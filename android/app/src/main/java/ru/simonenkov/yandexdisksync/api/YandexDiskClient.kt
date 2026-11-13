package ru.simonenkov.yandexdisksync.api

import com.google.gson.Gson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import okio.BufferedSink
import java.io.InputStream
import java.io.OutputStream
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

class YandexDiskApiException(message: String, val statusCode: Int? = null) : Exception(message)

class YandexDiskClient(private var token: String) {

    private val gson = Gson()
    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()

    companion object {
        private const val BASE_URL = "https://cloud-api.yandex.net/v1/disk"
    }

    fun updateToken(newToken: String) {
        this.token = newToken.trim()
    }

    private fun authHeaders(): Headers {
        if (token.isBlank()) {
            throw YandexDiskApiException("OAuth-токен не указан.")
        }
        return Headers.Builder()
            .add("Authorization", "OAuth $token")
            .add("User-Agent", "YandexDiskSyncAndroid/1.2.2")
            .add("Accept", "application/json")
            .build()
    }

    private suspend fun <T> executeWithRetry(
        requestBuilder: () -> Request,
        parser: (Response) -> T
    ): T = withContext(Dispatchers.IO) {
        val maxRetries = 3
        var backoffMs = 1000L

        for (attempt in 0 until maxRetries) {
            val request = requestBuilder()
            try {
                val response = client.newCall(request).execute()
                if (response.isSuccessful) {
                    return@withContext parser(response)
                }

                val code = response.code
                val body = response.body?.string().orEmpty()
                val errorMsg = try {
                    gson.fromJson(body, ApiError::class.java)?.message ?: body
                } catch (e: Exception) {
                    body.ifBlank { "HTTP $code" }
                }

                if ((code == 429 || code in 500..504) && attempt < maxRetries - 1) {
                    val retryAfter = response.header("Retry-After")?.toLongOrNull() ?: (backoffMs / 1000)
                    delay(retryAfter.coerceIn(1, 30) * 1000L)
                    backoffMs = (backoffMs * 2).coerceAtMost(30000L)
                    continue
                }

                throw YandexDiskApiException("Ошибка API ($code): $errorMsg", code)
            } catch (e: YandexDiskApiException) {
                throw e
            } catch (e: Exception) {
                if (attempt < maxRetries - 1) {
                    delay(backoffMs)
                    backoffMs = (backoffMs * 2).coerceAtMost(30000L)
                    continue
                }
                throw YandexDiskApiException("Сетевая ошибка: ${e.localizedMessage ?: e.message}")
            }
        }
        throw YandexDiskApiException("Превышено количество попыток запроса")
    }

    suspend fun getDiskInfo(): DiskInfo {
        return executeWithRetry({
            Request.Builder()
                .url("$BASE_URL/")
                .headers(authHeaders())
                .get()
                .build()
        }) { response ->
            val json = response.body?.string().orEmpty()
            gson.fromJson(json, DiskInfo::class.java)
        }
    }

    suspend fun getResource(path: String, limit: Int = 20, offset: Int = 0): Resource {
        val encoded = URLEncoder.encode(path, "UTF-8")
        val url = "$BASE_URL/resources?path=$encoded&limit=$limit&offset=$offset"
        return executeWithRetry({
            Request.Builder()
                .url(url)
                .headers(authHeaders())
                .get()
                .build()
        }) { response ->
            val json = response.body?.string().orEmpty()
            gson.fromJson(json, Resource::class.java)
        }
    }

    suspend fun listFolderRecursive(rootPath: String): List<Resource> = withContext(Dispatchers.IO) {
        val result = mutableListOf<Resource>()
        val queue = ArrayDeque<String>()
        queue.add(rootPath)

        while (queue.isNotEmpty()) {
            val currentPath = queue.removeFirst()
            var offset = 0
            val limit = 100
            var hasMore = true

            while (hasMore) {
                val res = try {
                    getResource(currentPath, limit = limit, offset = offset)
                } catch (e: YandexDiskApiException) {
                    if (e.statusCode == 404) {
                        hasMore = false
                        break
                    }
                    throw e
                }

                val items = res.embedded?.items.orEmpty()
                for (item in items) {
                    result.add(item)
                    if (item.isDir) {
                        queue.add(item.path.removePrefix("disk:"))
                    }
                }

                val total = res.embedded?.total ?: 0
                offset += items.size
                hasMore = items.isNotEmpty() && offset < total
            }
        }
        result
    }

