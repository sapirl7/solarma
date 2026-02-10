#!/usr/bin/env node
/**
 * Lightweight program observability without servers.
 *
 * Fetches recent transactions for a program id and parses Anchor events from logs.
 *
 * Usage:
 *   node scripts/monitor_program_events.cjs --limit 200
 *
 * Env:
 *   SOLANA_RPC_URL   (default: https://api.devnet.solana.com)
 *   PROGRAM_ID       (default: devnet id in programs/solarma_vault/src/lib.rs)
 */

const fs = require("fs");
const path = require("path");
const { createRequire } = require("module");

const requireFromProgram = createRequire(
  path.resolve(__dirname, "../programs/solarma_vault/package.json")
);

const anchor = requireFromProgram("@coral-xyz/anchor");
const { Connection, PublicKey } = requireFromProgram("@solana/web3.js");

function parseArgs(argv) {
  const out = { limit: 200 };
  for (let i = 0; i < argv.length; i++) {
    const a = argv[i];
    if (a === "--limit") out.limit = Number(argv[++i] || "200");
  }
  return out;
}

function inc(map, key, by = 1) {
  map[key] = (map[key] || 0) + by;
}

async function main() {
  const args = parseArgs(process.argv.slice(2));

  const rpcUrl = process.env.SOLANA_RPC_URL || "https://api.devnet.solana.com";
  const programId = new PublicKey(
    process.env.PROGRAM_ID || "F54LpWS97bCvkn5PGfUsFi8cU8HyYBZgyozkSkAbAjzP"
  );

  const idlPath = path.resolve(
    __dirname,
    "../programs/solarma_vault/target/idl/solarma_vault.json"
  );
  const idl = JSON.parse(fs.readFileSync(idlPath, "utf8"));

  const coder = new anchor.BorshCoder(idl);
  const parser = new anchor.EventParser(programId, coder);

  const conn = new Connection(rpcUrl, "confirmed");

  const sigInfos = await conn.getSignaturesForAddress(programId, {
    limit: args.limit,
  });

  const eventCounts = {};
  const txErrCounts = {};

  for (const s of sigInfos) {
    const tx = await conn.getTransaction(s.signature, {
      commitment: "confirmed",
      maxSupportedTransactionVersion: 0,
    });
    if (!tx || !tx.meta || !tx.meta.logMessages) continue;

    if (tx.meta.err) {
      const key = JSON.stringify(tx.meta.err);
      inc(txErrCounts, key);
    }

    try {
      parser.parseLogs(tx.meta.logMessages, (ev) => {
        inc(eventCounts, ev.name);
      });
    } catch (e) {
      inc(eventCounts, "_event_parse_error");
    }
  }

  const total = sigInfos.length;
  console.log(`RPC: ${rpcUrl}`);
  console.log(`Program: ${programId.toBase58()}`);
  console.log(`Scanned: ${total} txs`);
  console.log("");

  const keys = Object.keys(eventCounts).sort();
  console.log("Event counts:");
  for (const k of keys) {
    console.log(`  ${k}: ${eventCounts[k]}`);
  }

  const errKeys = Object.keys(txErrCounts).sort((a, b) => txErrCounts[b] - txErrCounts[a]);
  if (errKeys.length) {
    console.log("");
    console.log("Top tx errors:");
    for (const k of errKeys.slice(0, 10)) {
      console.log(`  ${txErrCounts[k]}x ${k}`);
    }
  }
}

main().catch((e) => {
  console.error(e);
  process.exit(1);
});

