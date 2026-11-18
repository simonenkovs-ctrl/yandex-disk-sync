package ru.simonenkov.yandexdisksync.engine

data class Md5Result(val hash: String? = null, val error: String? = null) {
    val isOk: Boolean get() = hash != null && error == null
    val isError: Boolean get() = error != null
}

object FileCompare {

    fun sameContent(localMd5: String?, remoteMd5: String?, localSize: Long, remoteSize: Long): Boolean {
        return if (!localMd5.isNullOrEmpty() && !remoteMd5.isNullOrEmpty()) {
            localMd5.equals(remoteMd5, ignoreCase = true)
        } else {
            localSize == remoteSize
        }
    }

    fun remotePathFor(pairRemotePath: String, relPath: String): String {
        return pairRemotePath.trim().trimEnd('/') + "/" + relPath
    }
}

object CompressionSig {
    fun of(pairCompressPhotos: Boolean, quality: Int, maxSide: Int): String {
        return if (pairCompressPhotos) "jpg-q$quality-s$maxSide" else "original"
    }

    fun isCompressed(sig: String?): Boolean {
        return sig != null && sig.startsWith("jpg-")
    }
}

enum class LocalDeletionKind { DELETE, SKIP_ALREADY_UPSTREAM, SKIP_NOT_UPSTREAM }

object TwoWayPolicy {

    fun localDeletion(
        syncDeletions: Boolean,
        hasRecord: Boolean,
        recordCompressionSig: String?,
        photoCompressionEnabled: Boolean,
        photoCompressible: Boolean
    ): LocalDeletionKind {
        if (!syncDeletions || !hasRecord) return LocalDeletionKind.SKIP_NOT_UPSTREAM
        val thisRunCompressed = photoCompressionEnabled && photoCompressible
        val wasCompressed = CompressionSig.isCompressed(recordCompressionSig)
        return when {
            thisRunCompressed && wasCompressed -> LocalDeletionKind.DELETE
            thisRunCompressed && !wasCompressed -> LocalDeletionKind.SKIP_ALREADY_UPSTREAM
            else -> LocalDeletionKind.DELETE
        }
    }

    fun remoteDeletion(
        syncDeletions: Boolean,
        hasRecord: Boolean,
        recordCompressionSig: String?,
        photoCompressionEnabled: Boolean
    ): Boolean {
        return syncDeletions && hasRecord && photoCompressionEnabled &&
            !CompressionSig.isCompressed(recordCompressionSig)
    }
}

object UploadDecision {
    fun alreadyUploaded(
        useCompression: Boolean,
        localSize: Long,
        localMd5: String?,
        remoteSize: Long,
        remoteMd5: String?
    ): Boolean {
        if (localSize <= 0L) return false
        if (useCompression) return remoteSize > 0L && remoteSize <= localSize
        val remote = remoteMd5.orEmpty()
        val local = localMd5.orEmpty()
        return if (remote.isNotEmpty() && local.isNotEmpty()) {
            remote.equals(local, ignoreCase = true)
        } else {
            remoteSize == localSize
        }
    }
}

data class DeletionOutcome(val keys: Set<String>, val blocked: Boolean, val exceedsThreshold: Boolean)

/**
 * Gate controlling whether deletions should be performed.
 * If any of the counters is > 0, `needsConfirmation` will be true and the engine
 * will log a warning and wait for user confirmation before actually deleting.
 */
data class DeletionGate(
    val blockedLocal: Int,
    val blockedRemote: Int
) {
    val needsConfirmation: Boolean
        get() = blockedLocal > 0 || blockedRemote > 0

    companion object {
        fun allowAll() = DeletionGate(0, 0)
    }
}

object DeletionPlanner {
    const val MAX_FILES = 20
    private const val MAX_PERCENT = 50

    fun plan(
        candidates: Set<String>,
        knownKeys: Set<String>,
        baselineEstablished: Boolean
    ): DeletionOutcome {
        if (candidates.isEmpty()) return DeletionOutcome(emptySet(), false, false)
        if (!baselineEstablished) return DeletionOutcome(emptySet(), true, false)
        val exceeds = candidates.size > MAX_FILES ||
            candidates.size * 100L > knownKeys.size * MAX_PERCENT
        return if (exceeds) {
            DeletionOutcome(emptySet(), true, true)
        } else {
            DeletionOutcome(candidates, false, false)
        }
    }
}
