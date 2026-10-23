package com.aurora.tvplayer.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aurora.tvplayer.ui.browser.FilesScreen
import com.aurora.tvplayer.ui.browser.NetworkScreen
import com.aurora.tvplayer.ui.components.DialogHost
import com.aurora.tvplayer.ui.components.MessageBar
import com.aurora.tvplayer.ui.device.CapabilitiesScreen
import com.aurora.tvplayer.ui.device.DeviceScreen
import com.aurora.tvplayer.ui.home.HomeScreen
import com.aurora.tvplayer.ui.support.SupportScreen
import com.aurora.tvplayer.ui.theme.AuroraBackground

@Composable
fun AppRoot(vm: MainViewModel) {
    val settings by vm.settings.state.collectAsStateWithLifecycle()
    val states = rememberSaveableStateHolder()
    val screen = vm.current
    BackHandler(enabled = vm.stack.size > 1) { vm.back() }

    AuroraBackground(animated = settings.animatedBackground) {
        states.SaveableStateProvider(screen.toString()) {
            when (screen) {
                Screen.Home -> HomeScreen(vm)
                is Screen.Files -> FilesScreen(vm, screen.initialFolder)
                Screen.Network -> NetworkScreen(vm)
                Screen.Device -> DeviceScreen(vm)
                Screen.Capabilities -> CapabilitiesScreen(vm)
                Screen.Support -> SupportScreen()
            }
        }
        vm.message?.let { MessageBar(it, Modifier.align(Alignment.BottomCenter).padding(bottom = 32.dp)) }
        vm.dialog?.let { DialogHost(it, vm::dismissDialog) }
        if (vm.voice != com.aurora.tvplayer.VoiceState.Off) VoiceOverlay(vm.voice) { vm.send(UiEvent.CancelVoice) }
    }
}
