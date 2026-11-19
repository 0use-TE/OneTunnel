// SPDX-License-Identifier: GPL-3.0-or-later
package dev.dsh.onetunnel

import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer
import java.io.ByteArrayOutputStream

object QrImport {
    fun fromImage(context: Context, uri: Uri): String {
        val bytes = context.contentResolver.openInputStream(uri)?.use { input ->
            val out = ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                require(out.size() + count <= 8 * 1024 * 1024) { "二维码图片不能超过 8 MiB。" }
                out.write(buffer, 0, count)
            }
            out.toByteArray()
        } ?: throw IllegalArgumentException("无法读取图片。")
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        require(bounds.outWidth > 0 && bounds.outHeight > 0) { "图片格式无效。" }
        val options = BitmapFactory.Options().apply {
            inSampleSize = 1
            while (bounds.outWidth / inSampleSize > 2048 || bounds.outHeight / inSampleSize > 2048) inSampleSize *= 2
        }
        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
            ?: throw IllegalArgumentException("图片解码失败。")
        return try {
            val pixels = IntArray(bitmap.width * bitmap.height)
            bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
            pixels.indices.forEach { index ->
                val pixel = pixels[index]
                val alpha = pixel ushr 24
                if (alpha < 255) {
                    val red = ((pixel shr 16 and 255) * alpha + 255 * (255 - alpha)) / 255
                    val green = ((pixel shr 8 and 255) * alpha + 255 * (255 - alpha)) / 255
                    val blue = ((pixel and 255) * alpha + 255 * (255 - alpha)) / 255
                    pixels[index] = (255 shl 24) or (red shl 16) or (green shl 8) or blue
                }
            }
            decodePixels(bitmap.width, bitmap.height, pixels)
        } finally { bitmap.recycle() }
    }
    internal fun decodePixels(width: Int, height: Int, pixels: IntArray): String {
        val reader = MultiFormatReader()
        val source = RGBLuminanceSource(width, height, pixels)
        val hints = mapOf(DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE), DecodeHintType.TRY_HARDER to true)
        val result = try { reader.decode(BinaryBitmap(HybridBinarizer(source)), hints).text }
        catch (_: Exception) {
            try { reader.reset(); reader.decode(BinaryBitmap(HybridBinarizer(source.invert())), hints).text }
            catch (_: Exception) { throw IllegalArgumentException("未识别到二维码，请选择清晰的完整二维码图片。") }
        } finally { reader.reset() }
        require(result.toByteArray().size <= WireGuardImport.MAX_BYTES) { "二维码内容过长。" }
        return result
    }
}
