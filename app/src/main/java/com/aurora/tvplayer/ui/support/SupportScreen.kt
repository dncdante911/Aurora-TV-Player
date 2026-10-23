package com.aurora.tvplayer.ui.support

import android.graphics.Bitmap
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Border
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Glow
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import com.aurora.tvplayer.data.Support
import com.aurora.tvplayer.ui.components.CardShape
import com.aurora.tvplayer.ui.components.FocusMemory
import com.aurora.tvplayer.ui.components.InitialFocus
import com.aurora.tvplayer.ui.components.ScreenHeader
import com.aurora.tvplayer.ui.theme.Aurora
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel

/** QR codes to scan with a phone: paying on a TV remote is no fun. Nothing here is required. */
@Composable
fun SupportScreen(methods: List<Support.Method> = remember { Support.methods }) {
    val memory = remember { FocusMemory() }
    val first = remember { FocusRequester() }
    InitialFocus(memory, first)

    Column(Modifier.fillMaxSize().padding(top = 28.dp)) {
        ScreenHeader("Поддержать автора", "Спасибо, что пользуетесь Aurora TV", Modifier.padding(horizontal = 48.dp),
            subtitle = "Плеер бесплатный и без рекламы. Если он вам нравится — наведите камеру телефона на код.")
        Spacer(Modifier.height(14.dp))
        LazyRow(contentPadding = PaddingValues(horizontal = 48.dp, vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(22.dp)) {
            items(methods, key = { it.title }) { method ->
                MethodCard(method, if (method == methods.first()) Modifier.focusRequester(first) else Modifier)
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(
            "Это добровольная благодарность: все функции плеера работают без неё. Любая сумма — уже большое спасибо!",
            style = MaterialTheme.typography.bodyMedium, color = Aurora.muted, modifier = Modifier.padding(horizontal = 48.dp),
        )
    }
}

@Composable
private fun MethodCard(method: Support.Method, modifier: Modifier) {
    val qr = remember(method.qr) { qrBitmap(method.qr, 512) }
    Surface(
        onClick = {},
        modifier = modifier.size(width = 262.dp, height = 336.dp),
        shape = ClickableSurfaceDefaults.shape(CardShape),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = Aurora.panel.copy(alpha = 0.9f), contentColor = Aurora.white,
            focusedContainerColor = Aurora.panelHigh, focusedContentColor = Aurora.white,
        ),
        border = ClickableSurfaceDefaults.border(
            border = Border(BorderStroke(1.dp, Aurora.line), shape = CardShape),
            focusedBorder = Border(BorderStroke(2.dp, Aurora.cyan), shape = CardShape),
        ),
        glow = ClickableSurfaceDefaults.glow(focusedGlow = Glow(Aurora.cyan.copy(alpha = 0.4f), 14.dp)),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.04f),
    ) {
        Column(Modifier.fillMaxSize().padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(method.title, style = MaterialTheme.typography.titleMedium, color = Aurora.cyan)
            Text(method.detail, style = MaterialTheme.typography.bodySmall, color = Aurora.muted, textAlign = TextAlign.Center, maxLines = 2)
            Spacer(Modifier.height(10.dp))
            // A white quiet zone keeps the code readable for phone cameras on dark TV screens.
            Box(Modifier.size(184.dp).background(Aurora.white, RoundedCornerShape(12.dp)).padding(10.dp)) {
                Image(qr, method.title, Modifier.fillMaxSize(), filterQuality = FilterQuality.None)
            }
            Spacer(Modifier.height(10.dp))
            Text(method.shown, style = MaterialTheme.typography.labelMedium, color = Aurora.white, textAlign = TextAlign.Center,
                maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.fillMaxWidth())
        }
    }
}

private fun qrBitmap(text: String, size: Int): ImageBitmap {
    val matrix = QRCodeWriter().encode(
        text, BarcodeFormat.QR_CODE, size, size,
        mapOf(EncodeHintType.MARGIN to 0, EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M, EncodeHintType.CHARACTER_SET to "UTF-8"),
    )
    val pixels = IntArray(matrix.width * matrix.height) { i ->
        if (matrix[i % matrix.width, i / matrix.width]) 0xFF071522.toInt() else 0xFFFFFFFF.toInt()
    }
    return Bitmap.createBitmap(pixels, matrix.width, matrix.height, Bitmap.Config.ARGB_8888).asImageBitmap()
}
