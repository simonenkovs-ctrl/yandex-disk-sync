package ru.simonenkov.yandexdisksync.api

import com.google.gson.annotations.SerializedName

data class DiskInfo(
    @SerializedName("total_space") val totalSpace: Long = 0,
    @SerializedName("used_space") val usedSpace: Long = 0,
    @SerializedName("trash_size") val trashSize: Long = 0,
    @SerializedName("user") val user: DiskUser? = null
)

data class DiskUser(
    @SerializedName("uid") val uid: String? = null,
    @SerializedName("login") val login: String? = null,
    @SerializedName("display_name") val displayName: String? = null
)

data class Resource(
    @SerializedName("name") val name: String = "",
    @SerializedName("path") val path: String = "",
    @SerializedName("type") val type: String = "file", // "dir" or "file"
    @SerializedName("size") val size: Long = 0,
    @SerializedName("md5") val md5: String? = null,
    @SerializedName("sha256") val sha256: String? = null,
    @SerializedName("modified") val modified: String? = null,
    @SerializedName("created") val created: String? = null,
    @SerializedName("mime_type") val mimeType: String? = null,
    @SerializedName("_embedded") val embedded: ResourceList? = null
) {
    val isDir: Boolean get() = type == "dir"
}

data class ResourceList(
    @SerializedName("items") val items: List<Resource> = emptyList(),
    @SerializedName("limit") val limit: Int = 0,
    @SerializedName("offset") val offset: Int = 0,
    @SerializedName("total") val total: Int = 0
)

data class LinkResponse(
    @SerializedName("href") val href: String = "",
    @SerializedName("method") val method: String = "GET",
    @SerializedName("templated") val templated: Boolean = false
)

data class ApiError(
    @SerializedName("message") val message: String = "",
    @SerializedName("description") val description: String = "",
    @SerializedName("error") val error: String = ""
)
