package mz.mibackscreen.mouse.core

import android.app.WallpaperManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.os.Process
import java.io.File

/**
 * 主界面窗口背景：优先系统 API，失败则用 root 读 /data/system/users/<id>/wallpaper 解码，
 * 都不行退回黑色。结果按屏幕尺寸缓存。
 */
object WallpaperBackground {

    /** 按“屏幕宽高”缓存：主屏与背屏各一份（两者裁剪比例不同） */
    private val cache = HashMap<String, Drawable>()

    fun of(context: Context): Drawable {
        val dm = context.resources.displayMetrics
        val key = "${dm.widthPixels}x${dm.heightPixels}"
        synchronized(cache) { cache[key]?.let { return it } }
        val built = build(context, dm.widthPixels, dm.heightPixels)
        synchronized(cache) { cache[key] = built }
        return built
    }

    private fun build(context: Context, w: Int, h: Int): Drawable {
        val fallback: Drawable = ColorDrawable(Color.BLACK)
        // 1) 系统 API
        try {
            val src = WallpaperManager.getInstance(context).drawable
            val bmp = (src as? BitmapDrawable)?.bitmap
            if (bmp != null) return BitmapDrawable(context.resources, centerCrop(w, h, bmp))
        } catch (t: Throwable) {
            Logs.d("Wallpaper", "系统 API 不可用: ${t.javaClass.simpleName}")
        }
        // 2) root 读壁纸文件
        rootWallpaper(context, w, h)?.let { return it }
        Logs.d("Wallpaper", "取不到壁纸，退回黑色")
        return fallback
    }

    /** 用 root 把壁纸文件拷到 filesDir 再解码。 */
    private fun rootWallpaper(context: Context, w: Int, h: Int): Drawable? {
        if (!RootShell.isRootAvailable()) return null
        val userId = Process.myUid() / 100000
        val out = File(context.filesDir, "wallpaper.img")
        val candidates = listOf(
            "/data/system/users/$userId/wallpaper",
            "/data/system/users/$userId/wallpaper_orig",
            "/data/system/users/$userId/wallpaper_lock",
        )
        for (src in candidates) {
            val res = RootShell.run("cp -f $src ${out.absolutePath} && chmod 644 ${out.absolutePath}", 8)
            if (res.code != 0 || !out.exists() || out.length() <= 0L) continue
            val bmp = decodeScaled(out.absolutePath, w, h) ?: continue
            Logs.d("Wallpaper", "已从 $src 载入壁纸 ${bmp.width}x${bmp.height}")
            return BitmapDrawable(context.resources, centerCrop(w, h, bmp))
        }
        return null
    }

    /** 只解码到屏幕量级，避免 4000x3000 的壁纸直接把内存撑爆。 */
    private fun decodeScaled(path: String, targetW: Int, targetH: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= targetW && bounds.outHeight / (sample * 2) >= targetH) {
            sample *= 2
        }
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        return try {
            BitmapFactory.decodeFile(path, opts)
        } catch (t: Throwable) {
            Logs.d("Wallpaper", "解码失败: ${t.message}")
            null
        }
    }

    /** 按目标宽高比居中裁剪，等价于 center-crop（不拉伸变形）。 */
    private fun centerCrop(targetW: Int, targetH: Int, src: Bitmap): Bitmap {
        if (targetW <= 0 || targetH <= 0) return src
        val target = targetW.toFloat() / targetH
        val w = src.width
        val h = src.height
        var cw = w
        var ch = (w / target).toInt()
        if (ch > h) {
            ch = h
            cw = (h * target).toInt()
        }
        if (cw >= w && ch >= h) return src
        return try {
            Bitmap.createBitmap(src, (w - cw) / 2, (h - ch) / 2, cw, ch)
        } catch (t: Throwable) {
            Logs.d("Wallpaper", "裁剪失败，直接铺满: ${t.message}")
            src
        }
    }
}
