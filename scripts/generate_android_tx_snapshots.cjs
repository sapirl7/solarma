#!/usr/bin/env node
/**
 * Generate golden snapshots for Android "transaction snapshot tests".
 *
 * This script intentionally lives at repo root but loads Solana deps from
 * `programs/solarma_vault/node_modules` to avoid duplicating dependencies.
 *
 * Output:
 *   apps/android/app/src/test/resources/tx_snapshots/solarma_vault.json
 */

const fs = require("fs");
const path = require("path");
const crypto = require("crypto");
const { createRequire } = require("module");

const requireFromProgram = createRequire(
  path.resolve(__dirname, "../programs/solarma_vault/package.json")
);
const { PublicKey } = requireFromProgram("@solana/web3.js");
const bs58 = requireFromProgram("bs58");

const PROGRAM_ID = new PublicKey("F54LpWS97bCvkn5PGfUsFi8cU8HyYBZgyozkSkAbAjzP");
const SYSTEM_PROGRAM_ID = new PublicKey("11111111111111111111111111111111");
const BURN_SINK = new PublicKey("1nc1nerator11111111111111111111111111111111");

function hex(buf) {
  return Buffer.from(buf).toString("hex");
}

function discriminator(ixName) {
  return crypto
    .createHash("sha256")
    .update(`global:${ixName}`)
    .digest()
    .subarray(0, 8);
}

function u64le(n) {
  const b = Buffer.alloc(8);
  b.writeBigUInt64LE(BigInt(n));
  return b;
}

function i64le(n) {
  const b = Buffer.alloc(8);
  b.writeBigInt64LE(BigInt(n));
  return b;
}

function optionPubkey(pk) {
  if (!pk) return Buffer.from([0]);
  return Buffer.concat([Buffer.from([1]), pk.toBuffer()]);
}

function alarmIdToLeBytes(alarmId) {
  // Anchor seed uses u64 little-endian.
  return u64le(alarmId);
}

function deriveAlarmPda(owner, alarmId) {
  const [pda, bump] = PublicKey.findProgramAddressSync(
    [Buffer.from("alarm"), owner.toBuffer(), alarmIdToLeBytes(alarmId)],
    PROGRAM_ID
  );
  return { pda, bump };
}

function deriveVaultPda(alarmPda) {
  const [pda, bump] = PublicKey.findProgramAddressSync(
    [Buffer.from("vault"), alarmPda.toBuffer()],
    PROGRAM_ID
  );
  return { pda, bump };
}

function writeCompactU16(value) {
  // Matches Kotlin writeCompactU16 implementation (shortvec).
  let rem = value >>> 0;
  const out = [];
  while (true) {
    let byte = rem & 0x7f;
    rem >>>= 7;
    if (rem === 0) {
      out.push(byte);
      break;
    }
    out.push(byte | 0x80);
  }
  return Buffer.from(out);
}

function buildSortedAccountMetas(feePayer, instruction) {
  const map = new Map(); // base58 -> {pubkey, isSigner, isWritable}
  map.set(feePayer.toBase58(), {
    pubkey: feePayer,
    isSigner: true,
    isWritable: true,
  });

  for (const meta of instruction.accounts) {
    const key = meta.pubkey.toBase58();
    const existing = map.get(key);
    if (existing) {
      map.set(key, {
        pubkey: existing.pubkey,
        isSigner: existing.isSigner || meta.isSigner,
        isWritable: existing.isWritable || meta.isWritable,
      });
    } else {
      map.set(key, { ...meta });
    }
  }

  const programKey = instruction.programId.toBase58();
  if (!map.has(programKey)) {
    map.set(programKey, {
      pubkey: instruction.programId,
      isSigner: false,
      isWritable: false,
    });
  }

  const values = Array.from(map.values());
  values.sort((a, b) => {
    // Kotlin: compareBy({ !isSigner }, { !isWritable }, { pubkey.toBase58() })
    const aSigner = a.isSigner ? 0 : 1;
    const bSigner = b.isSigner ? 0 : 1;
    if (aSigner !== bSigner) return aSigner - bSigner;

    const aWritable = a.isWritable ? 0 : 1;
    const bWritable = b.isWritable ? 0 : 1;
    if (aWritable !== bWritable) return aWritable - bWritable;

    const aKey = a.pubkey.toBase58();
    const bKey = b.pubkey.toBase58();
    if (aKey < bKey) return -1;
    if (aKey > bKey) return 1;
    return 0;
  });
  return values;
}

