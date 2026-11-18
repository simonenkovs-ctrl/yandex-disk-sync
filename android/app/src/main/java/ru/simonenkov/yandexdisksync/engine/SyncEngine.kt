package ru.simonenkov.yandexdisksync.engine

import android.content.Context
import android.net.Uri
import android.webkit.MimeTypeMap
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import ru.simonenkov.yandexdisksync.api.Resource
import ru.simonenkov.yandexdisksync.api.YandexDiskClient
import ru.simonenkov.yandexdisksync.data.AppPreferences
import ru.simonenkov.yandexdisksync.data.SyncFolderPair
import ru.simonenkov.yandexdisksync.data.SyncMode
import ru.simonenkov.yandexdisksync.data.db.AppDatabase
import ru.simonenkov.yandexdisksync.data.db.FileRecord
import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.security.MessageDigest

class SyncEngine(
    private val context: Context,
    private val prefs: AppPreferences,
    private val db: AppDatabase
) {

    private val _progressFlow = MutableStateFlow(SyncProgress())
    val progressFlow: StateFlow<SyncProgress> = _progressFlow.asStateFlow()

    private val _logsFlow = MutableStateFlow<List<SyncLogItem>>(emptyList())
    val logsFlow: StateFlow<List<SyncLogItem>> = _logsFlow.asStateFlow()

    @Volatile
    private var isCancelled = false

    private val DIGEST_MD5 = "MD5"

    fun cancelSync() {
        isCancelled = true
        log(LogLevel.WARNING, "Синхронизация прервана пользователем")
    }

    fun clearLogs() {
        _logsFlow.value = emptyList()
    }

    private fun log(level: LogLevel, message: String) {
        val item = SyncLogItem(level = level, message = message)
        val current = _logsFlow.value.toMutableList()
        current.add(0, item) // новые сверху
        if (current.size > 200) current.removeAt(current.lastIndex)
        _logsFlow.value = current
    }

    suspend fun syncAll(): Unit = withContext(Dispatchers.IO) {
        val folders = prefs.getFolders().filter { it.enabled }
        if (folders.isEmpty()) {
            log(LogLevel.WARNING, "Нет включенных папок для синхронизации. Добавьте или включите папки.")
            return@withContext
        }

        if (prefs.wifiOnly) {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? android.net.ConnectivityManager
            val isWifi = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
                val net = cm?.activeNetwork
                val caps = cm?.getNetworkCapabilities(net)
                caps?.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI) == true
            } else {
                @Suppress("DEPRECATION")
                cm?.activeNetworkInfo?.type == android.net.ConnectivityManager.TYPE_WIFI
            }
            if (!isWifi) {
                log(LogLevel.WARNING, "Синхронизация пропущена: включена опция «Только по Wi-Fi», а Wi-Fi не подключен.")
                return@withContext
            }
        }

        val token = prefs.oauthToken
        if (token.isBlank()) {
            log(LogLevel.ERROR, "Не указан OAuth-токен в настройках")
            return@withContext
        }

        isCancelled = false
        _progressFlow.value = SyncProgress(isRunning = true, actionText = "Запуск синхронизации...")
        log(LogLevel.INFO, "▶ Начало синхронизации (${folders.size} папок)")

        val client = YandexDiskClient(token)

        try {
            for (pair in folders) {
                if (isCancelled) break
                syncFolderPair(pair, client)
            }
            if (!isCancelled) {
                log(LogLevel.SUCCESS, "✔ Синхронизация всех папок успешно завершена!")
            }
        } catch (e: Exception) {
            log(LogLevel.ERROR, "Сбой синхронизации: ${e.localizedMessage ?: e.message}")
        } finally {
            _progressFlow.value = SyncProgress(isRunning = false, actionText = "Синхронизация завершена")
        }
    }

    suspend fun syncSingleFolder(pair: SyncFolderPair): Unit = withContext(Dispatchers.IO) {
        val token = prefs.oauthToken
        if (token.isBlank()) {
            log(LogLevel.ERROR, "Не указан OAuth-токен в настройках")
            return@withContext
        }

        isCancelled = false
        _progressFlow.value = SyncProgress(isRunning = true, actionText = "Синхронизация папки: ${pair.displayName}")
        log(LogLevel.INFO, "▶ Начало синхронизации папки [${pair.displayName}]")

        val client = YandexDiskClient(token)
        try {
            syncFolderPair(pair, client)
            if (!isCancelled) {
                log(LogLevel.SUCCESS, "✔ Папка [${pair.displayName}] успешно синхронизирована!")
            }
        } catch (e: Exception) {
            log(LogLevel.ERROR, "Ошибка в папке [${pair.displayName}]: ${e.localizedMessage ?: e.message}")
        } finally {
            _progressFlow.value = SyncProgress(isRunning = false, actionText = "Завершено")
        }
    }

    /**
     * Build a DeletionGate that determines whether deletions need user confirmation.
     * It uses the DeletionPlanner to decide if the number of deletions exceeds safety thresholds.
     */
    private suspend fun buildDeletionGate(
        pair: SyncFolderPair,
        localFiles: Map<String, DocumentFile>,
        remoteFiles: Map<String, Resource>
    ): DeletionGate {
        // Existing records for this pair (used as baseline)
        val records = db.fileRecordDao().getRecordsForPair(pair.id)
        val knownKeys = records.map { it.relPath }.toSet()
        val baselineEstablished = db.fileRecordDao().countForPair(pair.id) == 0

        // Candidates are files that exist on one side but not the other AND have a record (i.e., were previously synced)
        val candidates = mutableSetOf<String>()
        for ((rel, doc) in localFiles) {
            if (remoteFiles[rel] == null && db.fileRecordDao().getRecord(pair.id, rel) != null) {
                candidates.add(rel)
            }
        }
        for ((rel, _) in remoteFiles) {
            if (!localFiles.containsKey(rel) && db.fileRecordDao().getRecord(pair.id, rel) != null) {
                candidates.add(rel)
            }
        }

        val outcome = DeletionPlanner.plan(candidates, knownKeys, baselineEstablished)
        if (!outcome.blocked) return DeletionGate.allowAll()

        // Count how many blocked deletions are local vs remote
        var blockedLocal = 0
        var blockedRemote = 0
        for (rel in outcome.keys) {
            if (localFiles.containsKey(rel) && remoteFiles[rel] == null) blockedLocal++
            if (remoteFiles.containsKey(rel) && !localFiles.containsKey(rel)) blockedRemote++
        }
        return DeletionGate(blockedLocal, blockedRemote)
    }

    private suspend fun syncFolderPair(pair: SyncFolderPair, client: YandexDiskClient) {

        _progressFlow.value = _progressFlow.value.copy(
            currentFolderName = pair.displayName,
            actionText = "Сканирование папок..."
        )
        log(LogLevel.INFO, "Папка [${pair.displayName}]: режим ${pair.mode.title}")

        val treeUri = Uri.parse(pair.localTreeUri)
        val rootDoc = DocumentFile.fromTreeUri(context, treeUri)
        if (rootDoc == null || !rootDoc.exists() || !rootDoc.isDirectory) {
            log(LogLevel.ERROR, "Локальная папка [${pair.displayName}] недоступна. Проверьте права доступа в системе.")
            prefs.updateFolderSyncResult(pair.id, "Ошибка доступа к локальной папке", 0)
            return
        }

        // 1. Проверяем / создаем целевую папку на Диске
        try {
            client.createFolder(pair.remotePath)
        } catch (e: Exception) {
            log(LogLevel.ERROR, "Не удалось создать/открыть папку в облаке: ${pair.remotePath}: ${e.message}")
            prefs.updateFolderSyncResult(pair.id, "Ошибка облачной папки", 0)
            return
        }

        // 2. Сканируем локальное дерево файлов
        _progressFlow.value = _progressFlow.value.copy(actionText = "Сканирование локальных файлов...")
        val localFiles = mutableMapOf<String, DocumentFile>()
        scanLocalTreeRecursive(rootDoc, "", localFiles)
        log(LogLevel.INFO, "Найдено локальных файлов: ${localFiles.size}")

        // 3. Сканируем облачное дерево файлов
        _progressFlow.value = _progressFlow.value.copy(actionText = "Сканирование файлов на Яндекс Диске...")
        val remoteResources = try {
            client.listFolderRecursive(pair.remotePath)
        } catch (e: Exception) {
            log(LogLevel.ERROR, "Ошибка получения списка файлов с Диска: ${e.message}")
            prefs.updateFolderSyncResult(pair.id, "Ошибка списка облака", 0)
            return
        }

        val remoteFiles = mutableMapOf<String, Resource>()
        val baseClean = pair.remotePath.trim().trimEnd('/')
        for (res in remoteResources) {
            if (!res.isDir) {
                val cleanItemPath = res.path.removePrefix("disk:").trim()
                val rel = if (cleanItemPath.startsWith(baseClean)) {
                    cleanItemPath.substring(baseClean.length).trimStart('/')
                } else {
                    res.name
                }
                if (rel.isNotEmpty() && !isIgnored(res.name)) {
                    remoteFiles[rel] = res
                }
            }
        }
        log(LogLevel.INFO, "Найдено файлов на Яндекс Диске: ${remoteFiles.size}")

        if (isCancelled) {
            prefs.updateFolderSyncResult(pair.id, "Отменена", 0)
            return
        }
        val deletionGate = if (pair.mode == SyncMode.TWO_WAY && prefs.syncDeletions) {
            buildDeletionGate(pair, localFiles, remoteFiles)
        } else {
            DeletionGate.allowAll()
        }
        if (deletionGate.needsConfirmation) {
            log(LogLevel.WARNING, "${LogTag.DELETIONS_HELD}: " +
                "${deletionGate.blockedLocal} local, ${deletionGate.blockedRemote} remote")
        }
        if (isCancelled) {
            prefs.updateFolderSyncResult(pair.id, "Отменена", 0)
            return
        }

        var syncedCount = 0


        try {
            syncedCount = when (pair.mode) {
                SyncMode.UPLOAD_ONLY -> handleUploadOnly(pair, client, localFiles, remoteFiles)
                SyncMode.DOWNLOAD_ONLY -> handleDownloadOnly(pair, client, rootDoc, localFiles, remoteFiles)
                SyncMode.TWO_WAY -> handleTwoWay(pair, client, rootDoc, localFiles, remoteFiles, deletionGate)
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            log(LogLevel.WARNING, "Папка [" + pair.displayName + "] остановлена")
            prefs.updateFolderSyncResult(pair.id, "Отменена", 0)
            return
        } catch (e: Exception) {
            log(LogLevel.ERROR, "Ошибка в папке [" + pair.displayName + "]: " + (e.localizedMessage ?: e.message))
            prefs.updateFolderSyncResult(pair.id, "Ошибка синхронизации", 0)
            return
        }

        if (isCancelled) {
            prefs.updateFolderSyncResult(pair.id, "Прервана ($syncedCount обновлено)", syncedCount)
            return
        }
        if (deletionGate.needsConfirmation) {
            prefs.updateFolderSyncResult(pair.id, "Нужно подтверждение удалений ($syncedCount)", syncedCount)
            return
        }
        prefs.updateFolderSyncResult(pair.id, "Успешно (" + syncedCount + " обновлено)", syncedCount)
    }

    private suspend fun handleUploadOnly(
        pair: SyncFolderPair,
        client: YandexDiskClient,
        localFiles: Map<String, DocumentFile>,
        remoteFiles: Map<String, Resource>
    ): Int {
        var count = 0
        var idx = 0
        val total = localFiles.size
        val records = db.fileRecordDao().getRecordsForPair(pair.id).associateBy { it.relPath }

        for ((relPath, docFile) in localFiles) {
            if (isCancelled) break
            idx++
            val name = docFile.name.orEmpty()
            val localSize = docFile.length()
            val localMtime = docFile.lastModified()
            val record = records[relPath]

            val useCompression = pair.compressPhotos && PhotoCompressor.isCompressible(name)
            val sig = if (useCompression) "jpg-q${pair.effectiveQuality}-s${pair.maxImageSide}" else "original"

            // Fast path: size and mtime unchanged, same compression settings -> skip without hashing
            if (record != null && record.size == localSize && record.localMtime == localMtime &&
                record.compressionSig == sig
            ) {
                continue
            }

            val localMd5 = computeLocalMd5(docFile)

            // Content unchanged and already uploaded with the same settings -> skip
            if (record != null && localMd5.isNotEmpty() && record.localMd5 == localMd5 &&
                record.compressionSig == sig
            ) {
                db.fileRecordDao().insertOrUpdate(record.copy(localMtime = localMtime, size = localSize))
                continue
            }

            // Legacy fallback (no DB record): trust cloud files to avoid re-uploading on fresh install
            val expectedRemotePath = if (useCompression) {
                val uploadName = name.substringBeforeLast('.') + ".jpg"
                if (relPath.contains('/')) relPath.substringBeforeLast('/') + "/" + uploadName else uploadName
            } else {
                relPath
            }
            val remoteRes = remoteFiles[expectedRemotePath] ?: remoteFiles[relPath]

            if (record == null && remoteRes != null) {
                val remoteMd5 = remoteRes.md5.orEmpty()
                val alreadyUploaded = if (useCompression) {
                    // При сжатии размер в облаке меньше, но файл уже там есть
                    remoteRes.size > 0 && remoteRes.size <= localSize
                } else {
                    if (remoteMd5.isNotEmpty() && localMd5.isNotEmpty()) {
                        remoteMd5.equals(localMd5, ignoreCase = true)
                    } else {
                        remoteRes.size == localSize
                    }
                }

                if (alreadyUploaded) {
                    db.fileRecordDao().insertOrUpdate(
                        FileRecord(
                            id = "${pair.id}:$relPath",
                            pairId = pair.id,
                            relPath = relPath,
                            localMd5 = localMd5,
                            remoteMd5 = remoteMd5,
                            localMtime = localMtime,
                            remoteMtime = System.currentTimeMillis(),
                            size = localSize,
                            lastSyncTime = System.currentTimeMillis(),
                            compressionSig = sig
                        )
                    )
                    continue
                }
            }

            // Try compression (temp file in cacheDir, original on phone untouched)
            var tmp: File? = null
            if (useCompression) {
                _progressFlow.value = _progressFlow.value.copy(
                    currentFileName = name,
                    actionText = "\u0421\u0436\u0430\u0442\u0438\u0435 \u0444\u043e\u0442\u043e [$idx/$total]",
                    processedFiles = idx,
                    totalFiles = total
                )
                tmp = PhotoCompressor.compressToCache(
                    context, docFile.uri, name, localSize, pair.effectiveQuality, pair.maxImageSide
                )
            }
            val tmpFile = tmp

            // Compression not possible and original already uploaded before -> skip
            if (tmpFile == null && useCompression && record != null && localMd5.isNotEmpty() &&
                record.localMd5 == localMd5 && record.compressionSig == "original"
            ) {
                continue
            }

            try {
                val uploadRelPath: String
                val uploadName: String
                val uploadSize: Long
                val streamProvider: () -> InputStream
                if (tmpFile != null) {
                    uploadName = name.substringBeforeLast('.') + ".jpg"
                    uploadRelPath = if (relPath.contains('/')) {
                        relPath.substringBeforeLast('/') + "/" + uploadName
                    } else {
                        uploadName
                    }
                    uploadSize = tmpFile.length()
                    streamProvider = { FileInputStream(tmpFile) }
                } else {
                    uploadName = name
                    uploadRelPath = relPath
                    uploadSize = localSize
                    streamProvider = { context.contentResolver.openInputStream(docFile.uri)!! }
                }

                val targetRemotePath = "${pair.remotePath.trimEnd('/')}/$uploadRelPath"
                val dirPath = targetRemotePath.substringBeforeLast('/', "")
                if (dirPath.isNotEmpty()) {
                    client.createFolder(dirPath)
                }

                _progressFlow.value = _progressFlow.value.copy(
                    currentFileName = uploadName,
                    actionText = "\u0417\u0430\u0433\u0440\u0443\u0437\u043a\u0430 \u0432 \u043e\u0431\u043b\u0430\u043a\u043e [$idx/$total]",
                    currentBytes = 0,
                    totalBytes = uploadSize,
                    processedFiles = idx,
                    totalFiles = total
                )

                if (tmpFile != null) {
                    val saved = 100 - (uploadSize * 100 / localSize.coerceAtLeast(1))
                    log(LogLevel.INFO, "\u2b06 \u0417\u0430\u0433\u0440\u0443\u0437\u043a\u0430 (\u0441\u0436\u0430\u0442\u043e): $uploadRelPath ${formatBytes(localSize)} -> ${formatBytes(uploadSize)} (-$saved%)")
                } else {
                    log(LogLevel.INFO, "\u2b06 \u0417\u0430\u0433\u0440\u0443\u0437\u043a\u0430: $relPath (${formatBytes(localSize)})")
                }

                val success = client.uploadFile(
                    remotePath = targetRemotePath,
                    contentLength = uploadSize,
                    streamProvider = streamProvider,
                    overwrite = true,
                    onProgress = { written, tot ->
                        _progressFlow.value = _progressFlow.value.copy(
                            currentBytes = written,
                            totalBytes = tot
                        )
                    }
                )
                if (success) {
                    count++
                    val uploadedMd5 = if (tmpFile != null) computeFileMd5(tmpFile) else localMd5
                    db.fileRecordDao().insertOrUpdate(
                        FileRecord(
                            id = "${pair.id}:$relPath",
                            pairId = pair.id,
                            relPath = relPath,
                            localMd5 = localMd5,
                            remoteMd5 = uploadedMd5,
                            localMtime = localMtime,
                            remoteMtime = System.currentTimeMillis(),
                            size = localSize,
                            lastSyncTime = System.currentTimeMillis(),
                            compressionSig = if (tmpFile != null) sig else "original"
                        )
                    )
                }
            } finally {
                tmpFile?.delete()
            }
        }
        return count
    }

    private suspend fun handleDownloadOnly(
        pair: SyncFolderPair,
        client: YandexDiskClient,
        rootDoc: DocumentFile,
        localFiles: Map<String, DocumentFile>,
        remoteFiles: Map<String, Resource>
    ): Int {
        var count = 0
        var idx = 0
        val total = remoteFiles.size

        for ((relPath, res) in remoteFiles) {
            if (isCancelled) break
            idx++
            val localDoc = localFiles[relPath]
            val remoteSize = res.size
            val remoteMd5 = res.md5.orEmpty()

            val needsDownload = if (localDoc == null) {
                true
            } else {
                val localMd5 = computeLocalMd5(localDoc)
                if (remoteMd5.isNotEmpty() && localMd5.isNotEmpty()) {
                    !remoteMd5.equals(localMd5, ignoreCase = true)
                } else {
                    localDoc.length() != remoteSize
                }
            }

            if (needsDownload) {
                try {
                    val targetDoc = getOrCreateDocumentFile(rootDoc, relPath, res.name)
                    if (targetDoc != null) {
                        _progressFlow.value = _progressFlow.value.copy(
                            currentFileName = res.name,
                            actionText = "Скачивание из облака [$idx/$total]",
                            currentBytes = 0,
                            totalBytes = remoteSize,
                            processedFiles = idx,
                            totalFiles = total
                        )

                        log(LogLevel.INFO, "⬇ Скачивание: $relPath (${formatBytes(remoteSize)})")
                        val outputStream = context.contentResolver.openOutputStream(targetDoc.uri, "wt")
                        if (outputStream != null) {
                            val success = outputStream.use { out ->
                                client.downloadFile(
                                    remotePath = res.path.removePrefix("disk:"),
                                    outputStream = out,
                                    onProgress = { read, tot ->
                                        _progressFlow.value = _progressFlow.value.copy(
                                            currentBytes = read,
                                            totalBytes = tot
                                        )
                                    }
                                )
                            }
                            if (success) {
                                count++
                                val newLocalMd5 = computeLocalMd5(targetDoc)
                                db.fileRecordDao().insertOrUpdate(
                                    FileRecord(
                                        id = "${pair.id}:$relPath",
                                        pairId = pair.id,
                                        relPath = relPath,
                                        localMd5 = newLocalMd5,
                                        remoteMd5 = remoteMd5,
                                        localMtime = targetDoc.lastModified(),
                                        remoteMtime = System.currentTimeMillis(),
                                        size = remoteSize,
                                        lastSyncTime = System.currentTimeMillis()
                                    )
                                )
                            }
                        } else {
                            log(LogLevel.ERROR, "Не удалось открыть поток записи для файла: $relPath")
                        }
                    } else {
                        log(LogLevel.ERROR, "Не удалось создать целевой файл на устройстве: $relPath")
                    }
                } catch (e: Exception) {
                    log(LogLevel.ERROR, "Ошибка скачивания файла [$relPath]: ${e.localizedMessage ?: e.message}")
                }
            }
        }
        return count
    }

    private suspend fun handleTwoWay(
        pair: SyncFolderPair,
        client: YandexDiskClient,
        rootDoc: DocumentFile,
        localFiles: Map<String, DocumentFile>,
        remoteFiles: Map<String, Resource>,
        deletionGate: DeletionGate
    ): Int {
        var count = 0
        val allKeys = localFiles.keys + remoteFiles.keys
        val total = allKeys.size
        var idx = 0

        for (relPath in allKeys) {
            if (isCancelled) break
            idx++
            val localDoc = localFiles[relPath]
            val remoteRes = remoteFiles[relPath]
            val record = db.fileRecordDao().getRecord(pair.id, relPath)

            if (localDoc != null && remoteRes == null) {
                // Файл есть локально, но нет в облаке
                if (record != null && prefs.syncDeletions) {
                    // Файл был удален в облаке -> удаляем локально (если подтверждения нет)
                    if (!deletionGate.needsConfirmation) {
                        log(LogLevel.INFO, "🗑 Удаление локального файла (удален в облаке): $relPath")
                        localDoc.delete()
                        db.fileRecordDao().deleteRecord(pair.id, relPath)
                    } else {
                        log(LogLevel.INFO, "🗑 Удаление локального файла отложено (требуется подтверждение): $relPath")
                    }
                } else {
                    // Новый локальный файл -> загружаем в облако
                    val targetRemotePath = "${pair.remotePath.trimEnd('/')}/$relPath"
                    val dirPath = targetRemotePath.substringBeforeLast('/', "")
                    if (dirPath.isNotEmpty()) client.createFolder(dirPath)

                    val size = localDoc.length()
                    log(LogLevel.INFO, "⬆ Загрузка нового файла в облако: $relPath")
                    val success = client.uploadFile(
                        remotePath = targetRemotePath,
                        contentLength = size,
                        streamProvider = { context.contentResolver.openInputStream(localDoc.uri)!! },
                        overwrite = true
                    )
                    if (success) {
                        count++
                        val md5 = computeLocalMd5(localDoc)
                        db.fileRecordDao().insertOrUpdate(
                            FileRecord(
                                id = "${pair.id}:$relPath",
                                pairId = pair.id,
                                relPath = relPath,
                                localMd5 = md5,
                                remoteMd5 = md5,
                                localMtime = localDoc.lastModified(),
                                remoteMtime = System.currentTimeMillis(),
                                size = size,
                                lastSyncTime = System.currentTimeMillis()
                            )
                        )
                    }
                }
            } else if (localDoc == null && remoteRes != null) {
                // Файл есть в облаке, но нет локально
                if (record != null && prefs.syncDeletions) {
                    // Файл удалён локально -> удаляем в облаке (если подтверждения нет)
                    if (!deletionGate.needsConfirmation) {
                        log(LogLevel.INFO, "🗑 Удаление файла в облаке (удален на телефоне): $relPath")
                        client.deleteResource(remoteRes.path.removePrefix("disk:"))
                        db.fileRecordDao().deleteRecord(pair.id, relPath)
                    } else {
                        log(LogLevel.INFO, "🗑 Удаление файла в облаке отложено (требуется подтверждение): $relPath")
                    }
                } else {
                    // Новый файл в облаке -> скачиваем
                    try {
                        log(LogLevel.INFO, "⬇ Скачивание нового файла из облака: $relPath")
                        val targetDoc = getOrCreateDocumentFile(rootDoc, relPath, remoteRes.name)
                        if (targetDoc != null) {
                            val outputStream = context.contentResolver.openOutputStream(targetDoc.uri, "wt")
                            if (outputStream != null) {
                                val success = outputStream.use { out ->
                                    client.downloadFile(
                                        remotePath = remoteRes.path.removePrefix("disk:"),
                                        outputStream = out
                                    )
                                }
                                if (success) {
                                    count++
                                    val md5 = computeLocalMd5(targetDoc)
                                    db.fileRecordDao().insertOrUpdate(
                                        FileRecord(
                                            id = "${pair.id}:$relPath",
                                            pairId = pair.id,
                                            relPath = relPath,
                                            localMd5 = md5,
                                            remoteMd5 = remoteRes.md5.orEmpty(),
                                            localMtime = targetDoc.lastModified(),
                                            remoteMtime = System.currentTimeMillis(),
                                            size = remoteRes.size,
                                            lastSyncTime = System.currentTimeMillis()
                                        )
                                    )
                                }
                            } else {
                                log(LogLevel.ERROR, "Не удалось открыть поток записи для файла: $relPath")
                            }
                        } else {
                            log(LogLevel.ERROR, "Не удалось создать целевой файл на устройстве: $relPath")
                        }
                    } catch (e: Exception) {
                        log(LogLevel.ERROR, "Ошибка скачивания файла [$relPath]: ${e.localizedMessage ?: e.message}")
                    }
                }
            } else if (localDoc != null && remoteRes != null) {
                // Файл есть на обеих сторонах -> проверяем хэши
                val localMd5 = computeLocalMd5(localDoc)
                val remoteMd5 = remoteRes.md5.orEmpty()

                val isSame = if (localMd5.isNotEmpty() && remoteMd5.isNotEmpty()) {
                    localMd5.equals(remoteMd5, ignoreCase = true)
                } else {
                    localDoc.length() == remoteRes.size
                }

                if (!isSame) {
                    // Конфликт или изменение
                    val targetRemotePath = "${pair.remotePath.trimEnd('/')}/$relPath"
                    log(LogLevel.INFO, "🔄 Обновление файла в облаке: $relPath")
                    val success = client.uploadFile(
                        remotePath = targetRemotePath,
                        contentLength = localDoc.length(),
                        streamProvider = { context.contentResolver.openInputStream(localDoc.uri)!! },
                        overwrite = true
                    )
                    if (success) {
                        count++
                        db.fileRecordDao().insertOrUpdate(
                            FileRecord(
                                id = "${pair.id}:$relPath",
                                pairId = pair.id,
                                relPath = relPath,
                                localMd5 = localMd5,
                                remoteMd5 = localMd5,
                                localMtime = localDoc.lastModified(),
                                remoteMtime = System.currentTimeMillis(),
                                size = localDoc.length(),
                                lastSyncTime = System.currentTimeMillis()
                            )
                        )
                    }
                }
            }
        }

        return count
    }

    private fun scanLocalTreeRecursive(
        currentDir: DocumentFile,
        currentRelPath: String,
        result: MutableMap<String, DocumentFile>
    ) {
        val files = currentDir.listFiles()
        for (f in files) {
            val name = f.name ?: continue
            if (isIgnored(name)) continue

            val rel = if (currentRelPath.isEmpty()) name else "$currentRelPath/$name"
            if (f.isDirectory) {
                scanLocalTreeRecursive(f, rel, result)
            } else {
                result[rel] = f
            }
        }
    }

    private fun getOrCreateDocumentFile(
        rootDoc: DocumentFile,
        relPath: String,
        fileName: String
    ): DocumentFile? {
        val parts = relPath.split("/").filter { it.isNotEmpty() }
        var current = rootDoc
        for (i in 0 until parts.size - 1) {
            val dirName = parts[i]
            var subDir = current.findFile(dirName)
            if (subDir == null || !subDir.isDirectory) {
                subDir = current.createDirectory(dirName)
            }
            if (subDir == null) return null
            current = subDir
        }

        val existing = current.findFile(fileName)
        if (existing != null && existing.isFile) {
            return existing
        }
        val ext = fileName.substringAfterLast('.', "").lowercase()
        val mimeType = if (ext.isNotEmpty()) {
            MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "application/octet-stream"
        } else {
            "application/octet-stream"
        }
        return current.createFile(mimeType, fileName)
    }

    private fun computeLocalMd5(docFile: DocumentFile): String =
        computeHash(context.contentResolver.openInputStream(docFile.uri), DIGEST_MD5)

    private fun computeFileMd5(file: File): String {
        val stream = runCatching { FileInputStream(file) }.getOrNull() ?: return ""
        return computeHash(stream, DIGEST_MD5)
    }

    private fun computeHash(stream: InputStream?, algorithm: String): String {
        if (stream == null) return ""
        return try {
            stream.use { input -> hashStream(input, algorithm) }
        } catch (e: Exception) {
            log(LogLevel.WARNING, LogTag.HASH_FAILED + " " + algorithm)
            ""
        }
    }

    private fun hashStream(input: InputStream, algorithm: String): String {
        val digest = MessageDigest.getInstance(algorithm)
        val buffer = ByteArray(65536)
        var read: Int
        while (input.read(buffer).also { read = it } != -1) {
            digest.update(buffer, 0, read)
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun isIgnored(name: String): Boolean {
        val ignoredNames = setOf(
            ".git", ".svn", "__pycache__", ".idea", ".vscode",
            "desktop.ini", "thumbs.db", ".ds_store", "\$recycle.bin",
            ".nomedia"
        )
        val ignoredExts = setOf(
            ".tmp", ".temp", ".crdownload", ".part", ".partial",
            ".download", ".swp", ".swo", ".lock", ".bak"
        )
        if (name in ignoredNames) return true
        val ext = "." + name.substringAfterLast('.', "")
        return ext.lowercase() in ignoredExts
    }

    private fun formatBytes(bytes: Long): String {
        return when {
            bytes < 1024 -> "$bytes Б"
            bytes < 1024 * 1024 -> String.format("%.1f КБ", bytes / 1024.0)
            bytes < 1024 * 1024 * 1024 -> String.format("%.1f МБ", bytes / (1024.0 * 1024.0))
            else -> String.format("%.2f ГБ", bytes / (1024.0 * 1024.0 * 1024.0))
        }
    }
}

