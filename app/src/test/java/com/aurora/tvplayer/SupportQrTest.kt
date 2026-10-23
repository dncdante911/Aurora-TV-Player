package com.aurora.tvplayer

import com.aurora.tvplayer.data.Support
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.EncodeHintType
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The QR codes on the support screen must scan back to exactly the configured details. */
class SupportQrTest {
    private fun roundTrip(text: String): String {
        val matrix = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, 512, 512,
            mapOf(EncodeHintType.MARGIN to 4, EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M, EncodeHintType.CHARACTER_SET to "UTF-8"))
        val pixels = IntArray(matrix.width * matrix.height) { i -> if (matrix[i % matrix.width, i / matrix.width]) 0xFF071522.toInt() else -1 }
        val bitmap = BinaryBitmap(HybridBinarizer(RGBLuminanceSource(matrix.width, matrix.height, pixels)))
        return QRCodeReader().decode(bitmap).text
    }

    @Test
    fun everyMethodScansBack() {
        assertTrue(Support.methods.isNotEmpty())
        Support.methods.forEach { assertEquals(it.qr, roundTrip(it.qr)) }
    }

    @Test
    fun jarLinkIsTheMonobankOne() {
        assertEquals("https://send.monobank.ua/jar/9u1tP5JT21", Support.methods.first().qr)
    }
}
