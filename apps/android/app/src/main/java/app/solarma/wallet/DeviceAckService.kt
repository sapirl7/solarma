package app.solarma.wallet

import android.util.Log
import app.solarma.data.local.AlarmDao
import app.solarma.data.local.StatsDao
import app.solarma.data.local.StatsEntity
import kotlinx.coroutines.delay
import org.sol4k.PublicKey
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Settles a woken-up alarm with this device's key: signs `ack_and_claim` (deposit
 * back to the owner) and pays its fee, without the wallet. Used right after the wake
 * proof, from a background worker, so it works from the lock screen.
 */
@Singleton
class DeviceAckService
    @Inject
    constructor(
        private val deviceKeyStore: DeviceKeyStore,
        private val rpcClient: SolanaRpcClient,
        private val transactionBuilder: TransactionBuilder,
        private val alarmDao: AlarmDao,
        private val statsDao: StatsDao,
        private val transactionQueue: TransactionQueue,
    ) {
        companion object {
            private const val TAG = "Solarma.DeviceAck"
            private const val CONFIRM_POLL_MS = 2_000L
            private const val CONFIRM_ATTEMPTS = 20
        }

        enum class Outcome {
            /** The deposit was returned by this attempt. */
            RETURNED,

            /** Nothing left to do (already resolved / no deposit / too late). */
            DONE,

            /** Temporary problem (network, not confirmed yet, alarm time not reached): try again. */
            RETRY,

            /** The device key can't do it (alarm not bound to it, no fee balance, ...): use the wallet. */
            NEEDS_WALLET,
        }

        suspend fun ackAndClaim(alarmId: Long): Outcome {
            val alarm = alarmDao.getById(alarmId) ?: return Outcome.DONE
            val pda = alarm.onchainPubkey ?: return Outcome.DONE
            if (!alarm.hasDeposit) return Outcome.DONE
            val device = deviceKeyStore.existing() ?: return Outcome.NEEDS_WALLET

            val data = rpcClient.getAccountDataBase64(pda).getOrElse { return Outcome.RETRY }
            if (data == null) {
                // Closed: already settled (e.g. by an earlier attempt)
                alarmDao.resolveDeposit(alarm)
                return Outcome.DONE
            }
            val onchain = OnchainAlarmParser.parse(pda, data) ?: return Outcome.NEEDS_WALLET
            when (decide(onchain, device.publicKey.toBase58(), System.currentTimeMillis() / 1000)) {
                Decision.SEND -> Unit
                Decision.WAIT -> return Outcome.RETRY
                Decision.TOO_LATE -> return Outcome.DONE
                Decision.NEEDS_WALLET -> return Outcome.NEEDS_WALLET
            }

            val balance = rpcClient.getBalance(device.publicKey.toBase58()).getOrElse { return Outcome.RETRY }
            if (!DeviceKeyStore.canPayAck(balance)) {
                Log.w(TAG, "Device key balance $balance too low to pay the fee; falling back to wallet")
                return Outcome.NEEDS_WALLET
            }

            val signed = transactionBuilder.buildDeviceSignedAckAndClaim(device, PublicKey(pda), PublicKey(onchain.owner))
            val signature = Base58.firstSignatureOf(signed)
            val sendError = rpcClient.sendTransactionFanOut(signed).exceptionOrNull()
            if (sendError != null) return classify(sendError.message)

            repeat(CONFIRM_ATTEMPTS) {
                val status = rpcClient.getSignatureStatus(signature).getOrNull()
                if (status?.err != null) return classify(status.err)
                if (status?.confirmationStatus == "confirmed" || status?.confirmationStatus == "finalized") {
                    onReturned(alarmId, onchain.remainingAmount)
                    return Outcome.RETURNED
                }
                delay(CONFIRM_POLL_MS)
            }
            // Not seen yet: the next attempt re-reads the account (closed = done)
            return Outcome.RETRY
        }

        internal enum class Decision {
            /** Send ack_and_claim now. */
            SEND,

            /** Alarm time not reached on-chain yet. */
            WAIT,

            /** Deadline passed without an ack: the deposit can't be claimed anymore (slash path). */
            TOO_LATE,

            /** Not for the device key: already acknowledged (claim/sweep) or bound to another key. */
            NEEDS_WALLET,
        }

        /** What the device key should do for [onchain] at [nowSec]. Pure, unit-tested. */
        internal fun decide(
            onchain: OnchainAlarmAccount,
            deviceAddress: String,
            nowSec: Long,
        ): Decision =
            when {
                onchain.status != OnchainParameters.STATUS_CREATED -> Decision.NEEDS_WALLET
                onchain.ackAuthority != deviceAddress -> Decision.NEEDS_WALLET
                nowSec < onchain.alarmTimeUnix -> Decision.WAIT
                nowSec >= onchain.deadlineUnix -> Decision.TOO_LATE
                else -> Decision.SEND
            }

        private fun classify(error: String?): Outcome =
            when (ErrorClassifier.classify(error)) {
                ErrorClassifier.Category.NON_RETRYABLE -> Outcome.NEEDS_WALLET
                else -> Outcome.RETRY
            }

        private suspend fun onReturned(
            alarmId: Long,
            lamports: Long,
        ) {
            if (statsDao.getStatsOnce() == null) statsDao.insert(StatsEntity())
            if (lamports > 0) statsDao.addSaved(lamports)
            alarmDao.getById(alarmId)?.let { alarmDao.resolveDeposit(it) }
            Log.i(TAG, "Alarm $alarmId settled with the device key: $lamports lamports returned")
        }

        /** Hand over to the wallet flow (signed when the app is opened). */
        suspend fun fallBackToWallet(alarmId: Long) {
            if (!transactionQueue.hasActive(TransactionProcessor.TYPE_ACK_AND_CLAIM, alarmId)) {
                transactionQueue.enqueue(type = TransactionProcessor.TYPE_ACK_AND_CLAIM, alarmId = alarmId)
            }
        }
    }
