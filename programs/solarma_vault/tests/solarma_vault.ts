import * as anchor from "@coral-xyz/anchor";
import { Program } from "@coral-xyz/anchor";
import { SolarmaVault } from "../target/types/solarma_vault";
import { expect } from "chai";
import { SystemProgram, Keypair, LAMPORTS_PER_SOL } from "@solana/web3.js";

describe("solarma_vault", () => {
    const provider = anchor.AnchorProvider.env();
    anchor.setProvider(provider);

    const program = anchor.workspace.SolarmaVault as Program<SolarmaVault>;
    const owner = provider.wallet;

    // Burn sink address (must match constants.rs)
    const BURN_SINK = new anchor.web3.PublicKey(
        new Uint8Array([
            0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
            0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1,
        ])
    );

    describe("Initialize", () => {
        it("Creates a user profile", async () => {
            const [userProfile] = anchor.web3.PublicKey.findProgramAddressSync(
                [Buffer.from("user-profile"), owner.publicKey.toBuffer()],
                program.programId
            );

            await program.methods
                .initialize()
                .accounts({
                    userProfile,
                    owner: owner.publicKey,
                    systemProgram: SystemProgram.programId,
                })
                .rpc();

            const profile = await program.account.userProfile.fetch(userProfile);
            expect(profile.owner.toString()).to.equal(owner.publicKey.toString());
        });
    });

    describe("Create Alarm", () => {
        it("Creates an alarm without deposit", async () => {
            const alarmTime = Math.floor(Date.now() / 1000) + 3600; // 1 hour from now
            const deadline = alarmTime + 1800; // 30 minutes grace period

            const [alarm] = anchor.web3.PublicKey.findProgramAddressSync(
                [
                    Buffer.from("alarm"),
                    owner.publicKey.toBuffer(),
                    Buffer.from(new anchor.BN(alarmTime).toArrayLike(Buffer, "le", 8)),
                ],
                program.programId
            );

            const [vault] = anchor.web3.PublicKey.findProgramAddressSync(
                [Buffer.from("vault"), alarm.toBuffer()],
                program.programId
            );

            await program.methods
                .createAlarm(
                    new anchor.BN(alarmTime),
                    new anchor.BN(deadline),
                    new anchor.BN(0), // no deposit
                    0, // Burn route
                    null
                )
                .accounts({
                    alarm,
                    vault,
                    owner: owner.publicKey,
                    systemProgram: SystemProgram.programId,
                })
                .rpc();

            const alarmAccount = await program.account.alarm.fetch(alarm);
            expect(alarmAccount.owner.toString()).to.equal(owner.publicKey.toString());
            expect(alarmAccount.initialAmount.toNumber()).to.equal(0);
            expect(alarmAccount.status).to.deep.equal({ created: {} });
        });

        it("Creates an alarm with SOL deposit", async () => {
            const alarmTime = Math.floor(Date.now() / 1000) + 7200; // 2 hours from now
            const deadline = alarmTime + 1800;
            const depositAmount = 0.1 * LAMPORTS_PER_SOL; // 0.1 SOL

            const [alarm] = anchor.web3.PublicKey.findProgramAddressSync(
                [
                    Buffer.from("alarm"),
                    owner.publicKey.toBuffer(),
                    Buffer.from(new anchor.BN(alarmTime).toArrayLike(Buffer, "le", 8)),
                ],
                program.programId
            );

            const [vault] = anchor.web3.PublicKey.findProgramAddressSync(
                [Buffer.from("vault"), alarm.toBuffer()],
                program.programId
            );

            await program.methods
                .createAlarm(
                    new anchor.BN(alarmTime),
                    new anchor.BN(deadline),
                    new anchor.BN(depositAmount),
                    0, // Burn route
                    null
                )
                .accounts({
                    alarm,
                    vault,
                    owner: owner.publicKey,
                    systemProgram: SystemProgram.programId,
                })
                .rpc();

            const alarmAccount = await program.account.alarm.fetch(alarm);
            expect(alarmAccount.initialAmount.toNumber()).to.equal(depositAmount);
            expect(alarmAccount.remainingAmount.toNumber()).to.equal(depositAmount);
        });
    });

    describe("Invariants", () => {
        it("Cannot claim after deadline", async () => {
            // This test would require time manipulation
            // In practice, test with a past deadline
            console.log("Invariant test: claim after deadline - requires time mock");
        });

        it("Cannot slash before deadline", async () => {
            // This test would require creating an alarm and attempting immediate slash
            console.log("Invariant test: slash before deadline - requires time mock");
        });
    });
});
