package sh.paseo.android.data

import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import java.nio.ByteBuffer
import org.junit.Assert.*
import org.junit.Test

class PairingQrDecoderTest {
    private val link = "https://app.paseo.sh/#offer=eyJ2IjoyLCJzZXJ2ZXJJZCI6InRlc3QtY29tcHV0ZXIifQ"

    @Test fun readsCameraPlaneWithOffsetPixelStrideAndUnpaddedLastRow() {
        val plane = qrPlane(pixelStride = 2, rowPadding = 13, prefix = 11)
        assertEquals(link, PairingQrDecoder().decode(plane, 320, 320, 653, 2))
        assertEquals(11, plane.position())
    }

    @Test fun readsRotatedAndInvertedQrWithoutRotatingCameraBuffers() {
        val decoder = PairingQrDecoder()
        assertEquals(link, decoder.decode(qrPlane(rotated = true), 320, 320, 320, 1))
        assertEquals(link, decoder.decode(qrPlane(inverted = true), 320, 320, 320, 1))
    }

    @Test fun appliesCameraRotationBeforeDecoding() {
        val rotated = qrPlane(clockwiseRotation = 90)
        assertEquals(link, PairingQrDecoder().decode(rotated, 320, 320, 320, 1, 270))
    }

    @Test fun ignoresBlankAndTruncatedFramesAndCanDecodeTheNextFrame() {
        val decoder = PairingQrDecoder()
        assertNull(decoder.decode(ByteBuffer.wrap(ByteArray(320 * 320) { -1 }), 320, 320, 320, 1))
        assertNull(decoder.decode(ByteBuffer.allocate(8), 320, 320, 320, 1))
        assertNull(decoder.decode(ByteBuffer.allocate(8), -1, 2, 1, 1))
        assertEquals(link, decoder.decode(qrPlane(), 320, 320, 320, 1))
    }

    private fun qrPlane(pixelStride: Int = 1, rowPadding: Int = 0, prefix: Int = 0, rotated: Boolean = false, inverted: Boolean = false, clockwiseRotation: Int = 0): ByteBuffer {
        val matrix = QRCodeWriter().encode(link, BarcodeFormat.QR_CODE, 320, 320)
        val stride = 320 * pixelStride + rowPadding
        val data = ByteArray(prefix + 319 * stride + 319 * pixelStride + 1) { 127 }
        for (y in 0 until 320) for (x in 0 until 320) {
            val (sourceX, sourceY) = when (clockwiseRotation) {
                90 -> y to 319 - x
                180 -> 319 - x to 319 - y
                270 -> 319 - y to x
                else -> x to y
            }
            val black = if (rotated) matrix[319 - sourceY, sourceX] else matrix[sourceX, sourceY]
            data[prefix + y * stride + x * pixelStride] = if (black != inverted) 0 else -1
        }
        return ByteBuffer.wrap(data).apply { position(prefix) }
    }
}
