package app.solarma.wallet

import java.math.BigInteger

private const val BASE58_ALPHABET = "123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz"

/**
 * Decode a Base58 string into bytes.
 *
 * This preserves leading zero bytes (Base58 '1' prefix).
 */
fun decodeBase58ToBytes(input: String): ByteArray {
    var num = BigInteger.ZERO
    for (char in input) {
        val index = BASE58_ALPHABET.indexOf(char)
        require(index >= 0) { "Invalid Base58 character: $char" }
        num = num.multiply(BigInteger.valueOf(58)).add(BigInteger.valueOf(index.toLong()))
    }

    var bytes = num.toByteArray()
    // Remove leading zero if present (from BigInteger sign bit)
    if (bytes.isNotEmpty() && bytes[0] == 0.toByte()) {
        bytes = bytes.copyOfRange(1, bytes.size)
    }

    val leadingZeros = input.takeWhile { it == '1' }.length
    return ByteArray(leadingZeros) + bytes
}

