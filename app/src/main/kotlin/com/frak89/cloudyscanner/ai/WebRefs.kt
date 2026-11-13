package com.frak89.cloudyscanner.ai

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Reference photos of a kind of object from Wikimedia Commons (free images, no account):
 * only the search words are sent. Used to show the AI what such an object looks like.
 */
object WebRefs {
    private const val UA = "CloudyScanner/1.6 (Android 3D scanner; reference images)"

    private fun get(url: String): ByteArray {
        val c = URL(url).openConnection() as HttpURLConnection
        c.setRequestProperty("User-Agent", UA)
        c.connectTimeout = 8000; c.readTimeout = 12000
        try {
            if (c.responseCode != 200) throw java.io.IOException("HTTP ${c.responseCode}")
            return c.inputStream.use { it.readBytes() }
        } finally { c.disconnect() }
    }

    /** Up to [n] JPEG thumbnails (about 320 px) for [query]; empty when offline or nothing found. */
    fun search(query: String, n: Int = 3): List<ByteArray> {
        val q = URLEncoder.encode("$query filetype:bitmap", "UTF-8")
        val api = "https://commons.wikimedia.org/w/api.php?action=query&format=json&generator=search&gsrnamespace=6" +
            "&gsrlimit=${n * 3}&gsrsearch=$q&prop=imageinfo&iiprop=url|mime&iiurlwidth=320"
        val out = ArrayList<ByteArray>()
        try {
            val pages = JSONObject(String(get(api))).optJSONObject("query")?.optJSONObject("pages") ?: return out
            val list = pages.keys().asSequence().map { pages.getJSONObject(it) }.sortedBy { it.optInt("index", 99) }.toList()
            for (p in list) {
                if (out.size >= n) break
                val info = p.optJSONArray("imageinfo")?.optJSONObject(0) ?: continue
                val mime = info.optString("mime")
                if (!(mime.contains("jpeg") || mime.contains("png"))) continue
                val url = info.optString("thumburl")
                if (url.isEmpty()) continue
                try {
                    val raw = get(url)
                    val bmp = BitmapFactory.decodeByteArray(raw, 0, raw.size) ?: continue
                    out.add(ByteArrayOutputStream().also { bmp.compress(Bitmap.CompressFormat.JPEG, 85, it) }.toByteArray())
                } catch (_: Throwable) { }
            }
        } catch (_: Throwable) { }
        return out
    }
}