    suspend fun createFolder(path: String): Boolean = withContext(Dispatchers.IO) {
        val cleanPath = path.trim().trimEnd('/')
        if (cleanPath.isEmpty() || cleanPath == "/") return@withContext true

        val parts = cleanPath.split("/").filter { it.isNotEmpty() }
        var current = ""
        for (part in parts) {
            current += "/$part"
            val encoded = URLEncoder.encode(current, "UTF-8")
            val url = "$BASE_URL/resources?path=$encoded"
            try {
                executeWithRetry({
                    Request.Builder()
                        .url(url)
                        .headers(authHeaders())
                        .put("".toRequestBody("application/json".toMediaType()))
                        .build()
                }) { true }
            } catch (e: YandexDiskApiException) {
                if (e.statusCode == 409) {
                    // Папка уже существует - продолжаем
                    continue
                }
                throw e
            }
        }
        true
    }

    suspend fun uploadFile(
        remotePath: String,
        contentLength: Long,
        streamProvider: () -> InputStream,
        overwrite: Boolean = true,
        onProgress: ((bytesWritten: Long, totalBytes: Long) -> Unit)? = null
    ): Boolean = withContext(Dispatchers.IO) {
        val encoded = URLEncoder.encode(remotePath, "UTF-8")
        val uploadUrlApi = "$BASE_URL/resources/upload?path=$encoded&overwrite=$overwrite"

        val link = executeWithRetry({
            Request.Builder()
                .url(uploadUrlApi)
                .headers(authHeaders())
                .get()
                .build()
        }) { response ->
            val json = response.body?.string().orEmpty()
            gson.fromJson(json, LinkResponse::class.java)
        }

        val requestBody = object : RequestBody() {
            override fun contentType() = "application/octet-stream".toMediaType()
            override fun contentLength() = contentLength
            override fun writeTo(sink: BufferedSink) {
                streamProvider().use { input ->
                    val buffer = ByteArray(65536)
                    var bytesWritten = 0L
                    var read: Int
                    while (input.read(buffer).also { read = it } != -1) {
                        sink.write(buffer, 0, read)
                        bytesWritten += read
                        onProgress?.invoke(bytesWritten, contentLength)
                    }
                }
            }
        }

        val uploadReq = Request.Builder()
            .url(link.href)
            .put(requestBody)
            .build()

        client.newCall(uploadReq).execute().use { response ->
            if (!response.isSuccessful && response.code != 201 && response.code != 202) {
                throw YandexDiskApiException("Ошибка загрузки файла (${response.code})", response.code)
            }
        }
        true
    }

    suspend fun downloadFile(
        remotePath: String,
        outputStream: OutputStream,
        onProgress: ((bytesRead: Long, totalBytes: Long) -> Unit)? = null
    ): Boolean = withContext(Dispatchers.IO) {
        val encoded = URLEncoder.encode(remotePath, "UTF-8")
        val downloadUrlApi = "$BASE_URL/resources/download?path=$encoded"

        val link = executeWithRetry({
            Request.Builder()
                .url(downloadUrlApi)
                .headers(authHeaders())
                .get()
                .build()
        }) { response ->
            val json = response.body?.string().orEmpty()
            gson.fromJson(json, LinkResponse::class.java)
        }

        val req = Request.Builder().url(link.href).get().build()
        client.newCall(req).execute().use { response ->
            if (!response.isSuccessful) {
                throw YandexDiskApiException("Ошибка скачивания файла (${response.code})", response.code)
            }
            val body = response.body ?: throw YandexDiskApiException("Пустое тело ответа при скачивании")
            val totalBytes = body.contentLength()
            body.byteStream().use { input ->
                val buffer = ByteArray(65536)
                var bytesRead = 0L
                var read: Int
                while (input.read(buffer).also { read = it } != -1) {
                    outputStream.write(buffer, 0, read)
                    bytesRead += read
                    onProgress?.invoke(bytesRead, totalBytes)
                }
                outputStream.flush()
            }
        }
        true
    }

    suspend fun deleteResource(remotePath: String, permanently: Boolean = false): Boolean = withContext(Dispatchers.IO) {
        val encoded = URLEncoder.encode(remotePath, "UTF-8")
        val url = "$BASE_URL/resources?path=$encoded&permanently=$permanently"
        executeWithRetry({
            Request.Builder()
                .url(url)
                .headers(authHeaders())
                .delete()
                .build()
        }) { response ->
            response.isSuccessful || response.code == 204 || response.code == 202
        }
    }
}