function buildMessage(blockhashBase58, sortedAccounts, instruction) {
  let numSigners = 0;
  let numReadOnlySigners = 0;
  let numReadOnlyNonSigners = 0;
  for (const acc of sortedAccounts) {
    if (acc.isSigner) {
      numSigners++;
      if (!acc.isWritable) numReadOnlySigners++;
    } else {
      if (!acc.isWritable) numReadOnlyNonSigners++;
    }
  }

  const accountKeys = sortedAccounts.map((a) => a.pubkey);
  const programIndex = accountKeys.findIndex(
    (k) => k.toBase58() === instruction.programId.toBase58()
  );
  if (programIndex < 0) throw new Error("program id missing from account keys");

  const compiledAccountIndices = instruction.accounts.map((meta) => {
    const idx = accountKeys.findIndex((k) => k.toBase58() === meta.pubkey.toBase58());
    if (idx < 0) throw new Error("instruction account missing from account keys");
    return idx;
  });

  const pieces = [];
  pieces.push(Buffer.from([numSigners, numReadOnlySigners, numReadOnlyNonSigners]));
  pieces.push(writeCompactU16(accountKeys.length));
  for (const k of accountKeys) pieces.push(k.toBuffer());

  const blockhashBytes = new PublicKey(blockhashBase58).toBuffer();
  pieces.push(blockhashBytes);

  pieces.push(writeCompactU16(1)); // 1 instruction
  pieces.push(Buffer.from([programIndex]));

  pieces.push(writeCompactU16(compiledAccountIndices.length));
  pieces.push(Buffer.from(compiledAccountIndices));

  pieces.push(writeCompactU16(instruction.data.length));
  pieces.push(Buffer.from(instruction.data));

  return Buffer.concat(pieces);
}

function buildUnsignedTransaction(message) {
  return Buffer.concat([Buffer.from([0x01]), Buffer.alloc(64, 0), message]);
}

function instr(programId, accounts, data) {
  return { programId, accounts, data };
}

function meta(pubkey, isSigner, isWritable) {
  return { pubkey, isSigner, isWritable };
}

function buildCreateAlarmIx({
  owner,
  alarmId,
  alarmTime,
  deadline,
  depositLamports,
  penaltyRoute,
  penaltyDestination,
}) {
  const { pda: alarmPda } = deriveAlarmPda(owner, alarmId);
  const { pda: vaultPda } = deriveVaultPda(alarmPda);

  const data = Buffer.concat([
    discriminator("create_alarm"),
    u64le(alarmId),
    i64le(alarmTime),
    i64le(deadline),
    u64le(depositLamports),
    Buffer.from([penaltyRoute & 0xff]),
    optionPubkey(penaltyDestination),
  ]);

  const accounts = [
    meta(alarmPda, false, true),
    meta(vaultPda, false, true),
    meta(owner, true, true),
    meta(SYSTEM_PROGRAM_ID, false, false),
  ];

  return { alarmPda, vaultPda, instruction: instr(PROGRAM_ID, accounts, data) };
}

function buildClaimIx({ owner, alarmId }) {
  const { pda: alarmPda } = deriveAlarmPda(owner, alarmId);
  const { pda: vaultPda } = deriveVaultPda(alarmPda);
  const data = Buffer.from(discriminator("claim"));

  const accounts = [
    meta(alarmPda, false, true),
    meta(vaultPda, false, true),
    meta(owner, true, true),
    meta(SYSTEM_PROGRAM_ID, false, false),
  ];

  return { alarmPda, vaultPda, instruction: instr(PROGRAM_ID, accounts, data) };
}

