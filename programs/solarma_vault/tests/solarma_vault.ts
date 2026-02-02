import * as anchor from "@coral-xyz/anchor";
import { Program } from "@coral-xyz/anchor";
import { SolarmaVault } from "../target/types/solarma_vault";
import { expect } from "chai";
import { SystemProgram, Keypair, LAMPORTS_PER_SOL, PublicKey } from "@solana/web3.js";

describe("solarma_vault", () => {
    const provider = anchor.AnchorProvider.env();
    anchor.setProvider(provider);

    const program = anchor.workspace.SolarmaVault as Program<SolarmaVault>;
    const owner = provider.wallet;

    // Burn sink address (must match constants.rs)
    const BURN_SINK = new PublicKey("1nc1nerator11111111111111111111111111111111");

    // Test constants
    const MIN_DEPOSIT = 1_000_000; // 0.001 SOL in lamports
    const DEPOSIT_AMOUNT = 0.1 * LAMPORTS_PER_SOL;
    const EMERGENCY_PENALTY_PERCENT = 5;
    const SNOOZE_PERCENT = 10;

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

    // =========================================================================
    // INITIALIZE
    // =========================================================================
    describe("Initialize", () => {
        it("Creates a user profile", async () => {
            const [userProfile] = PublicKey.findProgramAddressSync(
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

    // =========================================================================
    // CREATE ALARM
    // =========================================================================
    describe("Create Alarm", () => {
        it("Creates an alarm without deposit", async () => {
            const alarmId = new anchor.BN(Date.now());
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
            const alarmId = new anchor.BN(Date.now() + 1);
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
            const alarmId = new anchor.BN(Date.now() + 2);
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
            const alarmId = new anchor.BN(Date.now() + 3);
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
                expect.fail("Should have thrown BuddyAddressRequired error");
            } catch (err: any) {
                expect(err.message).to.include("BuddyAddressRequired");
            }
        });

        it("FAILS: Creates alarm with deposit below minimum", async () => {
            const alarmId = new anchor.BN(Date.now() + 4);
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
            const alarmId = new anchor.BN(Date.now() + 5);
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
    });

    // =========================================================================
    // CLAIM
    // =========================================================================
    describe("Claim", () => {
        it("Claims deposit after alarm_time but before deadline", async () => {
            const alarmId = new anchor.BN(Date.now() + 100);
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

        it("FAILS: Claim before alarm_time", async () => {
            const alarmId = new anchor.BN(Date.now() + 101);
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
                expect.fail("Should have thrown TooEarly error");
            } catch (err: any) {
                expect(err.message).to.include("TooEarly");
            }
        });
    });

    // =========================================================================
    // EMERGENCY REFUND
    // =========================================================================
    describe("Emergency Refund", () => {
        it("Refunds with 5% penalty before alarm_time", async () => {
            const alarmId = new anchor.BN(Date.now() + 200);
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
            const alarmId = new anchor.BN(Date.now() + 201);
            const now = await getCurrentTimestamp();
            const alarmTime = now + 2; // 2 seconds from now
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
            const alarmId = new anchor.BN(Date.now() + 202);
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
            const alarmId = new anchor.BN(Date.now() + 300);
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
                .snooze()
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

        it("Snooze cost doubles each time", async () => {
            const alarmId = new anchor.BN(Date.now() + 301);
            const now = await getCurrentTimestamp();
            const alarmTime = now + 5; // Longer wait to ensure timing works
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

            await new Promise(resolve => setTimeout(resolve, 3000));

            // First snooze: 10%
            await program.methods
                .snooze()
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

            // Second snooze: 10% * 2 = 20% of remaining
            await program.methods
                .snooze()
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
            const alarmId = new anchor.BN(Date.now() + 302);
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
                    .snooze()
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
            const alarmId = new anchor.BN(Date.now() + 400);
            const now = await getCurrentTimestamp();
            const alarmTime = now + 3; // Future time requirement
            const deadline = alarmTime + 5; // Short deadline but valid

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
            await new Promise(resolve => setTimeout(resolve, 10000));

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
            const alarmId = new anchor.BN(Date.now() + 401);
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
            const alarmId = new anchor.BN(Date.now() + 402);
            const now = await getCurrentTimestamp();
            const alarmTime = now + 3;
            const deadline = alarmTime + 5;
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

            await new Promise(resolve => setTimeout(resolve, 4000));

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
                expect.fail("Should have thrown InvalidPenaltyRecipient error");
            } catch (err: any) {
                expect(err.message).to.include("InvalidPenaltyRecipient");
            }
        });

        // Skip on devnet due to airdrop limits
        it.skip("Slash with Buddy route sends to buddy", async () => {
            const alarmId = new anchor.BN(Date.now() + 403);
            const now = await getCurrentTimestamp();
            const alarmTime = now + 3;
            const deadline = alarmTime + 5;
            const buddy = Keypair.generate();

            // Fund buddy so account exists
            const sig = await provider.connection.requestAirdrop(buddy.publicKey, LAMPORTS_PER_SOL);
            await provider.connection.confirmTransaction(sig);

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

            await new Promise(resolve => setTimeout(resolve, 4000));

            const buddyBalanceBefore = await provider.connection.getBalance(buddy.publicKey);

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

            const buddyBalanceAfter = await provider.connection.getBalance(buddy.publicKey);
            expect(buddyBalanceAfter).to.be.greaterThan(buddyBalanceBefore);

            const alarmAccount = await program.account.alarm.fetch(alarm);
            expect(alarmAccount.status).to.deep.equal({ slashed: {} });
        });
    });

    // =========================================================================
    // STATE TRANSITIONS
    // =========================================================================
    describe("State Transitions", () => {
        it("FAILS: Claim on already claimed alarm", async () => {
            const alarmId = new anchor.BN(Date.now() + 500);
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
});
