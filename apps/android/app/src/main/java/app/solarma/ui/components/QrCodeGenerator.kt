package app.solarma.ui.components

import android.graphics.Bitmap
import android.graphics.Color
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.graphics.createBitmap
import androidx.core.graphics.set
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter

/**
 * Generate QR code bitmap from text.
 */
fun generateQrBitmap(text: String, size: Int = 512): Bitmap {
    val writer = QRCodeWriter()
    val bitMatrix = writer.encode(text, BarcodeFormat.QR_CODE, size, size)

    val bitmap = createBitmap(size, size)
    for (x in 0 until size) {
        for (y in 0 until size) {
            bitmap[x, y] = if (bitMatrix[x, y]) Color.BLACK else Color.WHITE
        }
    }
    return bitmap
}

/**
 * Composable that displays a QR code.
 */
@Composable
fun QrCodeImage(
    text: String,
    modifier: Modifier = Modifier,
    size: Dp = 200.dp
) {
    val bitmap = remember(text) {
        generateQrBitmap(text, 512)
    }

    Image(
        bitmap = bitmap.asImageBitmap(),
        contentDescription = "QR Code for $text",
        modifier = modifier.size(size)
    )
}