function buildAckAwakeIx({ owner, alarmId }) {
  const { pda: alarmPda } = deriveAlarmPda(owner, alarmId);
  const data = Buffer.from(discriminator("ack_awake"));

  const accounts = [
    meta(alarmPda, false, true),
    meta(owner, true, true),
  ];

  return { alarmPda, instruction: instr(PROGRAM_ID, accounts, data) };
}

function buildSnoozeIx({ owner, alarmId, expectedSnoozeCount }) {
  const { pda: alarmPda } = deriveAlarmPda(owner, alarmId);
  const { pda: vaultPda } = deriveVaultPda(alarmPda);
  const data = Buffer.concat([
    discriminator("snooze"),
    Buffer.from([expectedSnoozeCount & 0xff]),
  ]);

  const accounts = [
    meta(alarmPda, false, true),
    meta(vaultPda, false, true),
    meta(BURN_SINK, false, true),
    meta(owner, true, true),
    meta(SYSTEM_PROGRAM_ID, false, false),
  ];

  return { alarmPda, vaultPda, instruction: instr(PROGRAM_ID, accounts, data) };
}

function buildEmergencyRefundIx({ owner, alarmId }) {
  const { pda: alarmPda } = deriveAlarmPda(owner, alarmId);
  const { pda: vaultPda } = deriveVaultPda(alarmPda);
  const data = Buffer.from(discriminator("emergency_refund"));

  const accounts = [
    meta(alarmPda, false, true),
    meta(vaultPda, false, true),
    meta(BURN_SINK, false, true),
    meta(owner, true, true),
    meta(SYSTEM_PROGRAM_ID, false, false),
  ];

  return { alarmPda, vaultPda, instruction: instr(PROGRAM_ID, accounts, data) };
}

function buildSlashIx({ caller, alarmId, penaltyRecipient }) {
  const { pda: alarmPda } = deriveAlarmPda(caller, alarmId);
  const { pda: vaultPda } = deriveVaultPda(alarmPda);
  const data = Buffer.from(discriminator("slash"));

  const accounts = [
    meta(alarmPda, false, true),
    meta(vaultPda, false, true),
    meta(penaltyRecipient, false, true),
    meta(caller, true, true),
    meta(SYSTEM_PROGRAM_ID, false, false),
  ];

  return { alarmPda, vaultPda, instruction: instr(PROGRAM_ID, accounts, data) };
}

function toSnapshotCase(name, feePayer, blockhash, inputs, buildFn) {
  const built = buildFn();
  const sortedAccounts = buildSortedAccountMetas(feePayer, built.instruction);
  const message = buildMessage(blockhash, sortedAccounts, built.instruction);
  const tx = buildUnsignedTransaction(message);

  const snapshot = {
    name,
    inputs,
    derived: {},
    instruction: {
      programId: built.instruction.programId.toBase58(),
      accounts: built.instruction.accounts.map((a) => ({
        pubkey: a.pubkey.toBase58(),
        isSigner: a.isSigner,
        isWritable: a.isWritable,
      })),
      dataHex: hex(built.instruction.data),
    },
    txHex: hex(tx),
  };

  if (built.alarmPda) snapshot.derived.alarmPda = built.alarmPda.toBase58();
  if (built.vaultPda) snapshot.derived.vaultPda = built.vaultPda.toBase58();

  return snapshot;
}

