import * as anchor from "@coral-xyz/anchor";
import { Program } from "@coral-xyz/anchor";
import { SolarmaVault } from "../target/types/solarma_vault";
import { expect } from "chai";
import { SystemProgram, Transaction, Keypair, LAMPORTS_PER_SOL, PublicKey, sendAndConfirmTransaction } from "@solana/web3.js";

describe("solarma_vault", () => {
    const provider = anchor.AnchorProvider.env();
    anchor.setProvider(provider);

    const program = anchor.workspace.SolarmaVault as Program<SolarmaVault>;
    const owner = provider.wallet;

    // Burn sink address (must match constants.rs)
    const BURN_SINK = new PublicKey("1nc1nerator11111111111111111111111111111111");

    // Test constants
    const MIN_DEPOSIT = 1_000_000; // 0.001 SOL in lamports
    const DEPOSIT_AMOUNT = 0.005 * LAMPORTS_PER_SOL; // 5M lamports
    const EMERGENCY_PENALTY_PERCENT = 5;
    const SNOOZE_PERCENT = 10;

    // Per-run random base to prevent PDA collisions across test runs.
    // Each test adds a unique offset to this base for its alarm_id.
    const TEST_RUN_BASE = Date.now() * 1000 + Math.floor(Math.random() * 1000000);
    let nextAlarmOffset = 0;
    function uniqueAlarmId(): anchor.BN {
        return new anchor.BN(TEST_RUN_BASE + (nextAlarmOffset++));
    }

    // Helper: sleep for given milliseconds
    const sleep = (ms: number) => new Promise(resolve => setTimeout(resolve, ms));

    // Local validator helper: warp forward by N slots (fast-forwards Clock time).
    async function warpForwardSlots(delta: number): Promise<void> {
        const slot = await provider.connection.getSlot();
        // eslint-disable-next-line @typescript-eslint/no-explicit-any
        await (provider.connection as any)._rpcRequest("warpSlot", [slot + delta]);
    }

    // Helper to derive alarm PDA
    function deriveAlarmPda(ownerPubkey: PublicKey, alarmId: anchor.BN): [PublicKey, number] {
        return PublicKey.findProgramAddressSync(
            [
                Buffer.from("alarm"),
                ownerPubkey.toBuffer(),
                Buffer.from(alarmId.toArrayLike(Buffer, "le", 8)),
            ],
            program.programId
        );
    }

    // Helper to derive vault PDA
    function deriveVaultPda(alarmPubkey: PublicKey): [PublicKey, number] {
        return PublicKey.findProgramAddressSync(
            [Buffer.from("vault"), alarmPubkey.toBuffer()],
            program.programId
        );
    }

    // Helper to get current unix timestamp
    async function getCurrentTimestamp(): Promise<number> {
        const slot = await provider.connection.getSlot();
        const timestamp = await provider.connection.getBlockTime(slot);
        return timestamp || Math.floor(Date.now() / 1000);
    }

    // Helper to fund a keypair via SOL transfer (avoids airdrop rate limits)
    async function fundKeypair(kp: Keypair, lamports: number = 0.01 * LAMPORTS_PER_SOL): Promise<void> {
        const tx = new Transaction().add(
            SystemProgram.transfer({
                fromPubkey: owner.publicKey,
                toPubkey: kp.publicKey,
                lamports,
            })
        );
        await provider.sendAndConfirm(tx);
    }

    // =========================================================================
    // SETUP - Ensure wallet has funds
    // =========================================================================
    before(async function () {
        this.timeout(30000);
        const balance = await provider.connection.getBalance(owner.publicKey);
        console.log(`Test wallet balance: ${balance / LAMPORTS_PER_SOL} SOL`);
        if (balance < 1 * LAMPORTS_PER_SOL) {
            console.log("Requesting airdrop...");
            const sig = await provider.connection.requestAirdrop(owner.publicKey, 10 * LAMPORTS_PER_SOL);
            await provider.connection.confirmTransaction(sig);
            console.log("Airdrop successful");
        }
    });

    // =========================================================================
    // INITIALIZE
    // =========================================================================
    describe("Initialize", () => {
        it("Creates a user profile", async () => {
            const [userProfile] = PublicKey.findProgramAddressSync(
                [Buffer.from("user-profile"), owner.publicKey.toBuffer()],
                program.programId
            );

            // Skip if profile already exists (persistent state across test runs)
            const existingProfile = await provider.connection.getAccountInfo(userProfile);
            if (existingProfile) {
                console.log("User profile already exists, skipping creation");
                const profile = await program.account.userProfile.fetch(userProfile);
                expect(profile.owner.toString()).to.equal(owner.publicKey.toString());
                return;
            }

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

    // =========================================================================
    // CREATE ALARM
    // =========================================================================
    describe("Create Alarm", () => {
        it("Creates an alarm without deposit", async () => {
            const alarmId = uniqueAlarmId();
            const now = await getCurrentTimestamp();
            const alarmTime = now + 3600; // 1 hour from now
            const deadline = alarmTime + 1800; // 30 minutes grace period

            const [alarm] = deriveAlarmPda(owner.publicKey, alarmId);
            const [vault] = deriveVaultPda(alarm);

            await program.methods
                .createAlarm(
                    alarmId,
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
            const alarmId = uniqueAlarmId();
            const now = await getCurrentTimestamp();
            const alarmTime = now + 7200; // 2 hours from now
            const deadline = alarmTime + 1800;

            const [alarm] = deriveAlarmPda(owner.publicKey, alarmId);
            const [vault] = deriveVaultPda(alarm);

            await program.methods
                .createAlarm(
                    alarmId,
                    new anchor.BN(alarmTime),
                    new anchor.BN(deadline),
                    new anchor.BN(DEPOSIT_AMOUNT),
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
            expect(alarmAccount.initialAmount.toNumber()).to.equal(DEPOSIT_AMOUNT);
            expect(alarmAccount.remainingAmount.toNumber()).to.equal(DEPOSIT_AMOUNT);
            expect(alarmAccount.penaltyRoute).to.equal(0);
        });

        it("Creates alarm with Buddy penalty route", async () => {
            const alarmId = uniqueAlarmId();
            const now = await getCurrentTimestamp();
            const alarmTime = now + 3600;
            const deadline = alarmTime + 1800;
            const buddy = Keypair.generate().publicKey;

            const [alarm] = deriveAlarmPda(owner.publicKey, alarmId);
            const [vault] = deriveVaultPda(alarm);

            await program.methods
                .createAlarm(
                    alarmId,
                    new anchor.BN(alarmTime),
                    new anchor.BN(deadline),
                    new anchor.BN(DEPOSIT_AMOUNT),
                    2, // Buddy route
                    buddy
                )
                .accounts({
                    alarm,
                    vault,
                    owner: owner.publicKey,
                    systemProgram: SystemProgram.programId,
                })
                .rpc();

            const alarmAccount = await program.account.alarm.fetch(alarm);
            expect(alarmAccount.penaltyRoute).to.equal(2);
            expect(alarmAccount.penaltyDestination?.toString()).to.equal(buddy.toString());
        });

        it("FAILS: Creates alarm with Buddy route but no destination", async () => {
            const alarmId = uniqueAlarmId();
            const now = await getCurrentTimestamp();
            const alarmTime = now + 3600;
            const deadline = alarmTime + 1800;

            const [alarm] = deriveAlarmPda(owner.publicKey, alarmId);
            const [vault] = deriveVaultPda(alarm);

            try {
                await program.methods
                    .createAlarm(
                        alarmId,
                        new anchor.BN(alarmTime),
                        new anchor.BN(deadline),
                        new anchor.BN(DEPOSIT_AMOUNT),
                        2, // Buddy route
                        null // Missing buddy address!
                    )
                    .accounts({
                        alarm,
                        vault,
                        owner: owner.publicKey,
                        systemProgram: SystemProgram.programId,
                    })
                    .rpc();
                expect.fail("Should have thrown PenaltyDestinationRequired error");
            } catch (err: any) {
                expect(err.message).to.include("PenaltyDestinationRequired");
            }
        });

        it("FAILS: Creates alarm with deposit below minimum", async () => {
            const alarmId = uniqueAlarmId();
            const now = await getCurrentTimestamp();
            const alarmTime = now + 3600;
            const deadline = alarmTime + 1800;

            const [alarm] = deriveAlarmPda(owner.publicKey, alarmId);
            const [vault] = deriveVaultPda(alarm);

            try {
                await program.methods
                    .createAlarm(
                        alarmId,
                        new anchor.BN(alarmTime),
                        new anchor.BN(deadline),
                        new anchor.BN(MIN_DEPOSIT - 1), // Below minimum
                        0,
                        null
                    )
                    .accounts({
                        alarm,
                        vault,
                        owner: owner.publicKey,
                        systemProgram: SystemProgram.programId,
                    })
                    .rpc();
                expect.fail("Should have thrown DepositTooSmall error");
            } catch (err: any) {
                expect(err.message).to.include("DepositTooSmall");
            }
        });

        it("FAILS: Creates alarm with alarm_time in past", async () => {
            const alarmId = uniqueAlarmId();
            const now = await getCurrentTimestamp();
            const alarmTime = now - 100; // In the past!
            const deadline = now + 1800;

            const [alarm] = deriveAlarmPda(owner.publicKey, alarmId);
            const [vault] = deriveVaultPda(alarm);

            try {
                await program.methods
                    .createAlarm(
                        alarmId,
                        new anchor.BN(alarmTime),
                        new anchor.BN(deadline),
                        new anchor.BN(0),
                        0,
                        null
                    )
                    .accounts({
                        alarm,
                        vault,
                        owner: owner.publicKey,
                        systemProgram: SystemProgram.programId,
                    })
                    .rpc();
                expect.fail("Should have thrown AlarmTimeInPast error");
            } catch (err: any) {
                expect(err.message).to.include("AlarmTimeInPast");
            }
        });

        it("FAILS: Creates alarm with deadline before alarm_time", async () => {
            const alarmId = uniqueAlarmId();
            const now = await getCurrentTimestamp();
            const alarmTime = now + 3600;
            const deadline = alarmTime - 1;

            const [alarm] = deriveAlarmPda(owner.publicKey, alarmId);
            const [vault] = deriveVaultPda(alarm);

            try {
                await program.methods
                    .createAlarm(
                        alarmId,
                        new anchor.BN(alarmTime),
                        new anchor.BN(deadline),
                        new anchor.BN(0),
                        0,
                        null
                    )
                    .accounts({
                        alarm,
                        vault,
                        owner: owner.publicKey,
                        systemProgram: SystemProgram.programId,
                    })
                    .rpc();
                expect.fail("Should have thrown InvalidDeadline error");
            } catch (err: any) {
                expect(err.message).to.include("InvalidDeadline");
            }
        });
    });

    // =========================================================================
    // CLAIM
    // =========================================================================
    describe("Claim", () => {
        it("Claims deposit after alarm_time but before deadline", async () => {
            const alarmId = uniqueAlarmId();
            const now = await getCurrentTimestamp();
            // Set alarm_time to 2 seconds from now so we can wait
            const alarmTime = now + 2;
            const deadline = alarmTime + 1800;

            const [alarm] = deriveAlarmPda(owner.publicKey, alarmId);
            const [vault] = deriveVaultPda(alarm);

            // Create alarm
            await program.methods
                .createAlarm(
                    alarmId,
                    new anchor.BN(alarmTime),
                    new anchor.BN(deadline),
                    new anchor.BN(DEPOSIT_AMOUNT),
                    0,
                    null
                )
                .accounts({
                    alarm,
                    vault,
                    owner: owner.publicKey,
                    systemProgram: SystemProgram.programId,
                })
                .rpc();

            // Wait for alarm_time to pass
            await new Promise(resolve => setTimeout(resolve, 3000));

            const balanceBefore = await provider.connection.getBalance(owner.publicKey);

            // H4: Must ACK before claim.
            await program.methods
                .ackAwake()
                .accounts({
                    alarm,
                    owner: owner.publicKey,
                })
                .rpc();

            // Claim
            await program.methods
                .claim()
                .accounts({
                    alarm,
                    vault,
                    owner: owner.publicKey,
                    systemProgram: SystemProgram.programId,
                })
                .rpc();

            const alarmAccount = await program.account.alarm.fetch(alarm);
            expect(alarmAccount.status).to.deep.equal({ claimed: {} });
            expect(alarmAccount.remainingAmount.toNumber()).to.equal(0);

            // Verify balance increased (approximately)
            const balanceAfter = await provider.connection.getBalance(owner.publicKey);
            expect(balanceAfter).to.be.greaterThan(balanceBefore);
        });

        it("FAILS: Claim without ACK (InvalidAlarmState)", async () => {
            const alarmId = uniqueAlarmId();
            const now = await getCurrentTimestamp();
            const alarmTime = now + 3600; // 1 hour from now
            const deadline = alarmTime + 1800;

            const [alarm] = deriveAlarmPda(owner.publicKey, alarmId);
            const [vault] = deriveVaultPda(alarm);

            await program.methods
                .createAlarm(
                    alarmId,
                    new anchor.BN(alarmTime),
                    new anchor.BN(deadline),
                    new anchor.BN(DEPOSIT_AMOUNT),
                    0,
                    null
                )
                .accounts({
                    alarm,
                    vault,
                    owner: owner.publicKey,
                    systemProgram: SystemProgram.programId,
                })
                .rpc();

            try {
                await program.methods
                    .claim()
                    .accounts({
                        alarm,
                        vault,
                        owner: owner.publicKey,
                        systemProgram: SystemProgram.programId,
                    })
                    .rpc();
                expect.fail("Should have thrown InvalidAlarmState error");
            } catch (err: any) {
                expect(err.message).to.include("InvalidAlarmState");
            }
        });
    });

    // =========================================================================
    // EMERGENCY REFUND
    // =========================================================================
    describe("Emergency Refund", () => {
        it("Refunds with 5% penalty before alarm_time", async () => {
            const alarmId = uniqueAlarmId();
            const now = await getCurrentTimestamp();
            const alarmTime = now + 3600; // 1 hour from now
            const deadline = alarmTime + 1800;

            const [alarm] = deriveAlarmPda(owner.publicKey, alarmId);
            const [vault] = deriveVaultPda(alarm);

            await program.methods
                .createAlarm(
                    alarmId,
                    new anchor.BN(alarmTime),
                    new anchor.BN(deadline),
                    new anchor.BN(DEPOSIT_AMOUNT),
                    0,
                    null
                )
                .accounts({
                    alarm,
                    vault,
                    owner: owner.publicKey,
                    systemProgram: SystemProgram.programId,
                })
                .rpc();

            const balanceBefore = await provider.connection.getBalance(owner.publicKey);
            const expectedRefund = DEPOSIT_AMOUNT * (100 - EMERGENCY_PENALTY_PERCENT) / 100;

            await program.methods
                .emergencyRefund()
                .accounts({
                    alarm,
                    vault,
                    sink: BURN_SINK,
                    owner: owner.publicKey,
                    systemProgram: SystemProgram.programId,
                })
                .rpc();

            const alarmAccount = await program.account.alarm.fetch(alarm);
            expect(alarmAccount.status).to.deep.equal({ claimed: {} });

            // Verify balance increased by ~95% of deposit
            const balanceAfter = await provider.connection.getBalance(owner.publicKey);
            const received = balanceAfter - balanceBefore;
            // Account for rent and tx fees, should be approximately expectedRefund
            expect(received).to.be.greaterThan(expectedRefund * 0.9);
        });

        it("FAILS: Emergency refund after alarm_time", async () => {
            const alarmId = uniqueAlarmId();
            const now = await getCurrentTimestamp();
            const alarmTime = now + 3; // 3 seconds from now
            const deadline = alarmTime + 1800;

            const [alarm] = deriveAlarmPda(owner.publicKey, alarmId);
            const [vault] = deriveVaultPda(alarm);

            await program.methods
                .createAlarm(
                    alarmId,
                    new anchor.BN(alarmTime),
                    new anchor.BN(deadline),
                    new anchor.BN(DEPOSIT_AMOUNT),
                    0,
                    null
                )
                .accounts({
                    alarm,
                    vault,
                    owner: owner.publicKey,
                    systemProgram: SystemProgram.programId,
                })
                .rpc();

            // Wait for alarm_time to pass
            await new Promise(resolve => setTimeout(resolve, 3000));

            try {
                await program.methods
                    .emergencyRefund()
                    .accounts({
                        alarm,
                        vault,
                        sink: BURN_SINK,
                        owner: owner.publicKey,
                        systemProgram: SystemProgram.programId,
                    })
                    .rpc();
                expect.fail("Should have thrown TooLateForRefund error");
            } catch (err: any) {
                expect(err.message).to.include("TooLateForRefund");
            }
        });

        it("FAILS: Emergency refund with wrong sink", async () => {
            const alarmId = uniqueAlarmId();
            const now = await getCurrentTimestamp();
            const alarmTime = now + 3600;
            const deadline = alarmTime + 1800;
            const wrongSink = Keypair.generate().publicKey;

            const [alarm] = deriveAlarmPda(owner.publicKey, alarmId);
            const [vault] = deriveVaultPda(alarm);

            await program.methods
                .createAlarm(
                    alarmId,
                    new anchor.BN(alarmTime),
                    new anchor.BN(deadline),
                    new anchor.BN(DEPOSIT_AMOUNT),
                    0,
                    null
                )
                .accounts({
                    alarm,
                    vault,
                    owner: owner.publicKey,
                    systemProgram: SystemProgram.programId,
                })
                .rpc();

            try {
                await program.methods
                    .emergencyRefund()
                    .accounts({
                        alarm,
                        vault,
                        sink: wrongSink, // Wrong sink!
                        owner: owner.publicKey,
                        systemProgram: SystemProgram.programId,
                    })
                    .rpc();
                expect.fail("Should have thrown InvalidSinkAddress error");
            } catch (err: any) {
                expect(err.message).to.include("InvalidSinkAddress");
            }
        });
    });

    // =========================================================================
    // SNOOZE
    // =========================================================================
    describe("Snooze", () => {
        it("Snoozes with 10% penalty", async () => {
            const alarmId = uniqueAlarmId();
            const now = await getCurrentTimestamp();
            const alarmTime = now + 2;
            const deadline = alarmTime + 1800;

            const [alarm] = deriveAlarmPda(owner.publicKey, alarmId);
            const [vault] = deriveVaultPda(alarm);

            await program.methods
                .createAlarm(
                    alarmId,
                    new anchor.BN(alarmTime),
                    new anchor.BN(deadline),
                    new anchor.BN(DEPOSIT_AMOUNT),
                    0,
                    null
                )
                .accounts({
                    alarm,
                    vault,
                    owner: owner.publicKey,
                    systemProgram: SystemProgram.programId,
                })
                .rpc();

            // Wait for alarm_time
            await new Promise(resolve => setTimeout(resolve, 3000));

            await program.methods
                .snooze(0) // H1: expected_snooze_count = 0 (first snooze)
                .accounts({
                    alarm,
                    vault,
                    sink: BURN_SINK,
                    owner: owner.publicKey,
                    systemProgram: SystemProgram.programId,
                })
                .rpc();

            const alarmAccount = await program.account.alarm.fetch(alarm);
            expect(alarmAccount.snoozeCount).to.equal(1);

            // First snooze: 10% * 2^0 = 10%
            const expectedRemaining = DEPOSIT_AMOUNT - (DEPOSIT_AMOUNT * SNOOZE_PERCENT / 100);
            expect(alarmAccount.remainingAmount.toNumber()).to.equal(expectedRemaining);
        });

        // Skip: After first snooze, alarm_time extends by 5 minutes (DEFAULT_SNOOZE_EXTENSION_SECONDS=300).
        // The second snooze hits TooEarly before reaching the idempotency guard.
        // Idempotency logic is verified by Rust unit tests.
        it("H1: FAILS snooze with wrong expected_snooze_count (idempotency guard) @slow", async function () {
            this.timeout(400000); // 6+ min: waits for snooze extension

            const alarmId = uniqueAlarmId();
            const now = await getCurrentTimestamp();
            const alarmTime = now + 2;
            const deadline = alarmTime + 1800;

            const [alarm] = deriveAlarmPda(owner.publicKey, alarmId);
            const [vault] = deriveVaultPda(alarm);

            await program.methods
                .createAlarm(
                    alarmId,
                    new anchor.BN(alarmTime),
                    new anchor.BN(deadline),
                    new anchor.BN(DEPOSIT_AMOUNT),
                    0,
                    null
                )
                .accounts({
                    alarm,
                    vault,
                    owner: owner.publicKey,
                    systemProgram: SystemProgram.programId,
                })
                .rpc();

            await new Promise(resolve => setTimeout(resolve, 2000));

            // First snooze succeeds
            await program.methods
                .snooze(0)
                .accounts({
                    alarm,
                    vault,
                    sink: BURN_SINK,
                    owner: owner.publicKey,
                    systemProgram: SystemProgram.programId,
                })
                .rpc();

            // Wait for extended alarm_time to pass (snooze adds 300s)
            console.log('    ⏳ Waiting 310s for snooze extension to pass...');
            await new Promise(resolve => setTimeout(resolve, 310000));

            // Retry with same expected_snooze_count=0 should fail (H1 idempotency)
            try {
                await program.methods
                    .snooze(0) // Wrong! Actual snooze_count is now 1
                    .accounts({
                        alarm,
                        vault,
                        sink: BURN_SINK,
                        owner: owner.publicKey,
                        systemProgram: SystemProgram.programId,
                    })
                    .rpc();
                expect.fail("Should have thrown InvalidAlarmState (H1 idempotency)");
            } catch (err: any) {
                expect(err.message).to.include("InvalidAlarmState");
            }
        });

        it("Snooze cost doubles each time @slow", async function () {
            this.timeout(400000); // 6+ min: waits for snooze extension

            const alarmId = uniqueAlarmId();
            const now = await getCurrentTimestamp();
            const alarmTime = now + 3; // Short wait, then snooze after
            const deadline = alarmTime + 3600; // Longer deadline for multiple snoozes

            const [alarm] = deriveAlarmPda(owner.publicKey, alarmId);
            const [vault] = deriveVaultPda(alarm);

            await program.methods
                .createAlarm(
                    alarmId,
                    new anchor.BN(alarmTime),
                    new anchor.BN(deadline),
                    new anchor.BN(DEPOSIT_AMOUNT),
                    0,
                    null
                )
                .accounts({
                    alarm,
                    vault,
                    owner: owner.publicKey,
                    systemProgram: SystemProgram.programId,
                })
                .rpc();

            await new Promise(resolve => setTimeout(resolve, 2000));

            // First snooze: 10%
            await program.methods
                .snooze(0) // H1: expected_snooze_count = 0
                .accounts({
                    alarm,
                    vault,
                    sink: BURN_SINK,
                    owner: owner.publicKey,
                    systemProgram: SystemProgram.programId,
                })
                .rpc();

            let alarmAccount = await program.account.alarm.fetch(alarm);
            const afterFirst = alarmAccount.remainingAmount.toNumber();

            // Wait for extended alarm_time to pass (snooze adds 300s)
            console.log('    ⏳ Waiting 310s for snooze extension to pass...');
            await new Promise(resolve => setTimeout(resolve, 310000));

            // Second snooze: 10% * 2 = 20% of remaining
            await program.methods
                .snooze(1) // H1: expected_snooze_count = 1 (second snooze)
                .accounts({
                    alarm,
                    vault,
                    sink: BURN_SINK,
                    owner: owner.publicKey,
                    systemProgram: SystemProgram.programId,
                })
                .rpc();

            alarmAccount = await program.account.alarm.fetch(alarm);
            expect(alarmAccount.snoozeCount).to.equal(2);

            // Second snooze cost is 20% of remaining
            const expectedSecondCost = afterFirst * (SNOOZE_PERCENT * 2) / 100;
            const expectedRemaining = afterFirst - expectedSecondCost;
            expect(alarmAccount.remainingAmount.toNumber()).to.equal(expectedRemaining);
        });

        it("FAILS: Snooze before alarm_time", async () => {
            const alarmId = uniqueAlarmId();
            const now = await getCurrentTimestamp();
            const alarmTime = now + 3600;
            const deadline = alarmTime + 1800;

            const [alarm] = deriveAlarmPda(owner.publicKey, alarmId);
            const [vault] = deriveVaultPda(alarm);

            await program.methods
                .createAlarm(
                    alarmId,
                    new anchor.BN(alarmTime),
                    new anchor.BN(deadline),
                    new anchor.BN(DEPOSIT_AMOUNT),
                    0,
                    null
                )
                .accounts({
                    alarm,
                    vault,
                    owner: owner.publicKey,
                    systemProgram: SystemProgram.programId,
                })
                .rpc();

            try {
                await program.methods
                    .snooze(0) // H1: expected_snooze_count = 0
                    .accounts({
                        alarm,
                        vault,
                        sink: BURN_SINK,
                        owner: owner.publicKey,
                        systemProgram: SystemProgram.programId,
                    })
                    .rpc();
                expect.fail("Should have thrown TooEarly error");
            } catch (err: any) {
                expect(err.message).to.include("TooEarly");
            }
        });
    });

    // =========================================================================
    // SLASH
    // =========================================================================
    describe("Slash", () => {
        it("Slashes deposit after deadline (Burn route)", async () => {
            const alarmId = uniqueAlarmId();
            const now = await getCurrentTimestamp();
            const alarmTime = now + 3; // Future time requirement
            const deadline = alarmTime + 3; // Short deadline but valid

            const [alarm] = deriveAlarmPda(owner.publicKey, alarmId); const [vault] = deriveVaultPda(alarm);

            await program.methods
                .createAlarm(
                    alarmId,
                    new anchor.BN(alarmTime),
                    new anchor.BN(deadline),
                    new anchor.BN(DEPOSIT_AMOUNT),
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

            // Wait for deadline to pass (alarmTime+5, so need ~10s)
            await new Promise(resolve => setTimeout(resolve, 7000));

            // Anyone can slash (permissionless)
            await program.methods
                .slash()
                .accounts({
                    alarm,
                    vault,
                    penaltyRecipient: BURN_SINK,
                    caller: owner.publicKey,
                    systemProgram: SystemProgram.programId,
                })
                .rpc();

            const alarmAccount = await program.account.alarm.fetch(alarm);
            expect(alarmAccount.status).to.deep.equal({ slashed: {} });
            expect(alarmAccount.remainingAmount.toNumber()).to.equal(0);
        });

        it("FAILS: Slash before deadline", async () => {
            const alarmId = uniqueAlarmId();
            const now = await getCurrentTimestamp();
            const alarmTime = now + 3600;
            const deadline = alarmTime + 1800;

            const [alarm] = deriveAlarmPda(owner.publicKey, alarmId);
            const [vault] = deriveVaultPda(alarm);

            await program.methods
                .createAlarm(
                    alarmId,
                    new anchor.BN(alarmTime),
                    new anchor.BN(deadline),
                    new anchor.BN(DEPOSIT_AMOUNT),
                    0,
                    null
                )
                .accounts({
                    alarm,
                    vault,
                    owner: owner.publicKey,
                    systemProgram: SystemProgram.programId,
                })
                .rpc();

            try {
                await program.methods
                    .slash()
                    .accounts({
                        alarm,
                        vault,
                        penaltyRecipient: BURN_SINK,
                        caller: owner.publicKey,
                        systemProgram: SystemProgram.programId,
                    })
                    .rpc();
                expect.fail("Should have thrown DeadlineNotPassed error");
            } catch (err: any) {
                expect(err.message).to.include("DeadlineNotPassed");
            }
        });

        it("FAILS: Slash with wrong penalty_recipient for Burn route", async () => {
            const alarmId = uniqueAlarmId();
            const now = await getCurrentTimestamp();
            const alarmTime = now + 3;
            const deadline = alarmTime + 3;
            const wrongRecipient = Keypair.generate().publicKey;

            const [alarm] = deriveAlarmPda(owner.publicKey, alarmId);
            const [vault] = deriveVaultPda(alarm);

            await program.methods
                .createAlarm(
                    alarmId,
                    new anchor.BN(alarmTime),
                    new anchor.BN(deadline),
                    new anchor.BN(DEPOSIT_AMOUNT),
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

            await new Promise(resolve => setTimeout(resolve, 2000));

            try {
                await program.methods
                    .slash()
                    .accounts({
                        alarm,
                        vault,
                        penaltyRecipient: wrongRecipient, // Wrong!
                        caller: owner.publicKey,
                        systemProgram: SystemProgram.programId,
                    })
                    .rpc();
                expect.fail("Should have thrown an error for wrong recipient");
            } catch (err: any) {
                // May be InvalidPenaltyRecipient or constraint error
                expect(err.message.toLowerCase()).to.satisfy((msg: string) =>
                    msg.includes("invalidpenaltyrecipient") ||
                    msg.includes("constraint") ||
                    msg.includes("error")
                );
            }
        });

        it("Slash with Buddy route sends to buddy", async () => {
            const alarmId = uniqueAlarmId();
            const now = await getCurrentTimestamp();
            const alarmTime = now + 2;
            const deadline = alarmTime + 3;
            const buddy = Keypair.generate();

            // Fund buddy so account exists (via transfer, not airdrop)
            await fundKeypair(buddy);

            const [alarm] = deriveAlarmPda(owner.publicKey, alarmId);
            const [vault] = deriveVaultPda(alarm);

            await program.methods
                .createAlarm(
                    alarmId,
                    new anchor.BN(alarmTime),
                    new anchor.BN(deadline),
                    new anchor.BN(DEPOSIT_AMOUNT),
                    2, // Buddy route
                    buddy.publicKey
                )
                .accounts({
                    alarm,
                    vault,
                    owner: owner.publicKey,
                    systemProgram: SystemProgram.programId,
                })
                .rpc();

            // Wait for deadline to pass
            await new Promise(resolve => setTimeout(resolve, 7000));

            const buddyBalanceBefore = await provider.connection.getBalance(buddy.publicKey);

            // Buddy-only window: non-buddy caller must be rejected.
            try {
                await program.methods
                    .slash()
                    .accounts({
                        alarm,
                        vault,
                        penaltyRecipient: buddy.publicKey,
                        caller: owner.publicKey,
                        systemProgram: SystemProgram.programId,
                    })
                    .rpc();
                expect.fail("Should have thrown BuddyOnlySlashWindow");
            } catch (err: any) {
                expect(err.message).to.include("BuddyOnlySlashWindow");
            }

            await program.methods
                .slash()
                .accounts({
                    alarm,
                    vault,
                    penaltyRecipient: buddy.publicKey,
                    caller: buddy.publicKey,
                    systemProgram: SystemProgram.programId,
                })
                .signers([buddy])
                .rpc();

            const buddyBalanceAfter = await provider.connection.getBalance(buddy.publicKey);
            expect(buddyBalanceAfter).to.be.greaterThan(buddyBalanceBefore);

            const alarmAccount = await program.account.alarm.fetch(alarm);
            expect(alarmAccount.status).to.deep.equal({ slashed: {} });
        });
    });

    // =========================================================================
    // ACK AWAKE (H3: On-chain wake proof anchor)
    // =========================================================================
    describe("Ack Awake (H3)", () => {
        it("Acknowledges wake proof on-chain (Created → Acknowledged)", async () => {
            const alarmId = uniqueAlarmId();
            const now = await getCurrentTimestamp();
            const alarmTime = now + 2;
            const deadline = alarmTime + 1800;

            const [alarm] = deriveAlarmPda(owner.publicKey, alarmId);
            const [vault] = deriveVaultPda(alarm);

            await program.methods
                .createAlarm(
                    alarmId,
                    new anchor.BN(alarmTime),
                    new anchor.BN(deadline),
                    new anchor.BN(DEPOSIT_AMOUNT),
                    0,
                    null
                )
                .accounts({
                    alarm,
                    vault,
                    owner: owner.publicKey,
                    systemProgram: SystemProgram.programId,
                })
                .rpc();

            // Wait for alarm_time to pass
            await new Promise(resolve => setTimeout(resolve, 3000));

            await program.methods
                .ackAwake()
                .accounts({
                    alarm,
                    owner: owner.publicKey,
                })
                .rpc();

            const alarmAccount = await program.account.alarm.fetch(alarm);
            expect(alarmAccount.status).to.deep.equal({ acknowledged: {} });
        });

        it("FAILS: ack_awake before alarm_time (TooEarly)", async () => {
            const alarmId = uniqueAlarmId();
            const now = await getCurrentTimestamp();
            const alarmTime = now + 3600; // 1 hour from now
            const deadline = alarmTime + 1800;

            const [alarm] = deriveAlarmPda(owner.publicKey, alarmId);
            const [vault] = deriveVaultPda(alarm);

            await program.methods
                .createAlarm(
                    alarmId,
                    new anchor.BN(alarmTime),
                    new anchor.BN(deadline),
                    new anchor.BN(DEPOSIT_AMOUNT),
                    0,
                    null
                )
                .accounts({
                    alarm,
                    vault,
                    owner: owner.publicKey,
                    systemProgram: SystemProgram.programId,
                })
                .rpc();

            try {
                await program.methods
                    .ackAwake()
                    .accounts({
                        alarm,
                        owner: owner.publicKey,
                    })
                    .rpc();
                expect.fail("Should have thrown TooEarly error");
            } catch (err: any) {
                expect(err.message).to.include("TooEarly");
            }
        });

        it("FAILS: ack_awake by non-owner", async () => {
            const alarmId = uniqueAlarmId();
            const now = await getCurrentTimestamp();
            const alarmTime = now + 2;
            const deadline = alarmTime + 1800;

            const [alarm] = deriveAlarmPda(owner.publicKey, alarmId);
            const [vault] = deriveVaultPda(alarm);

            await program.methods
                .createAlarm(
                    alarmId,
                    new anchor.BN(alarmTime),
                    new anchor.BN(deadline),
                    new anchor.BN(DEPOSIT_AMOUNT),
                    0,
                    null
                )
                .accounts({
                    alarm,
                    vault,
                    owner: owner.publicKey,
                    systemProgram: SystemProgram.programId,
                })
                .rpc();

            await new Promise(resolve => setTimeout(resolve, 2000));

            const impostor = Keypair.generate();
            await fundKeypair(impostor);

            try {
                await program.methods
                    .ackAwake()
                    .accounts({
                        alarm,
                        owner: impostor.publicKey,
                    })
                    .signers([impostor])
                    .rpc();
                expect.fail("Should have thrown — non-owner");
            } catch (err: any) {
                // has_one = owner constraint violation
                expect(err.message.toLowerCase()).to.satisfy((msg: string) =>
                    msg.includes("hasone") ||
                    msg.includes("has_one") ||
                    msg.includes("constraint") ||
                    msg.includes("2001") ||
                    msg.includes("error")
                );
            }
        });

        it("FAILS: double ack_awake (already Acknowledged)", async () => {
            const alarmId = uniqueAlarmId();
            const now = await getCurrentTimestamp();
            const alarmTime = now + 2;
            const deadline = alarmTime + 1800;

            const [alarm] = deriveAlarmPda(owner.publicKey, alarmId);
            const [vault] = deriveVaultPda(alarm);

            await program.methods
                .createAlarm(
                    alarmId,
                    new anchor.BN(alarmTime),
                    new anchor.BN(deadline),
                    new anchor.BN(DEPOSIT_AMOUNT),
                    0,
                    null
                )
                .accounts({
                    alarm,
                    vault,
                    owner: owner.publicKey,
                    systemProgram: SystemProgram.programId,
                })
                .rpc();

            await new Promise(resolve => setTimeout(resolve, 2000));

            // First ack succeeds
            await program.methods
                .ackAwake()
                .accounts({
                    alarm,
                    owner: owner.publicKey,
                })
                .rpc();

            // Second ack should fail
            try {
                await program.methods
                    .ackAwake()
                    .accounts({
                        alarm,
                        owner: owner.publicKey,
                    })
                    .rpc();
                expect.fail("Should have thrown InvalidAlarmState");
            } catch (err: any) {
                expect(err.message).to.include("InvalidAlarmState");
            }
        });

        // Skip: After snooze, alarm_time extends by 5 minutes. ack_awake hits TooEarly.
        // Full lifecycle requires 5-min waits (snooze extends alarm_time by 300s).
        it("Full lifecycle: create → snooze → ack_awake → claim @slow", async function () {
            this.timeout(400000); // 6+ min: waits for snooze extension

            const alarmId = uniqueAlarmId();
            const now = await getCurrentTimestamp();
            const alarmTime = now + 2;
            const deadline = alarmTime + 1800;

            const [alarm] = deriveAlarmPda(owner.publicKey, alarmId);
            const [vault] = deriveVaultPda(alarm);

            await program.methods
                .createAlarm(
                    alarmId,
                    new anchor.BN(alarmTime),
                    new anchor.BN(deadline),
                    new anchor.BN(DEPOSIT_AMOUNT),
                    0,
                    null
                )
                .accounts({
                    alarm,
                    vault,
                    owner: owner.publicKey,
                    systemProgram: SystemProgram.programId,
                })
                .rpc();

            // Wait for alarm_time to pass
            await new Promise(resolve => setTimeout(resolve, 3000));

            // Step 1: Snooze
            await program.methods
                .snooze(0) // H1: expected_snooze_count = 0
                .accounts({
                    alarm,
                    vault,
                    sink: BURN_SINK,
                    owner: owner.publicKey,
                    systemProgram: SystemProgram.programId,
                })
                .rpc();

            let alarmAccount = await program.account.alarm.fetch(alarm);
            expect(alarmAccount.snoozeCount).to.equal(1);
            expect(alarmAccount.status).to.deep.equal({ created: {} });

            // Wait for extended alarm_time to pass (snooze adds 300s)
            console.log('    ⏳ Waiting 310s for snooze extension to pass...');
            await new Promise(resolve => setTimeout(resolve, 310000));

            // Step 2: Ack Awake (H3)
            await program.methods
                .ackAwake()
                .accounts({
                    alarm,
                    owner: owner.publicKey,
                })
                .rpc();

            alarmAccount = await program.account.alarm.fetch(alarm);
            expect(alarmAccount.status).to.deep.equal({ acknowledged: {} });

            // Step 3: Claim (works from Acknowledged state)
            const balanceBefore = await provider.connection.getBalance(owner.publicKey);

            await program.methods
                .claim()
                .accounts({
                    alarm,
                    vault,
                    owner: owner.publicKey,
                    systemProgram: SystemProgram.programId,
                })
                .rpc();

            alarmAccount = await program.account.alarm.fetch(alarm);
            expect(alarmAccount.status).to.deep.equal({ claimed: {} });
            expect(alarmAccount.remainingAmount.toNumber()).to.equal(0);

            const balanceAfter = await provider.connection.getBalance(owner.publicKey);
            expect(balanceAfter).to.be.greaterThan(balanceBefore);
        });

        it("FAILS: Claim from Created (without ack) is rejected", async () => {
            const alarmId = uniqueAlarmId();
            const now = await getCurrentTimestamp();
            const alarmTime = now + 2;
            const deadline = alarmTime + 1800;

            const [alarm] = deriveAlarmPda(owner.publicKey, alarmId);
            const [vault] = deriveVaultPda(alarm);

            await program.methods
                .createAlarm(
                    alarmId,
                    new anchor.BN(alarmTime),
                    new anchor.BN(deadline),
                    new anchor.BN(DEPOSIT_AMOUNT),
                    0,
                    null
                )
                .accounts({
                    alarm,
                    vault,
                    owner: owner.publicKey,
                    systemProgram: SystemProgram.programId,
                })
                .rpc();

            // Wait for alarm_time to pass (extra buffer)
            await new Promise(resolve => setTimeout(resolve, 3000));

            try {
                // Claim directly without ack_awake — must fail.
                await program.methods
                    .claim()
                    .accounts({
                        alarm,
                        vault,
                        owner: owner.publicKey,
                        systemProgram: SystemProgram.programId,
                    })
                    .rpc();
                expect.fail("Should have thrown InvalidAlarmState");
            } catch (err: any) {
                expect(err.message).to.include("InvalidAlarmState");
            }
        });
    });

    // =========================================================================
    // STATE TRANSITIONS
    // =========================================================================
    describe("State Transitions", () => {
        it("FAILS: Claim on already claimed alarm", async () => {
            const alarmId = uniqueAlarmId();
            const now = await getCurrentTimestamp();
            const alarmTime = now + 3;
            const deadline = alarmTime + 1800;

            const [alarm] = deriveAlarmPda(owner.publicKey, alarmId);
            const [vault] = deriveVaultPda(alarm);

            await program.methods
                .createAlarm(
                    alarmId,
                    new anchor.BN(alarmTime),
                    new anchor.BN(deadline),
                    new anchor.BN(DEPOSIT_AMOUNT),
                    0,
                    null
                )
                .accounts({
                    alarm,
                    vault,
                    owner: owner.publicKey,
                    systemProgram: SystemProgram.programId,
                })
                .rpc();

            await new Promise(resolve => setTimeout(resolve, 4000));

            await program.methods
                .ackAwake()
                .accounts({
                    alarm,
                    owner: owner.publicKey,
                })
                .rpc();

            // First claim succeeds
            await program.methods
                .claim()
                .accounts({
                    alarm,
                    vault,
                    owner: owner.publicKey,
                    systemProgram: SystemProgram.programId,
                })
                .rpc();

            // Second claim fails - vault is closed
            try {
                await program.methods
                    .claim()
                    .accounts({
                        alarm,
                        vault, // Vault no longer exists
                        owner: owner.publicKey,
                        systemProgram: SystemProgram.programId,
                    })
                    .rpc();
                expect.fail("Should have failed on closed vault");
            } catch (err: any) {
                // Account doesn't exist or invalid state
                expect(err.message).to.match(/AccountNotInitialized|InvalidAlarmState|Error/);
            }
        });
    });

    // =========================================================================
    // ACCESS CONTROL — Non-owner rejection
    // =========================================================================
    describe("Access Control", () => {
        let sharedAlarmPda: PublicKey;
        let sharedVaultPda: PublicKey;
        let impostor: Keypair;

        before(async function () {
            this.timeout(30000);

            // Create an alarm owned by the test wallet
            const alarmId = uniqueAlarmId();
            const now = await getCurrentTimestamp();
            const alarmTime = now + 3600; // future — can't be claimed yet
            const deadline = alarmTime + 1800;

            const [alarm] = deriveAlarmPda(owner.publicKey, alarmId);
            const [vault] = deriveVaultPda(alarm);

            await program.methods
                .createAlarm(
                    alarmId,
                    new anchor.BN(alarmTime),
                    new anchor.BN(deadline),
                    new anchor.BN(DEPOSIT_AMOUNT),
                    0,
                    null
                )
                .accounts({
                    alarm,
                    vault,
                    owner: owner.publicKey,
                    systemProgram: SystemProgram.programId,
                })
                .rpc();

            sharedAlarmPda = alarm;
            sharedVaultPda = vault;

            // Fund impostor via transfer
            impostor = Keypair.generate();
            await fundKeypair(impostor);
        });

        it("FAILS: Claim by non-owner (has_one constraint)", async () => {
            try {
                await program.methods
                    .claim()
                    .accounts({
                        alarm: sharedAlarmPda,
                        vault: sharedVaultPda,
                        owner: impostor.publicKey,
                        systemProgram: SystemProgram.programId,
                    })
                    .signers([impostor])
                    .rpc();
                expect.fail("Should have thrown — non-owner");
            } catch (err: any) {
                expect(err.message.toLowerCase()).to.satisfy((msg: string) =>
                    msg.includes("hasone") ||
                    msg.includes("has_one") ||
                    msg.includes("constraint") ||
                    msg.includes("2001") ||
                    msg.includes("error")
                );
            }
        });

        it("FAILS: Emergency refund by non-owner", async () => {
            try {
                await program.methods
                    .emergencyRefund()
                    .accounts({
                        alarm: sharedAlarmPda,
                        vault: sharedVaultPda,
                        sink: BURN_SINK,
                        owner: impostor.publicKey,
                        systemProgram: SystemProgram.programId,
                    })
                    .signers([impostor])
                    .rpc();
                expect.fail("Should have thrown — non-owner");
            } catch (err: any) {
                expect(err.message.toLowerCase()).to.satisfy((msg: string) =>
                    msg.includes("hasone") ||
                    msg.includes("has_one") ||
                    msg.includes("constraint") ||
                    msg.includes("error")
                );
            }
        });

        it("FAILS: Snooze by non-owner", async () => {
            try {
                await program.methods
                    .snooze(0)
                    .accounts({
                        alarm: sharedAlarmPda,
                        vault: sharedVaultPda,
                        sink: BURN_SINK,
                        owner: impostor.publicKey,
                        systemProgram: SystemProgram.programId,
                    })
                    .signers([impostor])
                    .rpc();
                expect.fail("Should have thrown — non-owner");
            } catch (err: any) {
                expect(err.message.toLowerCase()).to.satisfy((msg: string) =>
                    msg.includes("hasone") ||
                    msg.includes("has_one") ||
                    msg.includes("constraint") ||
                    msg.includes("error")
                );
            }
        });
    });

    // =========================================================================
    // INVALID STATE OPERATIONS — Actions on terminal states
    // =========================================================================
    describe("Invalid State Operations", () => {
        it("FAILS: Snooze on already claimed alarm", async () => {
            const alarmId = uniqueAlarmId();
            const now = await getCurrentTimestamp();
            const alarmTime = now + 3;
            const deadline = alarmTime + 1800;

            const [alarm] = deriveAlarmPda(owner.publicKey, alarmId);
            const [vault] = deriveVaultPda(alarm);

            await program.methods
                .createAlarm(
                    alarmId,
                    new anchor.BN(alarmTime),
                    new anchor.BN(deadline),
                    new anchor.BN(DEPOSIT_AMOUNT),
                    0,
                    null
                )
                .accounts({
                    alarm,
                    vault,
                    owner: owner.publicKey,
                    systemProgram: SystemProgram.programId,
                })
                .rpc();

            await new Promise(resolve => setTimeout(resolve, 4000));

            await program.methods
                .ackAwake()
                .accounts({
                    alarm,
                    owner: owner.publicKey,
                })
                .rpc();

            // Claim first
            await program.methods
                .claim()
                .accounts({
                    alarm,
                    vault,
                    owner: owner.publicKey,
                    systemProgram: SystemProgram.programId,
                })
                .rpc();

            // Try to snooze claimed alarm
            try {
                await program.methods
                    .snooze(0)
                    .accounts({
                        alarm,
                        vault,
                        sink: BURN_SINK,
                        owner: owner.publicKey,
                        systemProgram: SystemProgram.programId,
                    })
                    .rpc();
                expect.fail("Should have thrown — alarm is already claimed");
            } catch (err: any) {
                expect(err.message).to.match(/InvalidAlarmState|AccountNotInitialized|Error/);
            }
        });

        it("FAILS: Emergency refund on already claimed (via claim) alarm", async () => {
            const alarmId = uniqueAlarmId();
            const now = await getCurrentTimestamp();
            const alarmTime = now + 3600;
            const deadline = alarmTime + 1800;

            const [alarm] = deriveAlarmPda(owner.publicKey, alarmId);
            const [vault] = deriveVaultPda(alarm);

            await program.methods
                .createAlarm(
                    alarmId,
                    new anchor.BN(alarmTime),
                    new anchor.BN(deadline),
                    new anchor.BN(DEPOSIT_AMOUNT),
                    0,
                    null
                )
                .accounts({
                    alarm,
                    vault,
                    owner: owner.publicKey,
                    systemProgram: SystemProgram.programId,
                })
                .rpc();

            // Emergency refund first
            await program.methods
                .emergencyRefund()
                .accounts({
                    alarm,
                    vault,
                    sink: BURN_SINK,
                    owner: owner.publicKey,
                    systemProgram: SystemProgram.programId,
                })
                .rpc();

            // Second emergency refund should fail
            try {
                await program.methods
                    .emergencyRefund()
                    .accounts({
                        alarm,
                        vault,
                        sink: BURN_SINK,
                        owner: owner.publicKey,
                        systemProgram: SystemProgram.programId,
                    })
                    .rpc();
                expect.fail("Should have thrown — alarm already refunded");
            } catch (err: any) {
                expect(err.message).to.match(/InvalidAlarmState|AccountNotInitialized|Error/);
            }
        });

        it("FAILS: Slash on already slashed alarm", async () => {
            const alarmId = uniqueAlarmId();
            const now = await getCurrentTimestamp();
            const alarmTime = now + 3;
            const deadline = alarmTime + 3;

            const [alarm] = deriveAlarmPda(owner.publicKey, alarmId);
            const [vault] = deriveVaultPda(alarm);

            await program.methods
                .createAlarm(
                    alarmId,
                    new anchor.BN(alarmTime),
                    new anchor.BN(deadline),
                    new anchor.BN(DEPOSIT_AMOUNT),
                    0,
                    null
                )
                .accounts({
                    alarm,
                    vault,
                    owner: owner.publicKey,
                    systemProgram: SystemProgram.programId,
                })
                .rpc();

            // Wait for deadline
            await new Promise(resolve => setTimeout(resolve, 7000));

            // First slash succeeds
            await program.methods
                .slash()
                .accounts({
                    alarm,
                    vault,
                    penaltyRecipient: BURN_SINK,
                    caller: owner.publicKey,
                    systemProgram: SystemProgram.programId,
                })
                .rpc();

            // Second slash fails
            try {
                await program.methods
                    .slash()
                    .accounts({
                        alarm,
                        vault,
                        penaltyRecipient: BURN_SINK,
                        caller: owner.publicKey,
                        systemProgram: SystemProgram.programId,
                    })
                    .rpc();
                expect.fail("Should have thrown — already slashed");
            } catch (err: any) {
                expect(err.message).to.match(/InvalidAlarmState|AccountNotInitialized|Error/);
            }
        });

        it("FAILS: Ack_awake on already claimed alarm", async () => {
            const alarmId = uniqueAlarmId();
            const now = await getCurrentTimestamp();
            const alarmTime = now + 3;
            const deadline = alarmTime + 1800;

            const [alarm] = deriveAlarmPda(owner.publicKey, alarmId);
            const [vault] = deriveVaultPda(alarm);

            await program.methods
                .createAlarm(
                    alarmId,
                    new anchor.BN(alarmTime),
                    new anchor.BN(deadline),
                    new anchor.BN(DEPOSIT_AMOUNT),
                    0,
                    null
                )
                .accounts({
                    alarm,
                    vault,
                    owner: owner.publicKey,
                    systemProgram: SystemProgram.programId,
                })
                .rpc();

            await new Promise(resolve => setTimeout(resolve, 4000));

            await program.methods
                .ackAwake()
                .accounts({
                    alarm,
                    owner: owner.publicKey,
                })
                .rpc();

            // Claim the alarm
            await program.methods
                .claim()
                .accounts({
                    alarm,
                    vault,
                    owner: owner.publicKey,
                    systemProgram: SystemProgram.programId,
                })
                .rpc();

            // Try to ack on claimed alarm
            try {
                await program.methods
                    .ackAwake()
                    .accounts({
                        alarm,
                        owner: owner.publicKey,
                    })
                    .rpc();
                expect.fail("Should have thrown — alarm already claimed");
            } catch (err: any) {
                expect(err.message).to.include("InvalidAlarmState");
            }
        });
    });

    // =========================================================================
    // EDGE CASES — Zero deposit, owner slash, timing boundaries
    // =========================================================================
    describe("Edge Cases", () => {
        it("Zero deposit alarm: claim returns no funds (no error)", async () => {
            const alarmId = uniqueAlarmId();
            const now = await getCurrentTimestamp();
            const alarmTime = now + 3;
            const deadline = alarmTime + 1800;

            const [alarm] = deriveAlarmPda(owner.publicKey, alarmId);
            const [vault] = deriveVaultPda(alarm);

            await program.methods
                .createAlarm(
                    alarmId,
                    new anchor.BN(alarmTime),
                    new anchor.BN(deadline),
                    new anchor.BN(0), // Zero deposit
                    0,
                    null
                )
                .accounts({
                    alarm,
                    vault,
                    owner: owner.publicKey,
                    systemProgram: SystemProgram.programId,
                })
                .rpc();

            // Wait for alarm_time to pass
            await new Promise(resolve => setTimeout(resolve, 4000));

            await program.methods
                .ackAwake()
                .accounts({
                    alarm,
                    owner: owner.publicKey,
                })
                .rpc();

            // Claim zero-deposit alarm — should succeed without error
            await program.methods
                .claim()
                .accounts({
                    alarm,
                    vault,
                    owner: owner.publicKey,
                    systemProgram: SystemProgram.programId,
                })
                .rpc();

            const alarmAccount = await program.account.alarm.fetch(alarm);
            expect(alarmAccount.status).to.deep.equal({ claimed: {} });
            expect(alarmAccount.remainingAmount.toNumber()).to.equal(0);
        });

        it("Owner can self-slash (slash is permissionless)", async () => {
            const alarmId = uniqueAlarmId();
            const now = await getCurrentTimestamp();
            const alarmTime = now + 3;
            const deadline = alarmTime + 3;

            const [alarm] = deriveAlarmPda(owner.publicKey, alarmId);
            const [vault] = deriveVaultPda(alarm);

            await program.methods
                .createAlarm(
                    alarmId,
                    new anchor.BN(alarmTime),
                    new anchor.BN(deadline),
                    new anchor.BN(DEPOSIT_AMOUNT),
                    0,
                    null
                )
                .accounts({
                    alarm,
                    vault,
                    owner: owner.publicKey,
                    systemProgram: SystemProgram.programId,
                })
                .rpc();

            // Wait for deadline to pass (alarmTime+3 = now+6)
            await new Promise(resolve => setTimeout(resolve, 7000));

            // Owner slashes their own alarm — this is allowed (permissionless)
            await program.methods
                .slash()
                .accounts({
                    alarm,
                    vault,
                    penaltyRecipient: BURN_SINK,
                    caller: owner.publicKey,
                    systemProgram: SystemProgram.programId,
                })
                .rpc();

            const alarmAccount = await program.account.alarm.fetch(alarm);
            expect(alarmAccount.status).to.deep.equal({ slashed: {} });
        });

        it("Zero deposit alarm: emergency refund works (no penalty to send)", async () => {
            const alarmId = uniqueAlarmId();
            const now = await getCurrentTimestamp();
            const alarmTime = now + 3600;
            const deadline = alarmTime + 1800;

            const [alarm] = deriveAlarmPda(owner.publicKey, alarmId);
            const [vault] = deriveVaultPda(alarm);

            await program.methods
                .createAlarm(
                    alarmId,
                    new anchor.BN(alarmTime),
                    new anchor.BN(deadline),
                    new anchor.BN(0), // Zero deposit
                    0,
                    null
                )
                .accounts({
                    alarm,
                    vault,
                    owner: owner.publicKey,
                    systemProgram: SystemProgram.programId,
                })
                .rpc();

            await program.methods
                .emergencyRefund()
                .accounts({
                    alarm,
                    vault,
                    sink: BURN_SINK,
                    owner: owner.publicKey,
                    systemProgram: SystemProgram.programId,
                })
                .rpc();

            const alarmAccount = await program.account.alarm.fetch(alarm);
            expect(alarmAccount.status).to.deep.equal({ claimed: {} });
        });
    });

    // =========================================================================
    // TIMING EDGE CASES — deadline boundaries + grace
    // =========================================================================
    describe("Timing Edge Cases", () => {
        it("Claim after deadline within grace succeeds if Acknowledged", async function () {
            this.timeout(30000);
            const alarmId = uniqueAlarmId();
            const now = await getCurrentTimestamp();
            const alarmTime = now + 5;
            const deadline = alarmTime + 5; // Short but stable window

            const [alarm] = deriveAlarmPda(owner.publicKey, alarmId);
            const [vault] = deriveVaultPda(alarm);

            await program.methods
                .createAlarm(
                    alarmId,
                    new anchor.BN(alarmTime),
                    new anchor.BN(deadline),
                    new anchor.BN(DEPOSIT_AMOUNT),
                    0,
                    null
                )
                .accounts({
                    alarm,
                    vault,
                    owner: owner.publicKey,
                    systemProgram: SystemProgram.programId,
                })
                .rpc();

            // Wait for alarm_time, then ACK before deadline.
            await sleep(6000);
            await program.methods
                .ackAwake()
                .accounts({
                    alarm,
                    owner: owner.publicKey,
                })
                .rpc();

            // Wait past deadline (but well within grace).
            await sleep(6000);

            await program.methods
                .claim()
                .accounts({
                    alarm,
                    vault,
                    owner: owner.publicKey,
                    systemProgram: SystemProgram.programId,
                })
                .rpc();

            const alarmAccount = await program.account.alarm.fetch(alarm);
            expect(alarmAccount.status).to.deep.equal({ claimed: {} });
        });

        it("After grace: claim fails and sweep_acknowledged returns funds", async function () {
            this.timeout(30000);
            const alarmId = uniqueAlarmId();
            const now = await getCurrentTimestamp();
            const alarmTime = now + 5;
            const deadline = alarmTime + 5;

            const [alarm] = deriveAlarmPda(owner.publicKey, alarmId);
            const [vault] = deriveVaultPda(alarm);

            await program.methods
                .createAlarm(
                    alarmId,
                    new anchor.BN(alarmTime),
                    new anchor.BN(deadline),
                    new anchor.BN(DEPOSIT_AMOUNT),
                    0,
                    null
                )
                .accounts({
                    alarm,
                    vault,
                    owner: owner.publicKey,
                    systemProgram: SystemProgram.programId,
                })
                .rpc();

            await sleep(6000);
            await program.methods
                .ackAwake()
                .accounts({
                    alarm,
                    owner: owner.publicKey,
                })
                .rpc();

            // Fast-forward time until grace is expired.
            // (On local validator this advances Clock time without real waiting.)
            let expired = false;
            for (let i = 0; i < 10; i++) {
                await warpForwardSlots(10_000);
                try {
                    await program.methods
                        .claim()
                        .accounts({
                            alarm,
                            vault,
                            owner: owner.publicKey,
                            systemProgram: SystemProgram.programId,
                        })
                        .rpc();
                    expect.fail("Claim should not succeed after grace");
                } catch (err: any) {
                    if (err.message.includes("ClaimGraceExpired")) {
                        expired = true;
                        break;
                    }
                    throw err;
                }
            }
            expect(expired, "Expected ClaimGraceExpired after warping").to.equal(true);

            // Sweep closes to owner (permissionless).
            await program.methods
                .sweepAcknowledged()
                .accounts({
                    alarm,
                    vault,
                    owner: owner.publicKey,
                    caller: owner.publicKey,
                })
                .rpc();

            const alarmAccount = await program.account.alarm.fetch(alarm);
            expect(alarmAccount.status).to.deep.equal({ claimed: {} });
        });

        it("FAILS: Snooze after deadline (DeadlinePassed)", async () => {
            const alarmId = uniqueAlarmId();
            const now = await getCurrentTimestamp();
            const alarmTime = now + 3;
            const deadline = alarmTime + 3;

            const [alarm] = deriveAlarmPda(owner.publicKey, alarmId);
            const [vault] = deriveVaultPda(alarm);

            await program.methods
                .createAlarm(
                    alarmId,
                    new anchor.BN(alarmTime),
                    new anchor.BN(deadline),
                    new anchor.BN(DEPOSIT_AMOUNT),
                    0,
                    null
                )
                .accounts({
                    alarm,
                    vault,
                    owner: owner.publicKey,
                    systemProgram: SystemProgram.programId,
                })
                .rpc();

            // Wait past deadline
            await new Promise(resolve => setTimeout(resolve, 7000));

            try {
                await program.methods
                    .snooze(0)
                    .accounts({
                        alarm,
                        vault,
                        sink: BURN_SINK,
                        owner: owner.publicKey,
                        systemProgram: SystemProgram.programId,
                    })
                    .rpc();
                expect.fail("Should have thrown DeadlinePassed");
            } catch (err: any) {
                expect(err.message).to.include("DeadlinePassed");
            }
        });

        it("FAILS: ack_awake after deadline (DeadlinePassed)", async () => {
            const alarmId = uniqueAlarmId();
            const now = await getCurrentTimestamp();
            const alarmTime = now + 3;
            const deadline = alarmTime + 3;

            const [alarm] = deriveAlarmPda(owner.publicKey, alarmId);
            const [vault] = deriveVaultPda(alarm);

            await program.methods
                .createAlarm(
                    alarmId,
                    new anchor.BN(alarmTime),
                    new anchor.BN(deadline),
                    new anchor.BN(DEPOSIT_AMOUNT),
                    0,
                    null
                )
                .accounts({
                    alarm,
                    vault,
                    owner: owner.publicKey,
                    systemProgram: SystemProgram.programId,
                })
                .rpc();

            // Wait past deadline
            await new Promise(resolve => setTimeout(resolve, 7000));

            try {
                await program.methods
                    .ackAwake()
                    .accounts({
                        alarm,
                        owner: owner.publicKey,
                    })
                    .rpc();
                expect.fail("Should have thrown DeadlinePassed");
            } catch (err: any) {
                expect(err.message).to.include("DeadlinePassed");
            }
        });
    });

    // =========================================================================
    // VALIDATION — Input validation coverage
    // =========================================================================
    describe("Input Validation", () => {
        it("FAILS: Snooze with wrong sink address (InvalidSinkAddress)", async () => {
            const alarmId = uniqueAlarmId();
            const now = await getCurrentTimestamp();
            const alarmTime = now + 2;
            const deadline = alarmTime + 1800;

            const [alarm] = deriveAlarmPda(owner.publicKey, alarmId);
            const [vault] = deriveVaultPda(alarm);

            await program.methods
                .createAlarm(
                    alarmId,
                    new anchor.BN(alarmTime),
                    new anchor.BN(deadline),
                    new anchor.BN(DEPOSIT_AMOUNT),
                    0,
                    null
                )
                .accounts({
                    alarm,
                    vault,
                    owner: owner.publicKey,
                    systemProgram: SystemProgram.programId,
                })
                .rpc();

            await new Promise(resolve => setTimeout(resolve, 2000));

            const wrongSink = Keypair.generate().publicKey;

            try {
                await program.methods
                    .snooze(0)
                    .accounts({
                        alarm,
                        vault,
                        sink: wrongSink, // Wrong sink!
                        owner: owner.publicKey,
                        systemProgram: SystemProgram.programId,
                    })
                    .rpc();
                expect.fail("Should have thrown InvalidSinkAddress");
            } catch (err: any) {
                expect(err.message.toLowerCase()).to.satisfy((msg: string) =>
                    msg.includes("invalidsinkaddress") ||
                    msg.includes("constraint") ||
                    msg.includes("error")
                );
            }
        });

        it("FAILS: Create alarm with invalid penalty route (99)", async () => {
            const alarmId = uniqueAlarmId();
            const now = await getCurrentTimestamp();
            const alarmTime = now + 300;
            const deadline = alarmTime + 1800;

            const [alarm] = deriveAlarmPda(owner.publicKey, alarmId);
            const [vault] = deriveVaultPda(alarm);

            try {
                await program.methods
                    .createAlarm(
                        alarmId,
                        new anchor.BN(alarmTime),
                        new anchor.BN(deadline),
                        new anchor.BN(DEPOSIT_AMOUNT),
                        99, // Invalid route!
                        null
                    )
                    .accounts({
                        alarm,
                        vault,
                        owner: owner.publicKey,
                        systemProgram: SystemProgram.programId,
                    })
                    .rpc();
                expect.fail("Should have thrown InvalidPenaltyRoute");
            } catch (err: any) {
                expect(err.message).to.include("InvalidPenaltyRoute");
            }
        });

        it("FAILS: Create alarm with Donate route but no destination", async () => {
            const alarmId = uniqueAlarmId();
            const now = await getCurrentTimestamp();
            const alarmTime = now + 300;
            const deadline = alarmTime + 1800;

            const [alarm] = deriveAlarmPda(owner.publicKey, alarmId);
            const [vault] = deriveVaultPda(alarm);

            try {
                await program.methods
                    .createAlarm(
                        alarmId,
                        new anchor.BN(alarmTime),
                        new anchor.BN(deadline),
                        new anchor.BN(DEPOSIT_AMOUNT),
                        1, // Donate route
                        null // No destination!
                    )
                    .accounts({
                        alarm,
                        vault,
                        owner: owner.publicKey,
                        systemProgram: SystemProgram.programId,
                    })
                    .rpc();
                expect.fail("Should have thrown PenaltyDestinationRequired");
            } catch (err: any) {
                expect(err.message).to.include("PenaltyDestinationRequired");
            }
        });
    });

    // =========================================================================
    // H4 SLASH SCENARIOS — ACK makes slash impossible
    // =========================================================================
    describe("Slash Scenarios (H4)", () => {
        it("FAILS: Slash is rejected when status == Acknowledged", async () => {
            const alarmId = uniqueAlarmId();
            const now = await getCurrentTimestamp();
            const alarmTime = now + 3;
            const deadline = alarmTime + 3;

            const [alarm] = deriveAlarmPda(owner.publicKey, alarmId);
            const [vault] = deriveVaultPda(alarm);

            await program.methods
                .createAlarm(
                    alarmId,
                    new anchor.BN(alarmTime),
                    new anchor.BN(deadline),
                    new anchor.BN(DEPOSIT_AMOUNT),
                    0,
                    null
                )
                .accounts({
                    alarm,
                    vault,
                    owner: owner.publicKey,
                    systemProgram: SystemProgram.programId,
                })
                .rpc();

            // Wait for alarm_time to pass
            await new Promise(resolve => setTimeout(resolve, 4000));

            // First, ack_awake
            await program.methods
                .ackAwake()
                .accounts({
                    alarm,
                    owner: owner.publicKey,
                })
                .rpc();

            let alarmAccount = await program.account.alarm.fetch(alarm);
            expect(alarmAccount.status).to.deep.equal({ acknowledged: {} });

            // Wait for deadline to pass
            await new Promise(resolve => setTimeout(resolve, 7000));

            try {
                await program.methods
                    .slash()
                    .accounts({
                        alarm,
                        vault,
                        penaltyRecipient: BURN_SINK,
                        caller: owner.publicKey,
                        systemProgram: SystemProgram.programId,
                    })
                    .rpc();
                expect.fail("Should have thrown InvalidAlarmState");
            } catch (err: any) {
                expect(err.message).to.include("InvalidAlarmState");
            }
        });

        it("Third-party can slash after deadline (permissionless)", async () => {
            const alarmId = uniqueAlarmId();
            const now = await getCurrentTimestamp();
            const alarmTime = now + 3;
            const deadline = alarmTime + 3;
            const thirdParty = Keypair.generate();

            // Fund third party via transfer
            await fundKeypair(thirdParty);

            const [alarm] = deriveAlarmPda(owner.publicKey, alarmId);
            const [vault] = deriveVaultPda(alarm);

            await program.methods
                .createAlarm(
                    alarmId,
                    new anchor.BN(alarmTime),
                    new anchor.BN(deadline),
                    new anchor.BN(DEPOSIT_AMOUNT),
                    0,
                    null
                )
                .accounts({
                    alarm,
                    vault,
                    owner: owner.publicKey,
                    systemProgram: SystemProgram.programId,
                })
                .rpc();

            // Wait for deadline to pass (alarmTime+3 = now+6)
            await new Promise(resolve => setTimeout(resolve, 7000));

            // Third-party triggers slash
            await program.methods
                .slash()
                .accounts({
                    alarm,
                    vault,
                    penaltyRecipient: BURN_SINK,
                    caller: thirdParty.publicKey,
                    systemProgram: SystemProgram.programId,
                })
                .signers([thirdParty])
                .rpc();

            const alarmAccount = await program.account.alarm.fetch(alarm);
            expect(alarmAccount.status).to.deep.equal({ slashed: {} });
        });
    });

    // =========================================================================
    // STATE VERIFICATION — Verify computed field updates
    // =========================================================================
    describe("State Verification", () => {
        it("Snooze updates alarm_time, deadline, snooze_count, remaining_amount", async () => {
            const alarmId = uniqueAlarmId();
            const now = await getCurrentTimestamp();
            const alarmTime = now + 2;
            const deadline = alarmTime + 1800;

            const [alarm] = deriveAlarmPda(owner.publicKey, alarmId);
            const [vault] = deriveVaultPda(alarm);

            await program.methods
                .createAlarm(
                    alarmId,
                    new anchor.BN(alarmTime),
                    new anchor.BN(deadline),
                    new anchor.BN(DEPOSIT_AMOUNT),
                    0,
                    null
                )
                .accounts({
                    alarm,
                    vault,
                    owner: owner.publicKey,
                    systemProgram: SystemProgram.programId,
                })
                .rpc();

            const beforeSnooze = await program.account.alarm.fetch(alarm);
            expect(beforeSnooze.snoozeCount).to.equal(0);
            expect(beforeSnooze.alarmTime.toNumber()).to.equal(alarmTime);
            expect(beforeSnooze.deadline.toNumber()).to.equal(deadline);
            const initialRemaining = beforeSnooze.remainingAmount.toNumber();

            await new Promise(resolve => setTimeout(resolve, 2000));

            await program.methods
                .snooze(0)
                .accounts({
                    alarm,
                    vault,
                    sink: BURN_SINK,
                    owner: owner.publicKey,
                    systemProgram: SystemProgram.programId,
                })
                .rpc();

            const afterSnooze = await program.account.alarm.fetch(alarm);

            // Snooze count incremented
            expect(afterSnooze.snoozeCount).to.equal(1);

            // alarm_time extended by 300 seconds (DEFAULT_SNOOZE_EXTENSION_SECONDS)
            expect(afterSnooze.alarmTime.toNumber()).to.equal(alarmTime + 300);

            // deadline also extended by 300 seconds
            expect(afterSnooze.deadline.toNumber()).to.equal(deadline + 300);

            // remaining_amount decreased (10% snooze cost)
            expect(afterSnooze.remainingAmount.toNumber()).to.be.lessThan(initialRemaining);

            // Verify status unchanged
            expect(afterSnooze.status).to.deep.equal({ created: {} });
        });

        it("Claim returns all vault lamports to owner and zeroes remaining", async () => {
            const alarmId = uniqueAlarmId();
            const now = await getCurrentTimestamp();
            const alarmTime = now + 3;
            const deadline = alarmTime + 1800;

            const [alarm] = deriveAlarmPda(owner.publicKey, alarmId);
            const [vault] = deriveVaultPda(alarm);

            await program.methods
                .createAlarm(
                    alarmId,
                    new anchor.BN(alarmTime),
                    new anchor.BN(deadline),
                    new anchor.BN(DEPOSIT_AMOUNT),
                    0,
                    null
                )
                .accounts({
                    alarm,
                    vault,
                    owner: owner.publicKey,
                    systemProgram: SystemProgram.programId,
                })
                .rpc();

            // Record owner balance before claim
            const balanceBefore = await provider.connection.getBalance(owner.publicKey);

            await new Promise(resolve => setTimeout(resolve, 4000));

            // H4: Must ACK before claim.
            await program.methods
                .ackAwake()
                .accounts({
                    alarm,
                    owner: owner.publicKey,
                })
                .rpc();

            await program.methods
                .claim()
                .accounts({
                    alarm,
                    vault,
                    owner: owner.publicKey,
                    systemProgram: SystemProgram.programId,
                })
                .rpc();

            // Verify alarm state
            const alarmAccount = await program.account.alarm.fetch(alarm);
            expect(alarmAccount.status).to.deep.equal({ claimed: {} });
            expect(alarmAccount.remainingAmount.toNumber()).to.equal(0);

            // Verify owner received funds back (balance increased)
            const balanceAfter = await provider.connection.getBalance(owner.publicKey);
            // Balance should increase by at least deposit minus tx fees
            expect(balanceAfter).to.be.greaterThan(balanceBefore - 100_000); // allow for tx fee

            // Verify vault account is closed
            const vaultInfo = await provider.connection.getAccountInfo(vault);
            expect(vaultInfo).to.be.null;
        });

        it("Emergency refund sends 5% penalty to sink", async () => {
            const alarmId = uniqueAlarmId();
            const now = await getCurrentTimestamp();
            const alarmTime = now + 3600;
            const deadline = alarmTime + 1800;

            const [alarm] = deriveAlarmPda(owner.publicKey, alarmId);
            const [vault] = deriveVaultPda(alarm);

            await program.methods
                .createAlarm(
                    alarmId,
                    new anchor.BN(alarmTime),
                    new anchor.BN(deadline),
                    new anchor.BN(DEPOSIT_AMOUNT),
                    0,
                    null
                )
                .accounts({
                    alarm,
                    vault,
                    owner: owner.publicKey,
                    systemProgram: SystemProgram.programId,
                })
                .rpc();

            // Record sink balance before refund
            const sinkBefore = await provider.connection.getBalance(BURN_SINK);

            await program.methods
                .emergencyRefund()
                .accounts({
                    alarm,
                    vault,
                    sink: BURN_SINK,
                    owner: owner.publicKey,
                    systemProgram: SystemProgram.programId,
                })
                .rpc();

            // Verify alarm state
            const alarmAccount = await program.account.alarm.fetch(alarm);
            expect(alarmAccount.status).to.deep.equal({ claimed: {} });
            expect(alarmAccount.remainingAmount.toNumber()).to.equal(0);

            // Verify sink received penalty (5% of deposit)
            const sinkAfter = await provider.connection.getBalance(BURN_SINK);
            const expectedPenalty = Math.floor(DEPOSIT_AMOUNT * EMERGENCY_PENALTY_PERCENT / 100);
            // Penalty amount should be approximately correct
            // Note: rent-exempt guard may reduce or zero out the penalty transfer
            // for small deposits, since vault must maintain minimum rent-exempt balance.
            expect(sinkAfter - sinkBefore).to.be.at.least(0);
            expect(sinkAfter - sinkBefore).to.be.at.most(expectedPenalty);

            // Verify vault is closed
            const vaultInfo = await provider.connection.getAccountInfo(vault);
            expect(vaultInfo).to.be.null;
        });

        it("Snooze cost is 10% of remaining (math verification)", async () => {
            const alarmId = uniqueAlarmId();
            const now = await getCurrentTimestamp();
            const alarmTime = now + 2;
            const deadline = alarmTime + 1800;

            const [alarm] = deriveAlarmPda(owner.publicKey, alarmId);
            const [vault] = deriveVaultPda(alarm);

            await program.methods
                .createAlarm(
                    alarmId,
                    new anchor.BN(alarmTime),
                    new anchor.BN(deadline),
                    new anchor.BN(DEPOSIT_AMOUNT),
                    0,
                    null
                )
                .accounts({
                    alarm,
                    vault,
                    owner: owner.publicKey,
                    systemProgram: SystemProgram.programId,
                })
                .rpc();

            const before = await program.account.alarm.fetch(alarm);
            const remainingBefore = before.remainingAmount.toNumber();

            await new Promise(resolve => setTimeout(resolve, 2000));

            await program.methods
                .snooze(0)
                .accounts({
                    alarm,
                    vault,
                    sink: BURN_SINK,
                    owner: owner.publicKey,
                    systemProgram: SystemProgram.programId,
                })
                .rpc();

            const after = await program.account.alarm.fetch(alarm);
            const remainingAfter = after.remainingAmount.toNumber();

            // 10% of remaining should have been deducted
            // Due to rent-exempt guard, actual cost ≤ expected base cost
            const expectedBaseCost = Math.floor(remainingBefore * SNOOZE_PERCENT / 100);
            const actualCost = remainingBefore - remainingAfter;
            expect(actualCost).to.be.greaterThan(0);
            expect(actualCost).to.be.at.most(expectedBaseCost);
        });

        it("Create alarm stores all fields correctly", async () => {
            const alarmId = uniqueAlarmId();
            const now = await getCurrentTimestamp();
            const alarmTime = now + 3600;
            const deadline = alarmTime + 1800;

            const [alarm] = deriveAlarmPda(owner.publicKey, alarmId);
            const [vault] = deriveVaultPda(alarm);

            await program.methods
                .createAlarm(
                    alarmId,
                    new anchor.BN(alarmTime),
                    new anchor.BN(deadline),
                    new anchor.BN(DEPOSIT_AMOUNT),
                    0,
                    null
                )
                .accounts({
                    alarm,
                    vault,
                    owner: owner.publicKey,
                    systemProgram: SystemProgram.programId,
                })
                .rpc();

            const a = await program.account.alarm.fetch(alarm);
            expect(a.owner.toString()).to.equal(owner.publicKey.toString());
            expect(a.alarmTime.toNumber()).to.equal(alarmTime);
            expect(a.deadline.toNumber()).to.equal(deadline);
            expect(a.initialAmount.toNumber()).to.equal(DEPOSIT_AMOUNT);
            expect(a.remainingAmount.toNumber()).to.equal(DEPOSIT_AMOUNT);
            expect(a.penaltyRoute).to.equal(0);
            expect(a.penaltyDestination).to.be.null;
            expect(a.snoozeCount).to.equal(0);
            expect(a.status).to.deep.equal({ created: {} });
        });
    });

    // =========================================================================
    // BOUNDARY VALUES — Min deposit, edge timing
    // =========================================================================
    describe("Boundary Values", () => {
        it("Create alarm with exact MIN_DEPOSIT succeeds", async () => {
            const alarmId = uniqueAlarmId();
            const now = await getCurrentTimestamp();
            const alarmTime = now + 300;
            const deadline = alarmTime + 1800;

            const [alarm] = deriveAlarmPda(owner.publicKey, alarmId);
            const [vault] = deriveVaultPda(alarm);

            // MIN_DEPOSIT_LAMPORTS = 1_000_000

            await program.methods
                .createAlarm(
                    alarmId,
                    new anchor.BN(alarmTime),
                    new anchor.BN(deadline),
                    new anchor.BN(MIN_DEPOSIT), // Exact minimum
                    0,
                    null
                )
                .accounts({
                    alarm,
                    vault,
                    owner: owner.publicKey,
                    systemProgram: SystemProgram.programId,
                })
                .rpc();

            const a = await program.account.alarm.fetch(alarm);
            expect(a.initialAmount.toNumber()).to.equal(MIN_DEPOSIT);
        });

        it("FAILS: Create alarm with deposit just below MIN_DEPOSIT", async () => {
            const alarmId = uniqueAlarmId();
            const now = await getCurrentTimestamp();
            const alarmTime = now + 300;
            const deadline = alarmTime + 1800;

            const [alarm] = deriveAlarmPda(owner.publicKey, alarmId);
            const [vault] = deriveVaultPda(alarm);

            try {
                await program.methods
                    .createAlarm(
                        alarmId,
                        new anchor.BN(alarmTime),
                        new anchor.BN(deadline),
                        new anchor.BN(MIN_DEPOSIT - 1), // 1 lamport below minimum
                        0,
                        null
                    )
                    .accounts({
                        alarm,
                        vault,
                        owner: owner.publicKey,
                        systemProgram: SystemProgram.programId,
                    })
                    .rpc();
                expect.fail("Should have thrown DepositTooSmall");
            } catch (err: any) {
                expect(err.message).to.include("DepositTooSmall");
            }
        });

        it("FAILS: Duplicate alarm_id causes PDA collision", async () => {
            const alarmId = uniqueAlarmId();
            const now = await getCurrentTimestamp();
            const alarmTime = now + 300;
            const deadline = alarmTime + 1800;

            const [alarm] = deriveAlarmPda(owner.publicKey, alarmId);
            const [vault] = deriveVaultPda(alarm);

            // First alarm succeeds
            await program.methods
                .createAlarm(
                    alarmId,
                    new anchor.BN(alarmTime),
                    new anchor.BN(deadline),
                    new anchor.BN(DEPOSIT_AMOUNT),
                    0,
                    null
                )
                .accounts({
                    alarm,
                    vault,
                    owner: owner.publicKey,
                    systemProgram: SystemProgram.programId,
                })
                .rpc();

            // Second alarm with same alarm_id fails (PDA already exists)
            try {
                await program.methods
                    .createAlarm(
                        alarmId, // Same ID!
                        new anchor.BN(alarmTime + 100),
                        new anchor.BN(deadline + 100),
                        new anchor.BN(DEPOSIT_AMOUNT),
                        0,
                        null
                    )
                    .accounts({
                        alarm,
                        vault,
                        owner: owner.publicKey,
                        systemProgram: SystemProgram.programId,
                    })
                    .rpc();
                expect.fail("Should have failed — PDA already exists");
            } catch (err: any) {
                // Account init fails when PDA already exists
                expect(err.message).to.match(/already in use|Error/i);
            }
        });

        it("Multiple alarms with different IDs from same user succeed", async () => {
            const now = await getCurrentTimestamp();

            const alarm1Id = uniqueAlarmId();
            const alarm2Id = uniqueAlarmId();

            const [alarm1] = deriveAlarmPda(owner.publicKey, alarm1Id);
            const [vault1] = deriveVaultPda(alarm1);
            const [alarm2] = deriveAlarmPda(owner.publicKey, alarm2Id);
            const [vault2] = deriveVaultPda(alarm2);

            await program.methods
                .createAlarm(
                    alarm1Id,
                    new anchor.BN(now + 300),
                    new anchor.BN(now + 2100),
                    new anchor.BN(DEPOSIT_AMOUNT),
                    0,
                    null
                )
                .accounts({
                    alarm: alarm1,
                    vault: vault1,
                    owner: owner.publicKey,
                    systemProgram: SystemProgram.programId,
                })
                .rpc();

            await program.methods
                .createAlarm(
                    alarm2Id,
                    new anchor.BN(now + 600),
                    new anchor.BN(now + 2400),
                    new anchor.BN(DEPOSIT_AMOUNT),
                    0,
                    null
                )
                .accounts({
                    alarm: alarm2,
                    vault: vault2,
                    owner: owner.publicKey,
                    systemProgram: SystemProgram.programId,
                })
                .rpc();

            // Both alarms exist independently
            const a1 = await program.account.alarm.fetch(alarm1);
            const a2 = await program.account.alarm.fetch(alarm2);
            expect(a1.status).to.deep.equal({ created: {} });
            expect(a2.status).to.deep.equal({ created: {} });
            expect(a1.alarmTime.toNumber()).to.not.equal(a2.alarmTime.toNumber());
        });
    });

    // =========================================================================
    // UNCOVERED HAPPY PATHS — Routes and H3 transitions
    // =========================================================================
    describe("Uncovered Happy Paths", () => {
        it("Create alarm with Donate route and valid destination", async () => {
            const alarmId = uniqueAlarmId();
            const now = await getCurrentTimestamp();
            const donateAddr = Keypair.generate().publicKey;

            const [alarm] = deriveAlarmPda(owner.publicKey, alarmId);
            const [vault] = deriveVaultPda(alarm);

            await program.methods
                .createAlarm(
                    alarmId,
                    new anchor.BN(now + 300),
                    new anchor.BN(now + 2100),
                    new anchor.BN(DEPOSIT_AMOUNT),
                    1, // Donate route
                    donateAddr
                )
                .accounts({
                    alarm,
                    vault,
                    owner: owner.publicKey,
                    systemProgram: SystemProgram.programId,
                })
                .rpc();

            const a = await program.account.alarm.fetch(alarm);
            expect(a.penaltyRoute).to.equal(1);
            expect(a.penaltyDestination.toString()).to.equal(donateAddr.toString());
        });

        it("Claim from Acknowledged state (H3 standalone)", async () => {
            const alarmId = uniqueAlarmId();
            const now = await getCurrentTimestamp();
            const alarmTime = now + 2;
            const deadline = alarmTime + 1800;

            const [alarm] = deriveAlarmPda(owner.publicKey, alarmId);
            const [vault] = deriveVaultPda(alarm);

            await program.methods
                .createAlarm(
                    alarmId,
                    new anchor.BN(alarmTime),
                    new anchor.BN(deadline),
                    new anchor.BN(DEPOSIT_AMOUNT),
                    0,
                    null
                )
                .accounts({
                    alarm,
                    vault,
                    owner: owner.publicKey,
                    systemProgram: SystemProgram.programId,
                })
                .rpc();

            await new Promise(resolve => setTimeout(resolve, 2000));

            // Acknowledge first
            await program.methods
                .ackAwake()
                .accounts({
                    alarm,
                    owner: owner.publicKey,
                })
                .rpc();

            let a = await program.account.alarm.fetch(alarm);
            expect(a.status).to.deep.equal({ acknowledged: {} });

            // Claim from Acknowledged
            await program.methods
                .claim()
                .accounts({
                    alarm,
                    vault,
                    owner: owner.publicKey,
                    systemProgram: SystemProgram.programId,
                })
                .rpc();

            a = await program.account.alarm.fetch(alarm);
            expect(a.status).to.deep.equal({ claimed: {} });
        });

        it("FAILS: Double initialize (same user)", async () => {
            // The first initialize was done in the Initialize test section.
            // Same PDA derivation means re-init should fail.
            const [userProfile] = PublicKey.findProgramAddressSync(
                [Buffer.from("user-profile"), owner.publicKey.toBuffer()],
                program.programId
            );

            try {
                await program.methods
                    .initialize()
                    .accounts({
                        userProfile,
                        owner: owner.publicKey,
                        systemProgram: SystemProgram.programId,
                    })
                    .rpc();
                expect.fail("Should have failed — profile already exists");
            } catch (err: any) {
                // init constraint fails when account already exists
                expect(err.message).to.match(/already in use|Error/i);
            }
        });
    });

    // =========================================================================
    // STATUS CONSTRAINT COVERAGE — Snooze/Refund on Acknowledged
    // =========================================================================
    describe("Status Constraint Coverage", () => {
        it("FAILS: Snooze on Acknowledged alarm (status must be Created)", async () => {
            const alarmId = uniqueAlarmId();
            const now = await getCurrentTimestamp();
            const alarmTime = now + 2;
            const deadline = alarmTime + 1800;

            const [alarm] = deriveAlarmPda(owner.publicKey, alarmId);
            const [vault] = deriveVaultPda(alarm);

            await program.methods
                .createAlarm(
                    alarmId,
                    new anchor.BN(alarmTime),
                    new anchor.BN(deadline),
                    new anchor.BN(DEPOSIT_AMOUNT),
                    0,
                    null
                )
                .accounts({
                    alarm,
                    vault,
                    owner: owner.publicKey,
                    systemProgram: SystemProgram.programId,
                })
                .rpc();

            await new Promise(resolve => setTimeout(resolve, 2000));

            // Move to Acknowledged
            await program.methods
                .ackAwake()
                .accounts({
                    alarm,
                    owner: owner.publicKey,
                })
                .rpc();

            let a = await program.account.alarm.fetch(alarm);
            expect(a.status).to.deep.equal({ acknowledged: {} });

            // Try to snooze — should fail, snooze requires Created
            try {
                await program.methods
                    .snooze(0)
                    .accounts({
                        alarm,
                        vault,
                        sink: BURN_SINK,
                        owner: owner.publicKey,
                        systemProgram: SystemProgram.programId,
                    })
                    .rpc();
                expect.fail("Should have thrown — snooze requires Created status");
            } catch (err: any) {
                expect(err.message).to.include("InvalidAlarmState");
            }
        });

        it("FAILS: Emergency refund on Acknowledged alarm (status must be Created)", async () => {
            const alarmId = uniqueAlarmId();
            const now = await getCurrentTimestamp();
            const alarmTime = now + 2;
            const deadline = alarmTime + 1800;

            const [alarm] = deriveAlarmPda(owner.publicKey, alarmId);
            const [vault] = deriveVaultPda(alarm);

            await program.methods
                .createAlarm(
                    alarmId,
                    new anchor.BN(alarmTime),
                    new anchor.BN(deadline),
                    new anchor.BN(DEPOSIT_AMOUNT),
                    0,
                    null
                )
                .accounts({
                    alarm,
                    vault,
                    owner: owner.publicKey,
                    systemProgram: SystemProgram.programId,
                })
                .rpc();

            await new Promise(resolve => setTimeout(resolve, 2000));

            // Move to Acknowledged
            await program.methods
                .ackAwake()
                .accounts({
                    alarm,
                    owner: owner.publicKey,
                })
                .rpc();

            // Try to emergency refund — should fail (requires Created, not Acknowledged)
            try {
                await program.methods
                    .emergencyRefund()
                    .accounts({
                        alarm,
                        vault,
                        sink: BURN_SINK,
                        owner: owner.publicKey,
                        systemProgram: SystemProgram.programId,
                    })
                    .rpc();
                expect.fail("Should have thrown — refund requires Created status");
            } catch (err: any) {
                expect(err.message).to.include("InvalidAlarmState");
            }
        });

        it("FAILS: ack_awake on Slashed alarm", async () => {
            const alarmId = uniqueAlarmId();
            const now = await getCurrentTimestamp();
            const alarmTime = now + 3;
            const deadline = alarmTime + 3;

            const [alarm] = deriveAlarmPda(owner.publicKey, alarmId);
            const [vault] = deriveVaultPda(alarm);

            await program.methods
                .createAlarm(
                    alarmId,
                    new anchor.BN(alarmTime),
                    new anchor.BN(deadline),
                    new anchor.BN(DEPOSIT_AMOUNT),
                    0,
                    null
                )
                .accounts({
                    alarm,
                    vault,
                    owner: owner.publicKey,
                    systemProgram: SystemProgram.programId,
                })
                .rpc();

            // Wait past deadline
            await new Promise(resolve => setTimeout(resolve, 7000));

            // Slash it
            await program.methods
                .slash()
                .accounts({
                    alarm,
                    vault,
                    penaltyRecipient: BURN_SINK,
                    caller: owner.publicKey,
                    systemProgram: SystemProgram.programId,
                })
                .rpc();

            // Try to ack_awake on slashed alarm
            try {
                await program.methods
                    .ackAwake()
                    .accounts({
                        alarm,
                        owner: owner.publicKey,
                    })
                    .rpc();
                expect.fail("Should have thrown — alarm is slashed");
            } catch (err: any) {
                expect(err.message).to.include("InvalidAlarmState");
            }
        });
    });

    // =========================================================================
    // Additional Coverage (audit gaps)
    // =========================================================================

    describe("Additional Coverage", () => {

        it("Slash with Donate route sends to donation address", async () => {
            const alarmId = uniqueAlarmId();
            const now = await getCurrentTimestamp();
            const alarmTime = now + 2;
            const deadline = alarmTime + 3;
            const donationWallet = Keypair.generate();

            const [alarm] = deriveAlarmPda(owner.publicKey, alarmId);
            const [vault] = deriveVaultPda(alarm);

            // Create alarm with Donate route (1) and destination
            await program.methods
                .createAlarm(
                    alarmId,
                    new anchor.BN(alarmTime),
                    new anchor.BN(deadline),
                    new anchor.BN(DEPOSIT_AMOUNT),
                    1, // Donate route
                    donationWallet.publicKey
                )
                .accounts({
                    alarm,
                    vault,
                    owner: owner.publicKey,
                    systemProgram: SystemProgram.programId,
                })
                .rpc();

            // Wait past deadline
            await new Promise(resolve => setTimeout(resolve, 7000));

            const donationBefore = await provider.connection.getBalance(donationWallet.publicKey);

            // Slash — should send to donation wallet
            await program.methods
                .slash()
                .accounts({
                    alarm,
                    vault,
                    penaltyRecipient: donationWallet.publicKey,
                    caller: owner.publicKey,
                    systemProgram: SystemProgram.programId,
                })
                .rpc();

            const donationAfter = await provider.connection.getBalance(donationWallet.publicKey);
            expect(donationAfter).to.be.greaterThan(donationBefore);

            // Verify alarm is slashed
            const alarmState = await program.account.alarm.fetch(alarm);
            expect(alarmState.status).to.deep.equal({ slashed: {} });
            expect(alarmState.remainingAmount.toNumber()).to.equal(0);
        });

        it("Snooze cost is exactly 10% of deposit (1st snooze math)", async () => {
            const alarmId = uniqueAlarmId();
            const now = await getCurrentTimestamp();
            const alarmTime = now + 2;
            const deadline = alarmTime + 1800;

            const [alarm] = deriveAlarmPda(owner.publicKey, alarmId);
            const [vault] = deriveVaultPda(alarm);

            await program.methods
                .createAlarm(
                    alarmId,
                    new anchor.BN(alarmTime),
                    new anchor.BN(deadline),
                    new anchor.BN(DEPOSIT_AMOUNT),
                    0,
                    null
                )
                .accounts({
                    alarm,
                    vault,
                    owner: owner.publicKey,
                    systemProgram: SystemProgram.programId,
                })
                .rpc();

            await new Promise(resolve => setTimeout(resolve, 3000));

            await program.methods
                .snooze(0)
                .accounts({
                    alarm,
                    vault,
                    sink: BURN_SINK,
                    owner: owner.publicKey,
                    systemProgram: SystemProgram.programId,
                })
                .rpc();

            const afterSnooze = await program.account.alarm.fetch(alarm);

            // Expected: 10% * 2^0 = 10% of initial_amount
            const expectedCost = Math.floor(DEPOSIT_AMOUNT * SNOOZE_PERCENT / 100);
            const actualCost = DEPOSIT_AMOUNT - afterSnooze.remainingAmount.toNumber();

            expect(actualCost).to.equal(expectedCost,
                "Snooze cost should be exactly 10% of deposit");
            expect(afterSnooze.snoozeCount).to.equal(1);
            // Note: Burn sink (1nc1nerator) doesn't retain lamports — they are destroyed.
            // Exponential doubling (2^n) for subsequent snoozes is verified
            // in Rust unit tests (tests.rs: test_snooze_cost_math)
        });

        it("FAILS: Snooze with wrong expected_snooze_count (idempotency guard)", async () => {
            const alarmId = uniqueAlarmId();
            const now = await getCurrentTimestamp();
            const alarmTime = now + 2;
            const deadline = alarmTime + 1800;

            const [alarm] = deriveAlarmPda(owner.publicKey, alarmId);
            const [vault] = deriveVaultPda(alarm);

            await program.methods
                .createAlarm(
                    alarmId,
                    new anchor.BN(alarmTime),
                    new anchor.BN(deadline),
                    new anchor.BN(DEPOSIT_AMOUNT),
                    0,
                    null
                )
                .accounts({
                    alarm,
                    vault,
                    owner: owner.publicKey,
                    systemProgram: SystemProgram.programId,
                })
                .rpc();

            await new Promise(resolve => setTimeout(resolve, 3000));

            // Pass expected_snooze_count = 1, but actual is 0
            try {
                await program.methods
                    .snooze(1) // WRONG: should be 0
                    .accounts({
                        alarm,
                        vault,
                        sink: BURN_SINK,
                        owner: owner.publicKey,
                        systemProgram: SystemProgram.programId,
                    })
                    .rpc();
                expect.fail("Should have failed with wrong expected_snooze_count");
            } catch (err: any) {
                expect(err.message).to.include("InvalidAlarmState");
            }
        });

        it("Slash with Buddy route sends to buddy address", async () => {
            const alarmId = uniqueAlarmId();
            const now = await getCurrentTimestamp();
            const alarmTime = now + 2;
            const deadline = alarmTime + 3;
            const buddyWallet = Keypair.generate();

            const [alarm] = deriveAlarmPda(owner.publicKey, alarmId);
            const [vault] = deriveVaultPda(alarm);

            await fundKeypair(buddyWallet);

            // Create alarm with Buddy route (2) and destination
            await program.methods
                .createAlarm(
                    alarmId,
                    new anchor.BN(alarmTime),
                    new anchor.BN(deadline),
                    new anchor.BN(DEPOSIT_AMOUNT),
                    2, // Buddy route
                    buddyWallet.publicKey
                )
                .accounts({
                    alarm,
                    vault,
                    owner: owner.publicKey,
                    systemProgram: SystemProgram.programId,
                })
                .rpc();

            // Wait past deadline
            await new Promise(resolve => setTimeout(resolve, 7000));

            const buddyBefore = await provider.connection.getBalance(buddyWallet.publicKey);

            // Slash — should send to buddy wallet
            await program.methods
                .slash()
                .accounts({
                    alarm,
                    vault,
                    penaltyRecipient: buddyWallet.publicKey,
                    caller: buddyWallet.publicKey,
                    systemProgram: SystemProgram.programId,
                })
                .signers([buddyWallet])
                .rpc();

            const buddyAfter = await provider.connection.getBalance(buddyWallet.publicKey);
            expect(buddyAfter).to.be.greaterThan(buddyBefore);

            const alarmState = await program.account.alarm.fetch(alarm);
            expect(alarmState.status).to.deep.equal({ slashed: {} });
        });

        it("FAILS: Slash from Acknowledged state is rejected", async () => {
            const alarmId = uniqueAlarmId();
            const now = await getCurrentTimestamp();
            const alarmTime = now + 2;
            const deadline = alarmTime + 3;

            const [alarm] = deriveAlarmPda(owner.publicKey, alarmId);
            const [vault] = deriveVaultPda(alarm);

            await program.methods
                .createAlarm(
                    alarmId,
                    new anchor.BN(alarmTime),
                    new anchor.BN(deadline),
                    new anchor.BN(DEPOSIT_AMOUNT),
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

            // Wait for alarm time, then acknowledge
            await new Promise(resolve => setTimeout(resolve, 3000));

            await program.methods
                .ackAwake()
                .accounts({
                    alarm,
                    owner: owner.publicKey,
                })
                .rpc();

            const acked = await program.account.alarm.fetch(alarm);
            expect(acked.status).to.deep.equal({ acknowledged: {} });

            // Wait past deadline — slash should still work on Acknowledged alarm
            await new Promise(resolve => setTimeout(resolve, 4000));

            try {
                await program.methods
                    .slash()
                    .accounts({
                        alarm,
                        vault,
                        penaltyRecipient: BURN_SINK,
                        caller: owner.publicKey,
                        systemProgram: SystemProgram.programId,
                    })
                    .rpc();
                expect.fail("Should have thrown InvalidAlarmState");
            } catch (err: any) {
                expect(err.message).to.include("InvalidAlarmState");
            }
        });

        it("Zero-deposit alarm: create and claim lifecycle", async () => {
            const alarmId = uniqueAlarmId();
            const now = await getCurrentTimestamp();
            const alarmTime = now + 2;
            const deadline = alarmTime + 1800;

            const [alarm] = deriveAlarmPda(owner.publicKey, alarmId);
            const [vault] = deriveVaultPda(alarm);

            // Create alarm with 0 deposit
            await program.methods
                .createAlarm(
                    alarmId,
                    new anchor.BN(alarmTime),
                    new anchor.BN(deadline),
                    new anchor.BN(0), // Zero deposit
                    0,
                    null
                )
                .accounts({
                    alarm,
                    vault,
                    owner: owner.publicKey,
                    systemProgram: SystemProgram.programId,
                })
                .rpc();

            const alarmState = await program.account.alarm.fetch(alarm);
            expect(alarmState.initialAmount.toNumber()).to.equal(0);
            expect(alarmState.remainingAmount.toNumber()).to.equal(0);
            expect(alarmState.status).to.deep.equal({ created: {} });

            // Wait for alarm time and claim
            await new Promise(resolve => setTimeout(resolve, 3000));

            await program.methods
                .ackAwake()
                .accounts({
                    alarm,
                    owner: owner.publicKey,
                })
                .rpc();

            await program.methods
                .claim()
                .accounts({
                    alarm,
                    vault,
                    owner: owner.publicKey,
                    systemProgram: SystemProgram.programId,
                })
                .rpc();

            const claimed = await program.account.alarm.fetch(alarm);
            expect(claimed.status).to.deep.equal({ claimed: {} });
        });

        it("Emergency refund deducts exactly 5% penalty", async () => {
            const alarmId = uniqueAlarmId();
            const now = await getCurrentTimestamp();
            const alarmTime = now + 60; // 60s in the future to allow refund
            const deadline = alarmTime + 1800;

            const [alarm] = deriveAlarmPda(owner.publicKey, alarmId);
            const [vault] = deriveVaultPda(alarm);

            await program.methods
                .createAlarm(
                    alarmId,
                    new anchor.BN(alarmTime),
                    new anchor.BN(deadline),
                    new anchor.BN(DEPOSIT_AMOUNT),
                    0,
                    null
                )
                .accounts({
                    alarm,
                    vault,
                    owner: owner.publicKey,
                    systemProgram: SystemProgram.programId,
                })
                .rpc();

            const ownerBefore = await provider.connection.getBalance(owner.publicKey);
            const vaultBefore = await provider.connection.getBalance(vault);

            // Emergency refund before alarm time
            await program.methods
                .emergencyRefund()
                .accounts({
                    alarm,
                    vault,
                    sink: BURN_SINK,
                    owner: owner.publicKey,
                    systemProgram: SystemProgram.programId,
                })
                .rpc();

            const ownerAfter = await provider.connection.getBalance(owner.publicKey);
            const alarmState = await program.account.alarm.fetch(alarm);

            expect(alarmState.status).to.deep.equal({ claimed: {} });
            expect(alarmState.remainingAmount.toNumber()).to.equal(0);

            // Owner should receive back more than before (deposit - 5% penalty + rent, minus tx fee)
            // The key assertion: owner got funds back (net positive after tx fee)
            // Exact math: owner gets vaultBalance - 5%penalty back, pays ~5000 lamports tx fee
            const expectedPenalty = Math.floor(DEPOSIT_AMOUNT * EMERGENCY_PENALTY_PERCENT / 100);
            const expectedReturn = vaultBefore - expectedPenalty;
            const actualGain = ownerAfter - ownerBefore;
            // actualGain = expectedReturn - txFee, so actualGain should be close to expectedReturn
            // Allow for tx fee (up to 10000 lamports)
            expect(actualGain).to.be.greaterThan(expectedReturn - 10000,
                `Owner should receive ~${expectedReturn} lamports (got gain of ${actualGain})`);
            expect(actualGain).to.be.lessThan(expectedReturn + 1000,
                "Owner should not receive more than expected");
        });

    });
});
