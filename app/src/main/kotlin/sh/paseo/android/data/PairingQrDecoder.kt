package sh.paseo.android.data

import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.ReaderException
import com.google.zxing.common.HybridBinarizer
import java.nio.ByteBuffer

/** Single analyzer-thread decoder; accepts CameraX's padded Y plane without retaining the image. */
class PairingQrDecoder {
    private val reader = MultiFormatReader().apply {
        setHints(mapOf(DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE), DecodeHintType.TRY_HARDER to true))
    }
    fun decode(y: ByteBuffer, width: Int, height: Int, rowStride: Int, pixelStride: Int): String? {
        if (width <= 0 || height <= 0 || width.toLong() * height > 4_194_304 || pixelStride <= 0 || rowStride < width * pixelStride) return null
        val plane = y.duplicate()
        val start = plane.position()
        val end = start.toLong() + (height - 1L) * rowStride + (width - 1L) * pixelStride
        if (end >= plane.limit()) return null
        val pixels = ByteArray(width * height)
        for (row in 0 until height) for (col in 0 until width) pixels[row * width + col] = plane.get(start + row * rowStride + col * pixelStride)
        val source = PlanarYUVLuminanceSource(pixels, width, height, 0, 0, width, height, false)
        return try {
            try { reader.decodeWithState(BinaryBitmap(HybridBinarizer(source))).text }
            catch (_: ReaderException) { reader.decodeWithState(BinaryBitmap(HybridBinarizer(source.invert()))).text }
        } catch (_: ReaderException) { null }
        finally { reader.reset() }
    }
}
