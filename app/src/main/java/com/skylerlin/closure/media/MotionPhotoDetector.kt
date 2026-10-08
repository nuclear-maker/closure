package com.skylerlin.closure.media

import android.content.ContentResolver
import android.content.Context
import com.skylerlin.closure.data.MediaItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream

/**
 * Android 的「实况照片」= Motion Photo：JPEG 尾部内嵌了一段短视频。
 * 厂商格式碎片化，这里覆盖主流两类：
 * - Google v1（小米/老 Pixel 等）：XMP 带 GCamera:MicroVideo=1 + MicroVideoOffset（视频距文件末尾的字节数）
 * - Google v2 / 三星：XMP 带 GCamera:MotionPhoto 等，无偏移 → 在文件尾部扫描 mp4 的 ftyp 头
 */
object MotionPhotoDetector {

    private const val OFFSET_KEY = "GCamera:MicroVideoOffset=\""
    private val MOTION_MARKERS = arrayOf(
        "GCamera:MicroVideo=\"1\"",
        "GCamera:MotionPhoto=\"1\"",
        "<GCamera:MotionPhoto>",
        "MicroVideo=\"1\"",
        "MotionPhoto=\"Version\"",
    )

    data class Info(
        /** 视频距文件末尾的字节数；-1 表示未给出，需要扫描尾部找 ftyp */
        val offsetFromEnd: Long,
    )

    suspend fun detect(resolver: ContentResolver, item: MediaItem): Info? =
        withContext(Dispatchers.IO) {
            if (item.isVideo) return@withContext null
            try {
                resolver.openInputStream(item.uri)?.use { input ->
                    val head = input.readUpTo(256 * 1024)
                    val text = String(head, Charsets.ISO_8859_1)
                    if (MOTION_MARKERS.none { text.contains(it) }) return@withContext null
                    val idx = text.indexOf(OFFSET_KEY)
                    val offset = if (idx >= 0) {
                        val start = idx + OFFSET_KEY.length
                        val end = text.indexOf('"', start)
                        if (end > start) text.substring(start, end).toLongOrNull() ?: -1L else -1L
                    } else -1L
                    Info(offset)
                }
            } catch (e: Exception) {
                null
            }
        }

    /** 把内嵌视频抽到缓存目录，返回 null 表示失败 */
    suspend fun extract(context: Context, resolver: ContentResolver, item: MediaItem, info: Info): File? =
        withContext(Dispatchers.IO) {
            val dir = File(context.cacheDir, "motion").apply { mkdirs() }
            val out = File(dir, "motion_${item.id}.mp4")
            if (out.exists() && out.length() > 0) return@withContext out

            val tmp = File(dir, ".tmp_${item.id}")
            try {
                resolver.openInputStream(item.uri)?.use { input ->
                    val tailLen = minOf(item.sizeBytes, 32L * 1024 * 1024)
                    input.skipFully(item.sizeBytes - tailLen)
                    val tail = input.readBytes()
                    // 主策略：扫描最后一个合法的 ftyp box（含 4 字节长度头）——这是内嵌完整
                    // MP4 的真实起点，各厂商通用。不能直接从 "ftyp" 字符串处截取：缺了长度
                    // 头会令首 box 长度字段错位，部分设备解析失败表现为黑屏。offset 作回退。
                    val start = findMp4Start(tail)
                        ?: info.offsetFromEnd.takeIf { it > 0 }
                            ?.let { off -> (tail.size - off).toInt() }
                            ?.takeIf { s -> s >= 4 && s + 8 <= tail.size && be32(tail, s) >= 8 }
                        ?: return@withContext null
                    tmp.outputStream().use { it.write(tail, start, tail.size - start) }
                } ?: return@withContext null
                if (!tmp.renameTo(out)) {
                    tmp.copyTo(out, overwrite = true)
                    tmp.delete()
                }
                out
            } catch (e: Exception) {
                tmp.delete()
                null
            }
        }

    /** 找内嵌 MP4 的真实 box 起点（含 4 字节长度头）。从尾部往前找 "ftyp"，且长度头必须合理（ftyp box 很小）。 */
    private fun findMp4Start(tail: ByteArray): Int {
        var i = tail.size - 4
        while (i >= 4) {
            if (tail[i] == 0x66.toByte() && tail[i + 1] == 0x74.toByte() &&
                tail[i + 2] == 0x79.toByte() && tail[i + 3] == 0x70.toByte()
            ) {
                val start = i - 4
                val boxSize = be32(tail, start)
                if (boxSize >= 8 && boxSize <= (1 shl 20)) return start
            }
            i--
        }
        return -1
    }

    private fun be32(data: ByteArray, offset: Int): Int =
        ((data[offset].toInt() and 0xFF) shl 24) or
            ((data[offset + 1].toInt() and 0xFF) shl 16) or
            ((data[offset + 2].toInt() and 0xFF) shl 8) or
            (data[offset + 3].toInt() and 0xFF)

    private fun InputStream.readUpTo(max: Int): ByteArray {
        val buf = ByteArrayOutputStream()
        val chunk = ByteArray(64 * 1024)
        var total = 0
        while (total < max) {
            val n = read(chunk, 0, minOf(chunk.size, max - total))
            if (n <= 0) break
            buf.write(chunk, 0, n)
            total += n
        }
        return buf.toByteArray()
    }

    private fun InputStream.skipFully(n: Long) {
        var remaining = n
        val buf = ByteArray(64 * 1024)
        while (remaining > 0) {
            val r = read(buf, 0, minOf(buf.size.toLong(), remaining).toInt())
            if (r <= 0) break
            remaining -= r
        }
    }
}
