package com.aurora.tvplayer.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.aurora.tvplayer.data.UpdateState
import com.aurora.tvplayer.ui.components.AuroraButton
import com.aurora.tvplayer.ui.components.ProgressBar
import com.aurora.tvplayer.ui.theme.Aurora
import com.aurora.tvplayer.ui.theme.Icons

/**
 * Slim strip under the banner when a newer version exists. It never takes focus on its own:
 * the remote reaches it with one press up from the source cards.
 */
@Composable
fun UpdateBanner(state: UpdateState, onDownload: () -> Unit, onInstall: () -> Unit, modifier: Modifier = Modifier) {
    val (title, detail, action) = when (state) {
        is UpdateState.Available -> Triple("Доступна Aurora TV ${state.release.version}", firstLine(state.release.notes), "Скачать")
        is UpdateState.Downloading -> Triple("Скачиваю Aurora TV ${state.release.version}", "${(state.progress * 100).toInt()}% · можно продолжать смотреть", null)
        is UpdateState.Ready -> Triple("Aurora TV ${state.release.version} готова к установке", state.error ?: firstLine(state.release.notes), "Обновить")
        is UpdateState.Installing -> Triple("Устанавливаю Aurora TV ${state.release.version}", "Плеер закроется на пару секунд — откройте его снова", null)
        else -> return
    }
    Row(
        modifier.fillMaxWidth()
            .background(Aurora.panel.copy(alpha = 0.85f), RoundedCornerShape(14.dp))
            .border(1.dp, Aurora.cyan.copy(alpha = 0.45f), RoundedCornerShape(14.dp))
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Box(Modifier.size(36.dp).background(Aurora.cyan.copy(alpha = 0.15f), CircleShape), contentAlignment = Alignment.Center) {
            Icon(Icons.Up, null, tint = Aurora.cyan, modifier = Modifier.size(20.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall, color = Aurora.white, maxLines = 1)
            Text(detail, style = MaterialTheme.typography.bodySmall, color = if (state is UpdateState.Ready && state.error != null) Aurora.warn else Aurora.muted,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (state is UpdateState.Downloading) {
                Spacer(Modifier.height(6.dp))
                ProgressBar(state.progress, Modifier.fillMaxWidth(0.6f))
            }
        }
        action?.let { label ->
            AuroraButton(label, onClick = if (state is UpdateState.Available) onDownload else onInstall, icon = Icons.Up, accent = true)
        }
    }
}

private fun firstLine(notes: String): String =
    notes.lineSequence().map { it.trim().trimStart('#', '*', '-', ' ') }.firstOrNull { it.isNotEmpty() } ?: "Новая версия на GitHub"
