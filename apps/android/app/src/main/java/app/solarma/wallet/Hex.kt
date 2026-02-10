package app.solarma.wallet

fun bytesToHexLower(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it) }

fun decodeHexToBytesOrNull(hex: String?): ByteArray? {
    if (hex.isNullOrBlank()) return null
    val clean = hex.trim()
    if (clean.length % 2 != 0) return null
    return try {
        ByteArray(clean.length / 2) { i ->
            val index = i * 2
            clean.substring(index, index + 2).toInt(16).toByte()
        }
    } catch (_: Exception) {
        null
    }
}

