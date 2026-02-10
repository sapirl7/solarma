package app.solarma.wallet

import org.sol4k.PublicKey
import java.nio.ByteBuffer
import java.nio.ByteOrder

object Ed25519InstructionBuilder {
    val ED25519_PROGRAM_ID = PublicKey("Ed25519SigVerify111111111111111111111111111")

    private const val PUBKEY_SIZE = 32
    private const val SIGNATURE_SIZE = 64

    // Matches @solana/web3.js Ed25519Program.createInstructionWithPublicKey layout (1 signature).
    private const val HEADER_SIZE = 16
    private const val PUBKEY_OFFSET = HEADER_SIZE
    private const val SIGNATURE_OFFSET = PUBKEY_OFFSET + PUBKEY_SIZE
    private const val MESSAGE_OFFSET = SIGNATURE_OFFSET + SIGNATURE_SIZE

    fun buildVerify(
        publicKeyBytes: ByteArray,
        signatureBytes: ByteArray,
        messageBytes: ByteArray
    ): SolarmaInstruction {
        require(publicKeyBytes.size == PUBKEY_SIZE) { "publicKey must be 32 bytes" }
        require(signatureBytes.size == SIGNATURE_SIZE) { "signature must be 64 bytes" }
        require(messageBytes.size <= 0xFFFF) { "message too large" }

        val totalSize = MESSAGE_OFFSET + messageBytes.size
        val buffer = ByteBuffer.allocate(totalSize).order(ByteOrder.LITTLE_ENDIAN)

        buffer.put(1.toByte()) // num_signatures
        buffer.put(0.toByte()) // padding

        buffer.putShort(SIGNATURE_OFFSET.toShort())
        buffer.putShort(0xFFFF.toShort()) // signature_instruction_index
        buffer.putShort(PUBKEY_OFFSET.toShort())
        buffer.putShort(0xFFFF.toShort()) // public_key_instruction_index
        buffer.putShort(MESSAGE_OFFSET.toShort())
        buffer.putShort(messageBytes.size.toShort()) // message_data_size
        buffer.putShort(0xFFFF.toShort()) // message_instruction_index

        buffer.put(publicKeyBytes)
        buffer.put(signatureBytes)
        buffer.put(messageBytes)

        return SolarmaInstruction(ED25519_PROGRAM_ID, accounts = emptyList(), data = buffer.array())
    }
}

