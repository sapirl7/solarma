/**
 * Debug script to simulate create_alarm transaction on devnet.
 * This will show the exact error from program execution.
 */
import * as anchor from "@coral-xyz/anchor";
import { Keypair, LAMPORTS_PER_SOL, PublicKey, SystemProgram, Transaction, Connection, sendAndConfirmTransaction } from "@solana/web3.js";
import * as crypto from "crypto";

async function main() {
    // Setup with devnet
    const connection = new Connection("https://api.devnet.solana.com", "confirmed");

    // Use the actual owner wallet from logs: DUMy9ui595PWvH3kszkD2WkUtRYZS6WGRfhrp5rcMif2
    const ownerPubkey = new PublicKey("DUMy9ui595PWvH3kszkD2WkUtRYZS6WGRfhrp5rcMif2");
    console.log("Owner:", ownerPubkey.toBase58());

    // CORRECT Program ID from devnet deployment
    const programId = new PublicKey("G9om7wycNjAcvFXbrUixjJpxtJrtXjamNc9yLnpSrJcp");
    console.log("Program:", programId.toBase58());

    // Test parameters (matching what was in logs: alarmId=1, time=1769925600000ms)
    const alarmId = new anchor.BN(1);
    const alarmTimeMs = 1769925600000; // From logs
    const alarmTime = new anchor.BN(Math.floor(alarmTimeMs / 1000)); // Unix seconds
    const deadline = new anchor.BN(Math.floor(alarmTimeMs / 1000) + 3600); // +1 hour
    const depositAmount = new anchor.BN(50_000_000); // 0.05 SOL in lamports
    const penaltyRoute = 0; // BURN

    console.log("\n=== Parameters ===");
    console.log("alarmId:", alarmId.toString());
    console.log("alarmTime:", alarmTime.toString(), `(${new Date(alarmTime.toNumber() * 1000).toISOString()})`);
    console.log("deadline:", deadline.toString(), `(${new Date(deadline.toNumber() * 1000).toISOString()})`);
    console.log("deposit:", depositAmount.toString(), "lamports");

    // Derive PDAs using correct seeds
    const [alarmPda] = PublicKey.findProgramAddressSync(
        [
            Buffer.from("alarm"),
            ownerPubkey.toBuffer(),
            alarmId.toArrayLike(Buffer, "le", 8),
        ],
        programId
    );
    console.log("\nAlarm PDA:", alarmPda.toBase58());

    const [vaultPda] = PublicKey.findProgramAddressSync(
        [Buffer.from("vault"), alarmPda.toBuffer()],
        programId
    );
    console.log("Vault PDA:", vaultPda.toBase58());

    // Discriminator for "create_alarm"
    const discriminator = crypto
        .createHash("sha256")
        .update("global:create_alarm")
        .digest()
        .slice(0, 8);
    console.log("\nDiscriminator:", discriminator.toString("hex"));

    // Build instruction data
    const dataBuffer = Buffer.alloc(8 + 8 + 8 + 8 + 8 + 1 + 1);
    let offset = 0;

    discriminator.copy(dataBuffer, offset); offset += 8;
    alarmId.toArrayLike(Buffer, "le", 8).copy(dataBuffer, offset); offset += 8;
    alarmTime.toArrayLike(Buffer, "le", 8).copy(dataBuffer, offset); offset += 8;
    deadline.toArrayLike(Buffer, "le", 8).copy(dataBuffer, offset); offset += 8;
    depositAmount.toArrayLike(Buffer, "le", 8).copy(dataBuffer, offset); offset += 8;
    dataBuffer.writeUInt8(penaltyRoute, offset); offset += 1;
    dataBuffer.writeUInt8(0, offset); // None for penalty_destination

    console.log("Data hex:", dataBuffer.toString("hex"));

    // Build transaction
    const { blockhash, lastValidBlockHeight } = await connection.getLatestBlockhash();
    console.log("\nBlockhash:", blockhash);

    const instruction = new anchor.web3.TransactionInstruction({
        keys: [
            { pubkey: alarmPda, isSigner: false, isWritable: true },
            { pubkey: vaultPda, isSigner: false, isWritable: true },
            { pubkey: ownerPubkey, isSigner: true, isWritable: true },
            { pubkey: SystemProgram.programId, isSigner: false, isWritable: false },
        ],
        programId,
        data: dataBuffer,
    });

    const transaction = new Transaction();
    transaction.add(instruction);
    transaction.recentBlockhash = blockhash;
    transaction.feePayer = ownerPubkey;

    // Simulate the transaction to see exact error
    console.log("\n=== SIMULATING TRANSACTION ===");
    try {
        const simulation = await connection.simulateTransaction(transaction);
        console.log("Simulation result:", JSON.stringify(simulation, null, 2));

        if (simulation.value.err) {
            console.log("\n❌ SIMULATION FAILED!");
            console.log("Error:", JSON.stringify(simulation.value.err));

            // Check for specific Anchor errors
            if (simulation.value.logs) {
                console.log("\nProgram logs:");
                simulation.value.logs.forEach((log, i) => {
                    console.log(`  [${i}] ${log}`);
                });
            }
        } else {
            console.log("\n✅ SIMULATION SUCCESS!");
            console.log("Units consumed:", simulation.value.unitsConsumed);
        }
    } catch (e: any) {
        console.log("\n❌ SIMULATION ERROR:", e.message);
        if (e.logs) {
            console.log("Logs:", e.logs);
        }
    }

    // Also dump the message for comparison
    const message = transaction.compileMessage();
    const serialized = message.serialize();
    console.log("\n=== Serialized Message ===");
    console.log("Length:", serialized.length, "bytes");
    console.log("Hex:", serialized.toString("hex"));
}

main().catch(console.error);
