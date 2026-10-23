package com.aurora.tvplayer.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.aurora.tvplayer.VoiceState
import com.aurora.tvplayer.ui.theme.Aurora
import com.aurora.tvplayer.ui.theme.Icons

/** "Говорите…" card with a microphone that pulses with the voice and the words heard so far. */
@Composable
fun VoiceOverlay(state: VoiceState, onCancel: () -> Unit) {
    BackHandler(onBack = onCancel)
    val (heard, level) = when (state) {
        is VoiceState.Listening -> state.heard to state.level
        is VoiceState.Thinking -> state.heard to 0f
        VoiceState.Off -> "" to 0f
    }
    val pulse by animateFloatAsState(1f + level * 0.35f, label = "pulse")
    Box(Modifier.fillMaxSize().background(Aurora.navyDeep.copy(alpha = 0.55f)), contentAlignment = Alignment.BottomCenter) {
        Column(
            Modifier.padding(bottom = 64.dp).width(620.dp)
                .background(Aurora.navyDeep.copy(alpha = 0.96f), RoundedCornerShape(24.dp))
                .border(1.dp, Aurora.cyan.copy(alpha = 0.5f), RoundedCornerShape(24.dp))
                .padding(28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(contentAlignment = Alignment.Center) {
                Box(Modifier.size(84.dp).scale(pulse).background(Aurora.cyan.copy(alpha = 0.18f), CircleShape))
                Box(Modifier.size(64.dp).background(Aurora.cyan, CircleShape), contentAlignment = Alignment.Center) {
                    Icon(Icons.Mic, null, tint = Aurora.navy, modifier = Modifier.size(32.dp))
                }
            }
            Spacer(Modifier.height(16.dp))
            Text(if (state is VoiceState.Thinking) "Распознаю…" else "Говорите…", style = MaterialTheme.typography.headlineSmall, color = Aurora.white)
            Spacer(Modifier.height(6.dp))
            Text(
                heard.ifEmpty { "Например: «открой сеть», «продолжи», «найди Шерлок»" },
                style = MaterialTheme.typography.bodyLarge, color = if (heard.isEmpty()) Aurora.muted else Aurora.mint,
                textAlign = TextAlign.Center, maxLines = 2,
            )
            Spacer(Modifier.height(10.dp))
            Text("«Назад» — отмена", style = MaterialTheme.typography.bodySmall, color = Aurora.faint)
        }
    }
}
