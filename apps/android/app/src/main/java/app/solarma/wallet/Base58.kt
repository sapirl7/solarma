package app.solarma.wallet

import java.math.BigInteger

/**
 * Minimal Base58 (Bitcoin alphabet) encoder, used to derive a transaction's
 * signature string from the signed bytes before it is sent.
 */
internal object Base58 {
    private const val ALPHABET = "123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz"
    private val BASE = BigInteger.valueOf(58)

    fun encode(bytes: ByteArray): String {
        var num = BigInteger(1, bytes)
        val sb = StringBuilder()
        while (num > BigInteger.ZERO) {
            val (q, r) = num.divideAndRemainder(BASE)
            sb.append(ALPHABET[r.toInt()])
            num = q
        }
        for (b in bytes) {
            if (b.toInt() == 0) sb.append('1') else break
        }
        return sb.reverse().toString()
    }

    /**
     * The transaction signature is the fee payer's (first) signature: bytes 1..64 of a
     * serialized single-signer transaction (byte 0 is the compact-u16 signature count).
     */
    fun firstSignatureOf(signedTransaction: ByteArray): String {
        require(signedTransaction.size > 65 && signedTransaction[0].toInt() >= 1) { "Not a signed transaction" }
        return encode(signedTransaction.copyOfRange(1, 65))
    }
}
