package com.retrohome.launcher.ui

import android.content.Context
import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import com.retrohome.launcher.data.UrlTransport
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest

/** Картинки из интернета (значки достижений): в памяти, на диске, иначе загрузка. */
interface RemoteImageLoader {
    fun peek(url: String): ImageBitmap?
    /** Блокирующая загрузка (вызывается не в главном потоке). */
    fun load(url: String): ImageBitmap?
}

/** Подмена в тестах. null — обычный загрузчик с кэшем на диске. */
val LocalRemoteImages = staticCompositionLocalOf<RemoteImageLoader?> { null }

class DiskImageLoader private constructor(private val dir: File) : RemoteImageLoader {
    private val memory = LruCache<String, ImageBitmap>(240)

    private fun file(url: String): File {
        val h = MessageDigest.getInstance("MD5").digest(url.toByteArray()).joinToString("") { "%02x".format(it) }
        return File(dir, "$h.png")
    }

    override fun peek(url: String): ImageBitmap? = memory.get(url)

    override fun load(url: String): ImageBitmap? {
        memory.get(url)?.let { return it }
        val f = file(url)
        val bytes = if (f.exists()) f.readBytes() else {
            val r = UrlTransport.open(url, "image/*", "RetroHome (personal Android game launcher)")
            r.body?.takeIf { r.code == 200 }?.also { runCatching { f.writeBytes(it) } }
        } ?: return null
        val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap() ?: return null
        memory.put(url, bmp)
        return bmp
    }

    companion object {
        @Volatile private var instance: DiskImageLoader? = null
        fun get(context: Context): DiskImageLoader = instance ?: synchronized(this) {
            instance ?: DiskImageLoader(File(context.cacheDir, "remote_images").apply { mkdirs() }).also { instance = it }
        }
    }
}

/** Картинка по адресу; пока грузится — null. */
@Composable
fun rememberRemoteImage(url: String?): ImageBitmap? {
    if (url == null) return null
    val context = LocalContext.current
    val loader = LocalRemoteImages.current ?: remember(context) { DiskImageLoader.get(context) }
    var img by remember(url) { mutableStateOf(loader.peek(url)) }
    LaunchedEffect(url) {
        if (img == null) img = withContext(Dispatchers.IO) { runCatching { loader.load(url) }.getOrNull() }
    }
    return img
}
