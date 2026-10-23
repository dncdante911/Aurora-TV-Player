package com.aurora.tvplayer

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.speech.RecognizerIntent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.core.net.toUri
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.aurora.tvplayer.data.Media
import com.aurora.tvplayer.player.PlayItem
import com.aurora.tvplayer.player.PlayRequest
import com.aurora.tvplayer.ui.AppRoot
import com.aurora.tvplayer.ui.MainViewModel
import com.aurora.tvplayer.ui.UiEvent
import com.aurora.tvplayer.ui.browser.LocalBrowserViewModel
import com.aurora.tvplayer.ui.components.DialogAction
import com.aurora.tvplayer.ui.components.DialogSpec
import com.aurora.tvplayer.ui.theme.AuroraTheme
import com.aurora.tvplayer.ui.theme.Icons
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private val vm: MainViewModel by viewModels()
    private val files: LocalBrowserViewModel by viewModels()
    private var accessSettingsOpened = false

    private val pickFile = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri ?: return@registerForActivityResult
        runCatching { contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        val name = Media.displayName(this, uri)
        vm.send(UiEvent.Play(PlayRequest(listOf(PlayItem(uri.toString(), Media.title(name))))))
    }
    private val voice = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()?.let(vm::handleVoice)
    }
    private val readStorage = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) files.retryPendingVolume() else vm.toast("Доступ к файлам не разрешён")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { AuroraTheme { AppRoot(vm) } }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) { vm.uiEvents.collect(::handle) }
        }
        if (savedInstanceState == null) handleViewIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleViewIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        if (files.pendingVolume == null) return
        if (files.retryPendingVolume()) {
            accessSettingsOpened = false
        } else if (accessSettingsOpened) {
            accessSettingsOpened = false
            vm.show(DialogSpec.Message(
                "Разрешение не включено",
                "Если телевизор не показал переключатель «Доступ ко всем файлам», откройте страницу Aurora TV и выберите «Разрешения» или «Специальный доступ».",
                listOf(DialogAction("Страница Aurora TV", Icons.Settings) { openAppDetails() }),
            ))
        }
    }

    /** Videos opened from other apps ("Открыть с помощью"). */
    private fun handleViewIntent(intent: Intent) {
        if (intent.action != Intent.ACTION_VIEW) return
        val data = intent.data ?: return
        startActivity(PlayRequest(listOf(PlayItem(data.toString(), Media.title(Media.displayName(this, data))))).toIntent(this)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
        setIntent(Intent(this, MainActivity::class.java))
    }

    private fun handle(event: UiEvent) {
        when (event) {
            is UiEvent.Play -> startActivity(event.request.toIntent(this))
            UiEvent.PickFile -> try {
                pickFile.launch(arrayOf("video/*", "application/octet-stream", "application/x-matroska"))
            } catch (e: ActivityNotFoundException) {
                vm.toast("На ТВ нет системного выбора файлов. Откройте «USB / папки».")
            }
            is UiEvent.Voice -> try {
                voice.launch(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                    .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                    .putExtra(RecognizerIntent.EXTRA_LANGUAGE, event.language)
                    .putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
                    .putExtra(RecognizerIntent.EXTRA_PROMPT, "Например: открой сеть, продолжи или найди «Шерлок»"))
            } catch (e: RuntimeException) {
                vm.show(DialogSpec.Message("Распознавание недоступно",
                    "На телевизоре нет службы распознавания речи для приложений. Проверьте голосовые службы Google TV; управление пультом остаётся доступным."))
            }
            is UiEvent.RequestStorageAccess -> requestStorageAccess()
            UiEvent.OpenAppSettings -> openAppDetails()
        }
    }

    private fun requestStorageAccess() {
        if (Build.VERSION.SDK_INT < 30) {
            readStorage.launch(Manifest.permission.READ_EXTERNAL_STORAGE)
            return
        }
        vm.show(DialogSpec.Message(
            "Доступ к USB и файлам",
            "Чтобы показывать папки USB-накопителя, Android 11+ требует разрешение «Доступ ко всем файлам». " +
                "Включите его для Aurora TV и вернитесь сюда. Плеер ничего не изменяет и не удаляет.",
            listOf(DialogAction("Открыть настройки", Icons.Settings) { openAccessSettings() }),
        ))
    }

    private fun openAccessSettings() {
        accessSettingsOpened = true
        try {
            startActivity(Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, "package:$packageName".toUri()))
        } catch (e: RuntimeException) {
            openAppDetails()
        }
    }

    private fun openAppDetails() {
        accessSettingsOpened = true
        try {
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, "package:$packageName".toUri()))
        } catch (e: RuntimeException) {
            accessSettingsOpened = false
            vm.toast("ТВ не открыл настройки. Найдите Aurora TV в настройках вручную.")
        }
    }
}