function main() {
  // Deterministic test keys (avoid collisions with well-known program ids).
  const owner = new PublicKey(Buffer.alloc(32, 9));
  const buddy = new PublicKey(Buffer.alloc(32, 7));

  // Deterministic 32-byte blockhash (base58) used by Android snapshot tests.
  const blockhashBytes = Buffer.alloc(32, 2);
  const blockhash = bs58.encode(blockhashBytes);

  const alarmId = 42;
  const alarmTime = 1_700_000_000; // fixed unix timestamp
  const deadline = alarmTime + 1_800; // + 30 minutes
  const depositLamports = 100_000_000; // 0.1 SOL

  const cases = [];
  cases.push(
    toSnapshotCase(
      "create_alarm_burn",
      owner,
      blockhash,
      {
        op: "create_alarm",
        owner: owner.toBase58(),
        alarmId,
        alarmTime,
        deadline,
        depositLamports,
        penaltyRoute: 0,
        penaltyDestination: null,
      },
      () =>
        buildCreateAlarmIx({
          owner,
          alarmId,
          alarmTime,
          deadline,
          depositLamports,
          penaltyRoute: 0,
          penaltyDestination: null,
        })
    )
  );
  cases.push(
    toSnapshotCase(
      "create_alarm_buddy",
      owner,
      blockhash,
      {
        op: "create_alarm",
        owner: owner.toBase58(),
        alarmId: alarmId + 1,
        alarmTime,
        deadline,
        depositLamports,
        penaltyRoute: 2,
        penaltyDestination: buddy.toBase58(),
      },
      () =>
        buildCreateAlarmIx({
          owner,
          alarmId: alarmId + 1,
          alarmTime,
          deadline,
          depositLamports,
          penaltyRoute: 2,
          penaltyDestination: buddy,
        })
    )
  );
  cases.push(
    toSnapshotCase(
      "ack_awake",
      owner,
      blockhash,
      {
        op: "ack_awake",
        owner: owner.toBase58(),
        alarmId,
      },
      () => buildAckAwakeIx({ owner, alarmId })
    )
  );
  cases.push(
    toSnapshotCase(
      "snooze",
      owner,
      blockhash,
      {
        op: "snooze",
        owner: owner.toBase58(),
        alarmId,
        expectedSnoozeCount: 0,
      },
      () => buildSnoozeIx({ owner, alarmId, expectedSnoozeCount: 0 })
    )
  );
  cases.push(
    toSnapshotCase(
      "claim",
      owner,
      blockhash,
      {
        op: "claim",
        owner: owner.toBase58(),
        alarmId,
      },
      () => buildClaimIx({ owner, alarmId })
    )
  );
  cases.push(
    toSnapshotCase(
      "emergency_refund",
      owner,
      blockhash,
      {
        op: "emergency_refund",
        owner: owner.toBase58(),
        alarmId,
      },
      () => buildEmergencyRefundIx({ owner, alarmId })
    )
  );
  cases.push(
    toSnapshotCase(
      "slash_burn",
      owner,
      blockhash,
      {
        op: "slash",
        caller: owner.toBase58(),
        alarmId,
        penaltyRecipient: BURN_SINK.toBase58(),
      },
      () => buildSlashIx({ caller: owner, alarmId, penaltyRecipient: BURN_SINK })
    )
  );

  const out = {
    programId: PROGRAM_ID.toBase58(),
    systemProgramId: SYSTEM_PROGRAM_ID.toBase58(),
    burnSink: BURN_SINK.toBase58(),
    blockhash,
    fixedKeys: {
      owner: owner.toBase58(),
      buddy: buddy.toBase58(),
    },
    constants: {
      discriminators: {
        create_alarm: hex(discriminator("create_alarm")),
        claim: hex(discriminator("claim")),
        snooze: hex(discriminator("snooze")),
        slash: hex(discriminator("slash")),
        emergency_refund: hex(discriminator("emergency_refund")),
        ack_awake: hex(discriminator("ack_awake")),
      },
    },
    cases,
  };

  const outDir = path.resolve(
    __dirname,
    "../apps/android/app/src/test/resources/tx_snapshots"
  );
  fs.mkdirSync(outDir, { recursive: true });
  const outFile = path.join(outDir, "solarma_vault.json");
  fs.writeFileSync(outFile, JSON.stringify(out, null, 2) + "\n");

  console.log(`Wrote ${outFile}`);
  console.log(`Blockhash: ${blockhash}`);
}

main();
