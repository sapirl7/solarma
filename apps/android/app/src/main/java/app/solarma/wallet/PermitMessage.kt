package app.solarma.wallet

import org.sol4k.PublicKey

object PermitMessage {
    const val DOMAIN = "solarma"
    const val ACTION_ACK = "ack"
    const val DEFAULT_CLUSTER = "devnet"

    fun buildAck(
        cluster: String,
        programId: PublicKey,
        alarmPda: PublicKey,
        owner: PublicKey,
        nonce: Long,
        expTs: Long,
        proofType: Int,
        proofHash: ByteArray
    ): ByteArray {
        require(proofHash.size == 32) { "proofHash must be 32 bytes" }
        val proofHex = bytesToHexLower(proofHash)
        val s = listOf(
            DOMAIN,
            ACTION_ACK,
            cluster,
            programId.toBase58(),
            alarmPda.toBase58(),
            owner.toBase58(),
            nonce.toString(),
            expTs.toString(),
            proofType.toString(),
            proofHex
        ).joinToString("|")
        return s.toByteArray(Charsets.UTF_8)
    }
}

