package app.solarma.wallet

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Log
import androidx.core.content.edit
import dagger.hilt.android.qualifiers.ApplicationContext
import org.sol4k.Keypair
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.inject.Inject
import javax.inject.Singleton

/**
 * This device's ack key: an Ed25519 key set as `ack_authority` on new alarms, so
 * the alarm screen can sign `ack_and_claim` (and pay its fee) without opening the
 * wallet.
 *
 * The program only lets this key return a deposit to its owner, so a leaked key
 * can't move anyone's funds elsewhere; at most its own small fee balance is lost.
 * It is still stored encrypted with an AES key that never leaves the Android
 * Keystore, and app backups are disabled.
 */
@Singleton
class DeviceKeyStore
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
    ) {
        companion object {
            private const val TAG = "Solarma.DeviceKey"
            private const val PREFS = "solarma_device_key"
            private const val KEY_SECRET = "secret"
            private const val KEY_IV = "iv"
            private const val WRAP_ALIAS = "solarma_device_key_wrap"
            private const val ANDROID_KEYSTORE = "AndroidKeyStore"
            private const val TRANSFORMATION = "AES/GCM/NoPadding"
            private const val GCM_TAG_BITS = 128

            /** Rent-exempt minimum of a data-less system account; a fee payer must stay above it. */
            const val RENT_EXEMPT_MIN_LAMPORTS = 890_880L

            /** Worst-case fee of one device-signed ack_and_claim (base fee + capped priority fee). */
            const val MAX_ACK_FEE_LAMPORTS =
                5_000L + TransactionBuilder.DEVICE_COMPUTE_UNIT_LIMIT * TransactionBuilder.COMPUTE_UNIT_PRICE_MAX / 1_000_000L

            /** Amount the owner sends to the device key (with an alarm creation) when it runs low. */
            const val TOP_UP_LAMPORTS = 2_000_000L

            /** Whether a device key with [balance] lamports can't reliably pay a few more acks. */
            fun needsTopUp(balance: Long): Boolean = balance < RENT_EXEMPT_MIN_LAMPORTS + 2 * MAX_ACK_FEE_LAMPORTS

            /** Whether a device key with [balance] lamports can pay for one ack right now. */
            fun canPayAck(balance: Long): Boolean = balance >= RENT_EXEMPT_MIN_LAMPORTS + MAX_ACK_FEE_LAMPORTS
        }

        private val prefs by lazy { context.getSharedPreferences(PREFS, Context.MODE_PRIVATE) }

        @Volatile
        private var cached: Keypair? = null

        /** The device key, generated and stored on first use. */
        @Synchronized
        fun getOrCreate(): Keypair {
            cached?.let { return it }
            val keypair = load() ?: Keypair.generate().also { save(it) }
            cached = keypair
            return keypair
        }

        /** The device key if one was created before (never creates one). */
        @Synchronized
        fun existing(): Keypair? = cached ?: load()?.also { cached = it }

        private fun load(): Keypair? {
            val secret = prefs.getString(KEY_SECRET, null) ?: return null
            val iv = prefs.getString(KEY_IV, null) ?: return null
            return try {
                val cipher = Cipher.getInstance(TRANSFORMATION)
                cipher.init(Cipher.DECRYPT_MODE, wrappingKey(), GCMParameterSpec(GCM_TAG_BITS, Base64.decode(iv, Base64.NO_WRAP)))
                Keypair.fromSecretKey(cipher.doFinal(Base64.decode(secret, Base64.NO_WRAP)))
            } catch (e: Exception) {
                // e.g. the Keystore key was lost: alarms bound to the old key fall back to the wallet
                Log.e(TAG, "Device key unreadable; a new one will be created", e)
                null
            }
        }

        private fun save(keypair: Keypair) {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, wrappingKey())
            val encrypted = cipher.doFinal(keypair.secret)
            prefs.edit {
                putString(KEY_SECRET, Base64.encodeToString(encrypted, Base64.NO_WRAP))
                putString(KEY_IV, Base64.encodeToString(cipher.iv, Base64.NO_WRAP))
            }
            Log.i(TAG, "Device key created: ${keypair.publicKey.toBase58()}")
        }

        private fun wrappingKey(): SecretKey {
            val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
            (keyStore.getEntry(WRAP_ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
            val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
            generator.init(
                KeyGenParameterSpec.Builder(WRAP_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .build(),
            )
            return generator.generateKey()
        }
    }
