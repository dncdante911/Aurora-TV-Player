package com.aurora.tvplayer.ui.browser

import android.net.Uri
import com.aurora.tvplayer.data.Media
import com.aurora.tvplayer.net.SmbConnection
import jcifs.smb.SmbException
import jcifs.smb.SmbFile
import java.io.File
import java.io.IOException

/** A video that changed its address, so its position and history can follow it. */
data class Relocated(val from: String, val to: String, val title: String)

/**
 * File operations of a browser: only on the user's command, each with its subtitle files
 * ("Фильм.mkv" takes "Фильм.ru.srt" along). Blocking; call off the main thread.
 */
interface FileStore {
    /** [companions] are the names of the entry's subtitle files in the same folder. */
    fun rename(entry: BrowserEntry, newName: String, companions: List<String>): List<Relocated>
    fun delete(entry: BrowserEntry, companions: List<String>): List<String>
    fun move(entry: BrowserEntry, targetFolder: String, companions: List<String>, progress: (Float) -> Unit): List<Relocated>
    fun createFolder(parent: String, name: String)

    companion object {
        private val FORBIDDEN = "/\\:*?\"<>|"

        /** Why [name] cannot be a file name, or null when it can. */
        fun problem(name: String): String? = when {
            name.isBlank() -> "Введите имя"
            name == "." || name == ".." -> "Такое имя нельзя"
            name.any { it in FORBIDDEN || it < ' ' } -> "Без символов / \\ : * ? \" < > |"
            name.length > 200 -> "Слишком длинное имя"
            else -> null
        }

        /** "Старое.mkv" renamed to "Новое.mkv": "Старое.ru.srt" -> "Новое.ru.srt". */
        fun companionName(oldName: String, newName: String, companion: String): String {
            val oldBase = oldName.substringBeforeLast('.')
            return newName.substringBeforeLast('.') + companion.substring(oldBase.length)
        }
    }
}

/** USB and internal storage. Keys are absolute paths. */
object LocalFiles : FileStore {
    override fun rename(entry: BrowserEntry, newName: String, companions: List<String>): List<Relocated> {
        val source = File(entry.key)
        val target = File(source.parentFile, newName)
        if (target.exists()) throw IOException("Здесь уже есть «$newName»")
        if (!source.renameTo(target)) throw IOException("Телевизор не разрешил переименовать. Возможно, накопитель только для чтения.")
        companions.forEach { name -> File(source.parentFile, name).renameTo(File(source.parentFile, FileStore.companionName(entry.name, newName, name))) }
        return relocated(entry, source, target)
    }

    override fun delete(entry: BrowserEntry, companions: List<String>): List<String> {
        val file = File(entry.key)
        val videos = videosIn(entry, file).map { Uri.fromFile(it).toString() }
        if (!file.deleteRecursively()) throw IOException("Телевизор не разрешил удалить. Возможно, накопитель только для чтения.")
        companions.forEach { File(file.parentFile, it).delete() }
        return videos
    }

    override fun move(entry: BrowserEntry, targetFolder: String, companions: List<String>, progress: (Float) -> Unit): List<Relocated> {
        val source = File(entry.key)
        val dir = File(targetFolder)
        val target = File(dir, source.name)
        if (source.parentFile == dir) throw IOException("Файл уже в этой папке")
        if (dir.canonicalPath.startsWith(source.canonicalPath + File.separator) || dir.canonicalPath == source.canonicalPath) throw IOException("Нельзя перенести папку в саму себя")
        if (target.exists()) throw IOException("В этой папке уже есть «${source.name}»")
        val moved = relocated(entry, source, target)
        // Same disk: an instant rename. Another disk: copy with progress, then remove the original.
        if (!source.renameTo(target)) {
            copyWithProgress(source, target, progress)
            if (!source.deleteRecursively()) throw IOException("Скопировано, но оригинал удалить не удалось")
        }
        companions.forEach { name ->
            val sub = File(source.parentFile, name)
            val subTarget = File(dir, name)
            if (!sub.renameTo(subTarget)) runCatching { sub.copyTo(subTarget, overwrite = false); sub.delete() }
        }
        return moved
    }

    override fun createFolder(parent: String, name: String) {
        val dir = File(parent, name)
        if (dir.exists()) throw IOException("Здесь уже есть «$name»")
        if (!dir.mkdir()) throw IOException("Телевизор не разрешил создать папку. Возможно, накопитель только для чтения.")
    }

    /** Videos in a folder (one level) or the video itself, as they were before the change. */
    private fun videosIn(entry: BrowserEntry, file: File): List<File> = when (entry.kind) {
        EntryKind.VIDEO -> listOf(file)
        EntryKind.FOLDER -> file.listFiles()?.filter { it.isFile && Media.isVideo(it.name) }.orEmpty()
        else -> emptyList()
    }

    private fun relocated(entry: BrowserEntry, source: File, target: File): List<Relocated> = videosIn(entry, source).map { video ->
        val newFile = if (entry.kind == EntryKind.VIDEO) target else File(target, video.name)
        Relocated(Uri.fromFile(video).toString(), Uri.fromFile(newFile).toString(), Media.title(newFile.name))
    }

