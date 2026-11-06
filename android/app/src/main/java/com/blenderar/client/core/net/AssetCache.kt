package com.blenderar.client.core.net

import android.content.Context
import com.blenderar.client.core.protocol.AssetFileEntry
import com.blenderar.client.core.protocol.EffectManifestEntry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okio.Buffer
import okio.HashingSink
import okio.buffer
import okio.sink
import okio.source
import java.io.File
import java.security.MessageDigest

/**
 * 特效资源本地缓存。
 *
 * 目录布局:
 * ```
 * filesDir/effects/
 * ├── magic_fire_001/
 * │   ├── v1/                ← 一个版本一个目录(新版本先完整下载再切换)
 * │   │   ├── models/core.glb
 * │   │   └── ...
 * │   └── current            ← 指针文件,内容为 "v1"(原子 rename 切换)
 * ```
 *
 * 安全切换:新版本目录完整(全部文件 SHA-256 校验通过)后,才原子更新指针;
 * 渲染层在下一帧边界读指针并加载,旧版本目录在渲染层释放 GPU 资源后由
 * [pruneOldVersions] 删除。
 */
class AssetCache(private val appContext: Context) {

    private val http: OkHttpClient = OkHttpClient.Builder().build()

    val root: File get() = File(appContext.filesDir, "effects")

    fun dirFor(effectId: String, version: Int): File = File(root, "$effectId/v$version")

    fun currentVersion(effectId: String): Int? = runCatching {
        File(root, "$effectId/current").readText().trim().removePrefix("v").toIntOrNull()
    }.getOrNull()

    /** 版本目录内资源文件的绝对路径(不检查存在性) */
    fun pathFor(effectId: String, version: Int, relativePath: String): File =
        File(dirFor(effectId, version), safeRelativePath(relativePath))

    /**
     * 按清单同步缓存:缺失/校验失败的文件重新下载;全部就绪后切换指针。
     *
     * 注意:[EffectManifestEntry.files] 的 path 必须已是完整 URL
     * ([com.blenderar.client.core.repo.EffectRepository] 负责拼接 base)。
     *
     * @return 已完成版本切换的 effectId 列表
     */
    suspend fun syncToManifest(
        entries: List<EffectManifestEntry>,
        pairingCode: String,
        onProgress: (effectId: String, done: Int, total: Int) -> Unit = { _, _, _ -> },
    ): List<String> = withContext(Dispatchers.IO) {
        val switched = mutableListOf<String>()
        for (entry in entries) {
            val targetVersion = entry.version
            if (currentVersion(entry.id) == targetVersion && isVersionComplete(entry, targetVersion)) continue
            val dir = dirFor(entry.id, targetVersion)
            dir.deleteRecursively()
            dir.mkdirs()
            var ok = true
            for ((index, file) in entry.files.withIndex()) {
                onProgress(entry.id, index, entry.files.size)
                val dest = File(dir, safeRelativePath(file.path))
                dest.parentFile?.mkdirs()
                val result = downloadFile(file, dest, pairingCode)
                if (result.isFailure) {
                    ok = false
                    break
                }
            }
            onProgress(entry.id, entry.files.size, entry.files.size)
            if (ok) {
                switchPointer(effectId = entry.id, version = targetVersion)
                switched.add(entry.id)
            } else {
                // 下载失败:目录清理(下次重试),指针不动 → 旧版本继续可用
                dir.deleteRecursively()
            }
        }
        switched
    }

    /** 下载单个文件并校验 SHA-256(流式写 .tmp,校验通过后原子 rename) */
    suspend fun downloadFile(file: AssetFileEntry, dest: File, pairingCode: String): Result<File> =
        withContext(Dispatchers.IO) {
            runCatching {
                dest.parentFile?.mkdirs()
                val tmp = File(dest.parentFile, dest.name + ".tmp")
                val request = Request.Builder()
                    .url(file.path)
                    .header("X-Pairing-Code", pairingCode)
                    .build()
                http.newCall(request).execute().use { response ->
                    check(response.isSuccessful) { "HTTP ${response.code} 下载失败: ${file.path}" }
                    val source = response.body.byteStream().source().buffer()
                    val tmpSink = tmp.sink()
                    val hashing = HashingSink.sha256(tmpSink)
                    val bufferedHashing = hashing.buffer()
                    tmpSink.use { _ ->
                        bufferedHashing.use { out ->
                            source.use { input ->
                                val buffer = Buffer()
                                while (input.read(buffer, 64L * 1024L) != -1L) {
                                    out.write(buffer, buffer.size)
                                }
                            }
                            out.flush()
                        }
                    }
                    val actual = hashing.hash.hex()
                    check(file.sha256.isBlank() || actual == file.sha256.lowercase()) {
                        "SHA-256 不匹配: ${file.path} 期望 ${file.sha256} 实际 $actual"
                    }
                    if (dest.exists()) dest.delete()
                    check(tmp.renameTo(dest)) { "rename 失败: ${tmp.absolutePath}" }
                }
                dest
            }
        }

