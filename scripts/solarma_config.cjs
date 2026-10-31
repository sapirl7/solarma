#!/usr/bin/env node
/**
 * Manage the Solarma Vault global config (pause switch, deposit cap).
 *
 * Loads Anchor from programs/solarma_vault/node_modules (run `npm ci` there first)
 * and the IDL from `anchor build` output.
 *
 * Usage:
 *   # Once, right after deploying and BEFORE moving the upgrade authority to a multisig.
 *   # Signed by the current upgrade authority (the deployer keypair).
 *   node scripts/solarma_config.cjs init --rpc <url> --keypair <path> --admin <pubkey> --max-sol <n>
 *
 *   # Show the current config
 *   node scripts/solarma_config.cjs show --rpc <url>
 *
 *   # Build an update_config instruction for the admin (e.g. a Squads vault) to sign.
 *   # Prints program id, accounts and base58 data to paste into the multisig's
 *   # transaction builder. Omit --new-admin to keep the admin.
 *   node scripts/solarma_config.cjs print-update --rpc <url> --admin <pubkey> \
 *     --paused <true|false> --max-sol <n> [--new-admin <pubkey>]
 */

const fs = require("fs");
const os = require("os");
const path = require("path");
const { createRequire } = require("module");

const requireFromProgram = createRequire(
  path.resolve(__dirname, "../programs/solarma_vault/package.json")
);
const anchor = requireFromProgram("@coral-xyz/anchor");
const { Connection, Keypair, PublicKey, LAMPORTS_PER_SOL } = requireFromProgram("@solana/web3.js");
const bs58 = requireFromProgram("bs58");

const UPGRADEABLE_LOADER = new PublicKey("BPFLoaderUpgradeab1e11111111111111111111111");

function parseArgs(argv) {
  const args = { _: [] };
  for (let i = 0; i < argv.length; i++) {
    if (argv[i].startsWith("--")) {
      args[argv[i].slice(2)] = argv[i + 1];
      i++;
    } else {
      args._.push(argv[i]);
    }
  }
  return args;
}

function required(args, name) {
  if (args[name] === undefined) {
    console.error(`Missing --${name}`);
    process.exit(1);
  }
  return args[name];
}

function loadIdl() {
  const candidates = [
    path.resolve(__dirname, "../programs/target/idl/solarma_vault.json"),
    path.resolve(__dirname, "../programs/solarma_vault/target/idl/solarma_vault.json"),
  ];
  const found = candidates.find((p) => fs.existsSync(p));
  if (!found) {
    console.error("IDL not found; run `anchor build` in programs/solarma_vault first.");
    process.exit(1);
  }
  return JSON.parse(fs.readFileSync(found, "utf8"));
}

function loadKeypair(file) {
  const resolved = file.replace(/^~(?=$|\/)/, os.homedir());
  return Keypair.fromSecretKey(Uint8Array.from(JSON.parse(fs.readFileSync(resolved, "utf8"))));
}

function lamportsFromSol(sol) {
  const value = Number(sol);
  if (!(value > 0)) {
    console.error("--max-sol must be a positive number");
    process.exit(1);
  }
  return new anchor.BN(Math.round(value * LAMPORTS_PER_SOL));
}

function programFor(rpc, keypair) {
  const connection = new Connection(rpc, "confirmed");
  const wallet = new anchor.Wallet(keypair || Keypair.generate());
  const provider = new anchor.AnchorProvider(connection, wallet, { commitment: "confirmed" });
  return new anchor.Program(loadIdl(), provider);
}

function configPda(programId) {
  return PublicKey.findProgramAddressSync([Buffer.from("config")], programId)[0];
}

async function main() {
  const args = parseArgs(process.argv.slice(2));
  const command = args._[0];
  const rpc = required(args, "rpc");

  if (command === "init") {
    const authority = loadKeypair(required(args, "keypair"));
    const program = programFor(rpc, authority);
    const admin = new PublicKey(required(args, "admin"));
    const max = lamportsFromSol(required(args, "max-sol"));
    const [programData] = PublicKey.findProgramAddressSync([program.programId.toBuffer()], UPGRADEABLE_LOADER);

    const signature = await program.methods
      .initializeConfig(admin, max)
      .accountsPartial({ authority: authority.publicKey, programData })
      .rpc();
    console.log(`Config initialized: ${configPda(program.programId).toBase58()}`);
    console.log(`  admin=${admin.toBase58()} max=${max.toString()} lamports`);
    console.log(`  tx=${signature}`);
  } else if (command === "show") {
    const program = programFor(rpc, null);
    const config = await program.account.config.fetch(configPda(program.programId));
    console.log(
      JSON.stringify(
        {
          address: configPda(program.programId).toBase58(),
          admin: config.admin.toBase58(),
          paused: config.paused,
          maxDepositLamports: config.maxDepositLamports.toString(),
        },
        null,
        2
      )
    );
  } else if (command === "print-update") {
    const program = programFor(rpc, null);
    const admin = new PublicKey(required(args, "admin"));
    const paused = required(args, "paused") === "true";
    const max = lamportsFromSol(required(args, "max-sol"));
    const newAdmin = args["new-admin"] ? new PublicKey(args["new-admin"]) : null;

    const ix = await program.methods
      .updateConfig(paused, max, newAdmin)
      .accountsPartial({ config: configPda(program.programId), admin })
      .instruction();
    console.log(
      JSON.stringify(
        {
          programId: ix.programId.toBase58(),
          accounts: ix.keys.map((k) => ({
            pubkey: k.pubkey.toBase58(),
            isSigner: k.isSigner,
            isWritable: k.isWritable,
          })),
          dataBase58: bs58.encode(ix.data),
        },
        null,
        2
      )
    );
  } else {
    console.error("Usage: solarma_config.cjs <init|show|print-update> --rpc <url> ... (see file header)");
    process.exit(1);
  }
}

main().catch((err) => {
  console.error(err);
  process.exit(1);
});