    private fun copyWithProgress(source: File, target: File, progress: (Float) -> Unit) {
        val total = source.walkTopDown().filter { it.isFile }.sumOf { it.length() }.coerceAtLeast(1)
        var done = 0L
        val buffer = ByteArray(1 shl 20)
        try {
            source.walkTopDown().forEach { file ->
                val destination = File(target, file.relativeTo(source).path).takeIf { source.isDirectory } ?: target
                if (file.isDirectory) {
                    destination.mkdirs()
                    return@forEach
                }
                destination.parentFile?.mkdirs()
                file.inputStream().use { input ->
                    destination.outputStream().use { output ->
                        while (true) {
                            if (Thread.currentThread().isInterrupted) throw IOException("Отменено")
                            val n = input.read(buffer)
                            if (n < 0) break
                            output.write(buffer, 0, n)
                            done += n
                            progress(done.toFloat() / total)
                        }
                    }
                }
            }
        } catch (e: Exception) {
            // Never leave half a film behind.
            target.deleteRecursively()
            throw e as? IOException ?: IOException("Не удалось скопировать: ${e.message}", e)
        }
    }
}

/** The connected SMB share. Keys are smb:// URLs; moves stay on the server, nothing is copied through the TV. */
object SmbFiles : FileStore {
    override fun rename(entry: BrowserEntry, newName: String, companions: List<String>): List<Relocated> = smb {
        val session = SmbConnection.forUrl(entry.key)
        val parent = session.parentOf(entry.key) ?: throw IOException("Корень общей папки не переименовать")
        val source = session.file(entry.key)
        val target = SmbFile(session.file(parent), newName + if (entry.kind == EntryKind.FOLDER) "/" else "")
        if (target.exists()) throw IOException("Здесь уже есть «$newName»")
        source.renameTo(target)
        companions.forEach { name ->
            runCatching { SmbFile(session.file(parent), name).renameTo(SmbFile(session.file(parent), FileStore.companionName(entry.name, newName, name))) }
        }
        relocated(session, entry, target.path)
    }

    override fun delete(entry: BrowserEntry, companions: List<String>): List<String> = smb {
        val session = SmbConnection.forUrl(entry.key)
        val parent = session.parentOf(entry.key) ?: throw IOException("Корень общей папки не удалить")
        val videos = videosIn(session, entry)
        session.file(entry.key).delete()
        companions.forEach { name -> runCatching { SmbFile(session.file(parent), name).delete() } }
        videos
    }

    override fun move(entry: BrowserEntry, targetFolder: String, companions: List<String>, progress: (Float) -> Unit): List<Relocated> = smb {
        val session = SmbConnection.forUrl(entry.key)
        val parent = session.parentOf(entry.key) ?: throw IOException("Корень общей папки не перенести")
        if (parent == targetFolder) throw IOException("Файл уже в этой папке")
        if (targetFolder.startsWith(entry.key)) throw IOException("Нельзя перенести папку в саму себя")
        val target = SmbFile(session.file(targetFolder), entry.name + if (entry.kind == EntryKind.FOLDER) "/" else "")
        if (target.exists()) throw IOException("В этой папке уже есть «${entry.name}»")
        val moved = relocated(session, entry, target.path)
        session.file(entry.key).renameTo(target)
        companions.forEach { name -> runCatching { SmbFile(session.file(parent), name).renameTo(SmbFile(session.file(targetFolder), name)) } }
        progress(1f)
        moved
    }

    override fun createFolder(parent: String, name: String) = smb {
        val session = SmbConnection.forUrl(parent)
        val dir = SmbFile(session.file(parent), "$name/")
        if (dir.exists()) throw IOException("Здесь уже есть «$name»")
        dir.mkdir()
    }

    private fun videosIn(session: SmbConnection, entry: BrowserEntry): List<String> = when (entry.kind) {
        EntryKind.VIDEO -> listOf(entry.key)
        EntryKind.FOLDER -> runCatching { session.list(entry.key).filter { it.video }.map { it.url } }.getOrDefault(emptyList())
        else -> emptyList()
    }

    private fun relocated(session: SmbConnection, entry: BrowserEntry, newUrl: String): List<Relocated> = videosIn(session, entry).map { old ->
        val url = if (entry.kind == EntryKind.VIDEO) newUrl else newUrl + old.removePrefix(entry.key)
        Relocated(old, url, Media.title(url.trimEnd('/').substringAfterLast('/')))
    }

    /** Server refusals ("read-only share", "access denied") become a sentence for the user. */
    private fun <T> smb(block: () -> T): T = try {
        block()
    } catch (e: SmbException) {
        // SmbException is an IOException too, so it is caught first.
        throw IOException(if (e.ntStatus == SmbException.NT_STATUS_ACCESS_DENIED) "Сервер не разрешил изменение: у этого пользователя доступ только на чтение."
            else "Сервер отказал: ${e.message}", e)
    } catch (e: IOException) {
        throw e
    } catch (e: Exception) {
        throw IOException("Сервер отказал: ${e.message}", e)
    }
}
