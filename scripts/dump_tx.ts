/**
 * Debug script to generate and dump a create_alarm transaction in hex format.
 * Run with: npx ts-node scripts/dump_tx.ts
 */
import * as anchor from "@coral-xyz/anchor";
import { Program } from "@coral-xyz/anchor";
import { SolarmaVault } from "../target/types/solarma_vault";
import { Keypair, LAMPORTS_PER_SOL, PublicKey, SystemProgram, Transaction, Connection } from "@solana/web3.js";

async function main() {
    // Setup
    const connection = new Connection("https://api.devnet.solana.com", "confirmed");

    // Use a dummy keypair for transaction building (won't actually sign)
    const owner = Keypair.generate();
    console.log("Owner:", owner.publicKey.toBase58());

    // Program ID - must match deployed program
    const programId = new PublicKey("DK85nVhtwE71xR21p8vNJN7bL5TnxhLjXMDHHRWbKo1f");

    // Test parameters (matching Android app)
    const alarmId = new anchor.BN(1); // Same as Android test
    const alarmTime = new anchor.BN(1769923620); // Unix timestamp
    const deadline = new anchor.BN(1769927220);
    const depositAmount = new anchor.BN(10_000_000); // 0.01 SOL in lamports
    const penaltyRoute = 0; // BURN

    // Derive PDAs
    const [alarmPda] = PublicKey.findProgramAddressSync(
        [
            Buffer.from("alarm"),
            owner.publicKey.toBuffer(),
            alarmId.toArrayLike(Buffer, "le", 8),
        ],
        programId
    );
    console.log("Alarm PDA:", alarmPda.toBase58());

    const [vaultPda] = PublicKey.findProgramAddressSync(
        [Buffer.from("vault"), alarmPda.toBuffer()],
        programId
    );
    console.log("Vault PDA:", vaultPda.toBase58());

    // Build instruction data manually to show exact format
    // Anchor format: [8-byte discriminator][args...]
    // Discriminator for "create_alarm" = first 8 bytes of SHA256("global:create_alarm")
    const crypto = require("crypto");
    const discriminator = crypto
        .createHash("sha256")
        .update("global:create_alarm")
        .digest()
        .slice(0, 8);

    console.log("\n=== Anchor Discriminator ===");
    console.log("Discriminator hex:", discriminator.toString("hex"));

    // Build instruction data
    const dataBuffer = Buffer.alloc(8 + 8 + 8 + 8 + 8 + 1 + 1); // discriminator + args
    let offset = 0;

    // Discriminator
    discriminator.copy(dataBuffer, offset);
    offset += 8;

    // alarm_id: i64 LE
    alarmId.toArrayLike(Buffer, "le", 8).copy(dataBuffer, offset);
    offset += 8;

    // alarm_time: i64 LE
    alarmTime.toArrayLike(Buffer, "le", 8).copy(dataBuffer, offset);
    offset += 8;

    // deadline: i64 LE
    deadline.toArrayLike(Buffer, "le", 8).copy(dataBuffer, offset);
    offset += 8;

    // deposit_amount: u64 LE
    depositAmount.toArrayLike(Buffer, "le", 8).copy(dataBuffer, offset);
    offset += 8;

    // penalty_route: u8
    dataBuffer.writeUInt8(penaltyRoute, offset);
    offset += 1;

    // penalty_destination: Option<Pubkey> = None = 0
    dataBuffer.writeUInt8(0, offset);
    offset += 1;

    console.log("\n=== Instruction Data ===");
    console.log("Data hex:", dataBuffer.toString("hex"));
    console.log("Data length:", dataBuffer.length);

    // Build transaction using web3.js
    const { blockhash } = await connection.getLatestBlockhash();
    console.log("\nBlockhash:", blockhash);

    const instruction = new anchor.web3.TransactionInstruction({
        keys: [
            { pubkey: alarmPda, isSigner: false, isWritable: true },
            { pubkey: vaultPda, isSigner: false, isWritable: true },
            { pubkey: owner.publicKey, isSigner: true, isWritable: true },
            { pubkey: SystemProgram.programId, isSigner: false, isWritable: false },
        ],
        programId,
        data: dataBuffer,
    });

    const transaction = new Transaction();
    transaction.add(instruction);
    transaction.recentBlockhash = blockhash;
    transaction.feePayer = owner.publicKey;

    // Serialize WITHOUT signing (this is what we send to MWA)
    const message = transaction.compileMessage();
    const serializedMessage = message.serialize();

    console.log("\n=== Serialized Message ===");
    console.log("Message hex:", serializedMessage.toString("hex"));
    console.log("Message length:", serializedMessage.length);

    // Parse message structure
    console.log("\n=== Message Structure ===");
    let idx = 0;
    const numSigners = serializedMessage[idx++];
    const numReadonlySigned = serializedMessage[idx++];
    const numReadonlyUnsigned = serializedMessage[idx++];
    console.log(`Header: numSigners=${numSigners}, readonlySigned=${numReadonlySigned}, readonlyUnsigned=${numReadonlyUnsigned}`);

    // Account count (compact-u16)
    const accountCount = serializedMessage[idx++];
    console.log(`Account count: ${accountCount}`);

    // Print account keys
    console.log("Account keys:");
    for (let i = 0; i < accountCount; i++) {
        const key = serializedMessage.slice(idx, idx + 32);
        console.log(`  [${i}] ${new PublicKey(key).toBase58()}`);
        idx += 32;
    }

    // Blockhash
    const blockhashBytes = serializedMessage.slice(idx, idx + 32);
    console.log(`Blockhash: ${Buffer.from(blockhashBytes).toString("hex")}`);
    idx += 32;

    // Instruction count
    const instructionCount = serializedMessage[idx++];
    console.log(`Instruction count: ${instructionCount}`);

    // Instruction details
    const programIdIndex = serializedMessage[idx++];
    console.log(`Program ID index: ${programIdIndex}`);

    const accountIndicesCount = serializedMessage[idx++];
    console.log(`Account indices count: ${accountIndicesCount}`);

    const accountIndices = [];
    for (let i = 0; i < accountIndicesCount; i++) {
        accountIndices.push(serializedMessage[idx++]);
    }
    console.log(`Account indices: [${accountIndices.join(", ")}]`);

    const dataLength = serializedMessage[idx++];
    console.log(`Data length: ${dataLength}`);

    const data = serializedMessage.slice(idx, idx + dataLength);
    console.log(`Data: ${Buffer.from(data).toString("hex")}`);
}

main().catch(console.error);