    // ── 内置兜底资源(APK assets → 缓存,离线可用) ──

    /**
     * 将 APK assets/bundled/<effectId>/(含 definition.json 与资源)复制进缓存目录。
     * 幂等:目标版本目录已存在则跳过。
     * @return 完成种子复制的 effectId 列表
     */
    fun seedBundled(effectIds: List<String>): List<String> {
        val seeded = mutableListOf<String>()
        for (id in effectIds) {
            runCatching {
                val defText = appContext.assets.open("bundled/$id/definition.json").bufferedReader().use { it.readText() }
                val version = Regex("\"version\"\\s*:\\s*(\\d+)").find(defText)?.groupValues?.get(1)?.toIntOrNull() ?: 1
                if (currentVersion(id) == version && dirFor(id, version).list().isNotEmpty()) return@runCatching
                val dir = dirFor(id, version)
                dir.deleteRecursively()
                copyAssetDir("bundled/$id", dir)
                switchPointer(effectId = id, version = version)
                seeded.add(id)
            }
        }
        return seeded
    }

    /** 读取缓存内 definition.json(指针版本);无则 null */
    fun readDefinition(effectId: String): String? = runCatching {
        val version = currentVersion(effectId) ?: return@runCatching null
        val f = File(dirFor(effectId, version), "definition.json")
        if (f.exists()) f.readText() else null
    }.getOrNull()

    /** 删除旧版本目录(新版本已切换且渲染层已释放资源后调用) */
    fun pruneOldVersions(effectId: String, keepVersion: Int) {
        val base = File(root, effectId)
        base.listFiles()?.forEach { f ->
            if (f.isDirectory && f.name.startsWith("v") && f.name.removePrefix("v").toIntOrNull() != keepVersion) {
                f.deleteRecursively()
            }
        }
    }

    // ────────────────────────── 内部 ──────────────────────────

    private fun switchPointer(effectId: String, version: Int) {
        val base = File(root, effectId)
        base.mkdirs()
        val tmp = File(base, ".current.tmp")
        tmp.writeText("v$version")
        val pointer = File(base, "current")
        if (pointer.exists()) pointer.delete()
        check(tmp.renameTo(pointer)) { "指针切换失败: $effectId v$version" }
    }

    private fun isVersionComplete(entry: EffectManifestEntry, version: Int): Boolean {
        val dir = dirFor(entry.id, version)
        if (!dir.isDirectory) return false
        return entry.files.all { f ->
            val target = File(dir, safeRelativePath(f.path))
            target.exists() && (f.sha256.isBlank() || target.sha256Hex() == f.sha256.lowercase())
        }
    }

    private fun copyAssetDir(assetPath: String, destDir: File) {
        val children = appContext.assets.list(assetPath).orEmpty()
        if (children.isEmpty()) {
            destDir.mkdirs()
            val dest = File(destDir, assetPath.substringAfterLast('/'))
            appContext.assets.open(assetPath).use { input -> dest.outputStream().use { input.copyTo(it) } }
            return
        }
        destDir.mkdirs()
        for (child in children) {
            copyAssetDir("$assetPath/$child", File(destDir, child))
        }
    }

    /** 防路径穿越:拒绝 ".." 与空路径 */
    private fun safeRelativePath(path: String): String {
        val cleaned = path.trim().trimStart('/').split('/')
            .filter { it.isNotEmpty() && it != ".." && it != "." }
            .joinToString("/")
        check(cleaned.isNotEmpty()) { "非法资源路径: $path" }
        return cleaned
    }

    private fun File.sha256Hex(): String {
        val digest = MessageDigest.getInstance("SHA-256")
        inputStream().use { input ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n <= 0) break
                digest.update(buf, 0, n)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
