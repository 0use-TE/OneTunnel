package dev.dsh.onetunnel

import com.google.zxing.BarcodeFormat
import com.google.zxing.MultiFormatWriter
import org.junit.Assert.*
import org.junit.Test

class QrImportTest {
    private val payload = "[Interface]\nPrivateKey = test\n[Peer]\nEndpoint = vpn.example.com:51820"
    private val size = 480
    private fun pixels(): IntArray {
        val qr = MultiFormatWriter().encode(payload, BarcodeFormat.QR_CODE, size, size)
        return IntArray(size * size) { index -> if (qr[index % size, index / size]) 0xFF000000.toInt() else 0xFFFFFFFF.toInt() }
    }
    @Test fun decodesQrPayloadWithoutChangingText() {
        assertEquals(payload, QrImport.decodePixels(size, size, pixels()))
    }
    @Test fun decodesRotatedAndInvertedQr() {
        val original = pixels()
        val rotated = IntArray(original.size)
        for (y in 0 until size) for (x in 0 until size) rotated[x * size + (size - 1 - y)] = original[y * size + x]
        assertEquals(payload, QrImport.decodePixels(size, size, rotated))
        assertEquals(payload, QrImport.decodePixels(size, size, original.map { it xor 0x00FFFFFF }.toIntArray()))
    }
    @Test fun rejectsImageWithoutQr() {
        assertThrows(IllegalArgumentException::class.java) { QrImport.decodePixels(100, 100, IntArray(10000) { -1 }) }
    }
}
