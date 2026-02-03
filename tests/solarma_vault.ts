import * as anchor from "@coral-xyz/anchor";
import { Program } from "@coral-xyz/anchor";
import { SolarmaVault } from "../target/types/solarma_vault";
import { expect } from "chai";
import { Keypair, LAMPORTS_PER_SOL, PublicKey, SystemProgram } from "@solana/web3.js";

describe("solarma_vault", () => {
    const provider = anchor.AnchorProvider.env();
    anchor.setProvider(provider);

    const program = anchor.workspace.SolarmaVault as Program<SolarmaVault>;
    const owner = provider.wallet;
    const burnSink = new PublicKey("1nc1nerator11111111111111111111111111111111");

    // Test alarm parameters
    const alarmId = new anchor.BN(Date.now());
    const alarmTime = new anchor.BN(Math.floor(Date.now() / 1000) + 3600); // 1 hour from now
    const deadline = new anchor.BN(Math.floor(Date.now() / 1000) + 7200); // 2 hours from now
    const depositAmount = new anchor.BN(0.1 * LAMPORTS_PER_SOL);

    let alarmPda: PublicKey;
    let alarmBump: number;
    let vaultPda: PublicKey;
    let vaultBump: number;

    before(async () => {
        // Derive PDAs
        [alarmPda, alarmBump] = PublicKey.findProgramAddressSync(
            [
                Buffer.from("alarm"),
                owner.publicKey.toBuffer(),
                alarmId.toArrayLike(Buffer, "le", 8),
            ],
            program.programId
        );

        [vaultPda, vaultBump] = PublicKey.findProgramAddressSync(
            [Buffer.from("vault"), alarmPda.toBuffer()],
            program.programId
        );

        console.log("Alarm PDA:", alarmPda.toBase58());
        console.log("Vault PDA:", vaultPda.toBase58());
    });

    it("Creates an alarm with deposit", async () => {
        const tx = await program.methods
            .createAlarm(
                alarmId,
                alarmTime,
                deadline,
                depositAmount,
                0, // Burn route
                null // No penalty destination
            )
            .accounts({
                alarm: alarmPda,
                vault: vaultPda,
                owner: owner.publicKey,
                systemProgram: SystemProgram.programId,
            })
            .rpc();

        console.log("Create alarm tx:", tx);

        // Verify alarm account
        const alarm = await program.account.alarm.fetch(alarmPda);
        expect(alarm.owner.toBase58()).to.equal(owner.publicKey.toBase58());
        expect(alarm.alarmTime.toNumber()).to.equal(alarmTime.toNumber());
        expect(alarm.initialAmount.toNumber()).to.equal(depositAmount.toNumber());
        expect(alarm.status.created).to.not.be.undefined;
    });

    it("Fails to claim before alarm time (TooEarly)", async () => {
        try {
            await program.methods
                .claim()
                .accounts({
                    alarm: alarmPda,
                    vault: vaultPda,
                    owner: owner.publicKey,
                    systemProgram: SystemProgram.programId,
                })
                .rpc();

            expect.fail("Should have thrown TooEarly error");
        } catch (err: any) {
            expect(err.error?.errorCode?.code).to.equal("TooEarly");
        }
    });

    it("Emergency refund before alarm time", async () => {
        // Create a new alarm for this test
        const newAlarmId = new anchor.BN(Date.now() + 1);
        const [newAlarmPda] = PublicKey.findProgramAddressSync(
            [
                Buffer.from("alarm"),
                owner.publicKey.toBuffer(),
                newAlarmId.toArrayLike(Buffer, "le", 8),
            ],
            program.programId
        );
        const [newVaultPda] = PublicKey.findProgramAddressSync(
            [Buffer.from("vault"), newAlarmPda.toBuffer()],
            program.programId
        );

        // Create
        await program.methods
            .createAlarm(
                newAlarmId,
                alarmTime,
                deadline,
                depositAmount,
                0,
                null
            )
            .accounts({
                alarm: newAlarmPda,
                vault: newVaultPda,
                owner: owner.publicKey,
                systemProgram: SystemProgram.programId,
            })
            .rpc();

        // Emergency refund
        const balanceBefore = await provider.connection.getBalance(owner.publicKey);

        await program.methods
            .emergencyRefund()
            .accounts({
                alarm: newAlarmPda,
                vault: newVaultPda,
                sink: burnSink,
                owner: owner.publicKey,
                systemProgram: SystemProgram.programId,
            })
            .rpc();

        const balanceAfter = await provider.connection.getBalance(owner.publicKey);

        // Should have received most of the deposit back (minus 5% fee and tx fees)
        expect(balanceAfter).to.be.greaterThan(balanceBefore);

        // Verify alarm is now Claimed
        const alarm = await program.account.alarm.fetch(newAlarmPda);
        expect(alarm.status.claimed).to.not.be.undefined;
    });

    it("Snooze reduces deposit", async () => {
        // Create a new alarm for snooze test
        const snoozeAlarmId = new anchor.BN(Date.now() + 2);
        const snoozeAlarmTime = new anchor.BN(Math.floor(Date.now() / 1000) - 60); // In the past (so snooze is valid)
        const snoozeDeadline = new anchor.BN(Math.floor(Date.now() / 1000) + 3600);

        const [snoozeAlarmPda] = PublicKey.findProgramAddressSync(
            [
                Buffer.from("alarm"),
                owner.publicKey.toBuffer(),
                snoozeAlarmId.toArrayLike(Buffer, "le", 8),
            ],
            program.programId
        );
        const [snoozeVaultPda] = PublicKey.findProgramAddressSync(
            [Buffer.from("vault"), snoozeAlarmPda.toBuffer()],
            program.programId
        );

        // Burn sink (must match BURN_SINK in constants.rs)
        // Create with past alarm time
        await program.methods
            .createAlarm(
                snoozeAlarmId,
                snoozeAlarmTime,
                snoozeDeadline,
                depositAmount,
                0,
                null
            )
            .accounts({
                alarm: snoozeAlarmPda,
                vault: snoozeVaultPda,
                owner: owner.publicKey,
                systemProgram: SystemProgram.programId,
            })
            .rpc();

        // Snooze
        await program.methods
            .snooze()
            .accounts({
                alarm: snoozeAlarmPda,
                vault: snoozeVaultPda,
                sink: burnSink,
                owner: owner.publicKey,
                systemProgram: SystemProgram.programId,
            })
            .rpc();

        const alarm = await program.account.alarm.fetch(snoozeAlarmPda);
        expect(alarm.snoozeCount).to.equal(1);
        // Remaining should be 90% of original
        const expectedRemaining = depositAmount.toNumber() * 0.9;
        expect(alarm.remainingAmount.toNumber()).to.be.approximately(expectedRemaining, 1000);
    });
});
