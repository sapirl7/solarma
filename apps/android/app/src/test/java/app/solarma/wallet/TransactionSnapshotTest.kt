package app.solarma.wallet

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.sol4k.PublicKey

class TransactionSnapshotTest {
    private fun loadSnapshotRoot(): JSONObject {
        val stream =
            javaClass.getResourceAsStream("/tx_snapshots/solarma_vault.json")
                ?: Thread.currentThread().contextClassLoader?.getResourceAsStream("tx_snapshots/solarma_vault.json")
        assertNotNull("Missing tx snapshot resource: tx_snapshots/solarma_vault.json", stream)
        val text = stream!!.bufferedReader(Charsets.UTF_8).use { it.readText() }
        return JSONObject(text)
    }

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

    @Test
    fun `transactions match golden snapshots`() {
        val root = loadSnapshotRoot()
        val blockhash = root.getString("blockhash")

        val instructionBuilder = SolarmaInstructionBuilder()
        val txBuilder = TransactionBuilder(SolanaRpcClient(), instructionBuilder)

        val cases = root.getJSONArray("cases")
        for (i in 0 until cases.length()) {
            val c = cases.getJSONObject(i)
            val name = c.getString("name")
            val inputs = c.getJSONObject("inputs")
            val op = inputs.getString("op")

            val (feePayer, instruction, derivedAlarmPda, derivedVaultPda) =
                when (op) {
                    "create_alarm" -> {
                        val owner = PublicKey(inputs.getString("owner"))
                        val alarmId = inputs.getLong("alarmId")
                        val alarmTime = inputs.getLong("alarmTime")
                        val deadline = inputs.getLong("deadline")
                        val depositLamports = inputs.getLong("depositLamports")
                        val penaltyRoute = inputs.getInt("penaltyRoute").toByte()
                        val penaltyDestination =
                            if (inputs.isNull("penaltyDestination")) {
                                null
                            } else {
                                PublicKey(inputs.getString("penaltyDestination"))
                            }

                        val pda = instructionBuilder.deriveAlarmPda(owner, alarmId)
                        val vault = instructionBuilder.deriveVaultPda(pda.address)

                        val ix =
                            instructionBuilder.buildCreateAlarm(
                                owner = owner,
                                alarmId = alarmId,
                                alarmTime = alarmTime,
                                deadline = deadline,
                                depositLamports = depositLamports,
                                penaltyRoute = penaltyRoute,
                                penaltyDestination = penaltyDestination,
                            )
                        Quad(owner, ix, pda.address, vault.address)
                    }

                    "ack_awake" -> {
                        val owner = PublicKey(inputs.getString("owner"))
                        val alarmId = inputs.getLong("alarmId")

                        val pda = instructionBuilder.deriveAlarmPda(owner, alarmId)
                        val ix = instructionBuilder.buildAckAwake(owner = owner, alarmPda = pda.address)
                        Quad(owner, ix, pda.address, null)
                    }

                    "snooze" -> {
                        val owner = PublicKey(inputs.getString("owner"))
                        val alarmId = inputs.getLong("alarmId")
                        val expected = inputs.getInt("expectedSnoozeCount")

                        val pda = instructionBuilder.deriveAlarmPda(owner, alarmId)
                        val vault = instructionBuilder.deriveVaultPda(pda.address)

                        val ix =
                            instructionBuilder.buildSnooze(
                                owner = owner,
                                alarmPda = pda.address,
                                sinkAddress = TransactionBuilder.BURN_SINK,
                                expectedSnoozeCount = expected,
                            )
                        Quad(owner, ix, pda.address, vault.address)
                    }

                    "claim" -> {
                        val owner = PublicKey(inputs.getString("owner"))
                        val alarmId = inputs.getLong("alarmId")

                        val pda = instructionBuilder.deriveAlarmPda(owner, alarmId)
                        val vault = instructionBuilder.deriveVaultPda(pda.address)

                        val ix = instructionBuilder.buildClaim(owner = owner, alarmPda = pda.address)
                        Quad(owner, ix, pda.address, vault.address)
                    }

                    "emergency_refund" -> {
                        val owner = PublicKey(inputs.getString("owner"))
                        val alarmId = inputs.getLong("alarmId")

                        val pda = instructionBuilder.deriveAlarmPda(owner, alarmId)
                        val vault = instructionBuilder.deriveVaultPda(pda.address)

                        val ix =
                            instructionBuilder.buildEmergencyRefund(
                                owner = owner,
                                alarmPda = pda.address,
                                sinkAddress = TransactionBuilder.BURN_SINK,
                            )
                        Quad(owner, ix, pda.address, vault.address)
                    }

                    "slash" -> {
                        val caller = PublicKey(inputs.getString("caller"))
                        val alarmId = inputs.getLong("alarmId")
                        val recipient = PublicKey(inputs.getString("penaltyRecipient"))

                        val pda = instructionBuilder.deriveAlarmPda(caller, alarmId)
                        val vault = instructionBuilder.deriveVaultPda(pda.address)

                        val ix =
                            instructionBuilder.buildSlash(
                                caller = caller,
                                alarmPda = pda.address,
                                penaltyRecipient = recipient,
                            )
                        Quad(caller, ix, pda.address, vault.address)
                    }

                    else -> error("Unknown snapshot op '$op' in case '$name'")
                }

            val expectedDerived = c.getJSONObject("derived")
            assertEquals("$name: alarmPda", expectedDerived.getString("alarmPda"), derivedAlarmPda.toBase58())
            if (expectedDerived.has("vaultPda")) {
                assertEquals("$name: vaultPda", expectedDerived.getString("vaultPda"), derivedVaultPda?.toBase58())
            }

            val expectedIx = c.getJSONObject("instruction")
            assertEquals("$name: programId", expectedIx.getString("programId"), instruction.programId.toBase58())

            val expectedAccounts = expectedIx.getJSONArray("accounts")
            assertEquals("$name: accounts.size", expectedAccounts.length(), instruction.accounts.size)
            for (j in 0 until expectedAccounts.length()) {
                val exp = expectedAccounts.getJSONObject(j)
                val act = instruction.accounts[j]
                assertEquals("$name: accounts[$j].pubkey", exp.getString("pubkey"), act.pubkey.toBase58())
                assertEquals("$name: accounts[$j].isSigner", exp.getBoolean("isSigner"), act.isSigner)
                assertEquals("$name: accounts[$j].isWritable", exp.getBoolean("isWritable"), act.isWritable)
            }

            assertEquals("$name: dataHex", expectedIx.getString("dataHex"), instruction.data.toHex())

            val expectedTxHex = c.getString("txHex")
            val actualTxHex =
                txBuilder.buildUnsignedTransactionForSnapshot(feePayer, instruction, blockhash).toHex()
            assertEquals("$name: txHex", expectedTxHex, actualTxHex)
        }
    }

    private data class Quad(
        val feePayer: PublicKey,
        val instruction: SolarmaInstruction,
        val alarmPda: PublicKey,
        val vaultPda: PublicKey?,
    )
}
