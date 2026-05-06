package com.sync.app

import android.annotation.SuppressLint
import android.app.Activity
import android.webkit.JavascriptInterface
import android.webkit.WebView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

class AndroidBridge(
    private val activity: Activity,
    private val webView: WebView,
    private val scope: CoroutineScope
) {
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .build()

    // ── JS → Android entry point ─────────────────────────
    @JavascriptInterface
    fun postMessage(json: String) {
        try {
            val doc = JSONObject(json)
            when (doc.optString("type")) {
                "search" -> scope.launch {
                    doSearch(doc.optString("query"), doc.optString("id", "0"))
                }
                "suggest" -> scope.launch {
                    doSuggest(doc.optString("query"), doc.optString("id", "0"))
                }
                "fetchLyrics" -> scope.launch {
                    doFetchLyrics(
                        doc.optString("title"),
                        doc.optString("channel"),
                        doc.optDouble("duration", 0.0),
                        doc.optString("id", "0")
                    )
                }
                "minimize" -> activity.runOnUiThread { activity.moveTaskToBack(true) }
                "close" -> activity.runOnUiThread { activity.finish() }
                "setTitle", "drag", "maximize", "overlayMode", "overlayLyrics" -> { /* no-op */ }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    // ── Send message back to JS ──────────────────────────
    private fun sendToJs(payload: JSONObject) {
        val escaped = payload.toString().replace("\\", "\\\\").replace("'", "\\'")
        activity.runOnUiThread {
            webView.evaluateJavascript("window.__sync && window.__sync('$escaped')", null)
        }
    }

    // ════════════════════════════════════════════════════
    //  YouTube Search
    // ════════════════════════════════════════════════════
    private suspend fun doSearch(query: String, callbackId: String) {
        try {
            val KEY = "AIzaSyAO_FJ2SlqU8Q4STEHLGCilw_Y9_11qcW8"
            val URL = "https://www.youtube.com/youtubei/v1/search?key=$KEY&prettyPrint=false"
            val bodyJson = JSONObject().apply {
                put("context", JSONObject().apply {
                    put("client", JSONObject().apply {
                        put("clientName", "WEB")
                        put("clientVersion", "2.20240101.00.00")
                        put("hl", "ko")
                        put("gl", "KR")
                    })
                })
                put("query", query)
                put("params", "EgIQAQ%3D%3D")
            }
            val body = bodyJson.toString().toRequestBody("application/json".toMediaType())
            val req = Request.Builder()
                .url(URL)
                .post(body)
                .addHeader("X-YouTube-Client-Name", "1")
                .addHeader("X-YouTube-Client-Version", "2.20240101.00.00")
                .addHeader("Origin", "https://www.youtube.com")
                .addHeader("Referer", "https://www.youtube.com/")
                .addHeader("User-Agent", UA)
                .build()

            val responseJson = withContext(Dispatchers.IO) {
                client.newCall(req).execute().use { it.body!!.string() }
            }
            val tracks = parseSearchResults(responseJson)
            sendToJs(JSONObject().apply {
                put("type", "searchResult")
                put("id", callbackId)
                put("success", true)
                put("tracks", tracks)
            })
        } catch (e: Exception) {
            sendToJs(JSONObject().apply {
                put("type", "searchResult")
                put("id", callbackId)
                put("success", false)
                put("error", e.message)
            })
        }
    }

    private fun parseSearchResults(json: String): JSONArray {
        val list = JSONArray()
        try {
            val doc = JSONObject(json)
            val sections = doc
                .getJSONObject("contents")
                .getJSONObject("twoColumnSearchResultsRenderer")
                .getJSONObject("primaryContents")
                .getJSONObject("sectionListRenderer")
                .getJSONArray("contents")

            for (si in 0 until sections.length()) {
                val sec = sections.getJSONObject(si)
                if (!sec.has("itemSectionRenderer")) continue
                val items = sec.getJSONObject("itemSectionRenderer").getJSONArray("contents")
                for (ii in 0 until items.length()) {
                    val item = items.getJSONObject(ii)
                    if (!item.has("videoRenderer")) continue
                    val vr = item.getJSONObject("videoRenderer")
                    val id = vr.optString("videoId").ifEmpty { continue }

                    val title = vr.optJSONObject("title")
                        ?.optJSONArray("runs")?.optJSONObject(0)?.optString("text") ?: ""

                    val channel = (vr.optJSONObject("ownerText")
                        ?: vr.optJSONObject("shortBylineText"))
                        ?.optJSONArray("runs")?.optJSONObject(0)?.optString("text") ?: ""

                    val durStr = vr.optJSONObject("lengthText")
                        ?.optString("simpleText") ?: ""

                    if (!isMusicVideo(title, channel, vr)) continue

                    list.put(JSONObject().apply {
                        put("id", id)
                        put("title", title)
                        put("channel", channel)
                        put("dur", parseDur(durStr))
                        put("thumb", "https://i.ytimg.com/vi/$id/mqdefault.jpg")
                    })
                    if (list.length() >= 20) break
                }
                if (list.length() >= 20) break
            }
        } catch (_: Exception) {}
        return list
    }

    private fun isMusicVideo(title: String, channel: String, vr: JSONObject): Boolean {
        val tl = title.lowercase()
        val cl = channel.lowercase()
        if (listOf("vevo", "topic", "music", "records", "entertainment", "sound", "audio", "official")
                .any { cl.contains(it) }) return true
        if (listOf("official", "mv", "m/v", "music video", "audio", "lyrics", "lyric",
                "visualizer", "live", "performance", "concert").any { tl.contains(it) }) return true
        if (vr.has("ownerBadges")) return true
        return false
    }

    private fun parseDur(s: String): Int {
        if (s.isEmpty()) return 0
        val parts = s.split(":")
        return try {
            when (parts.size) {
                3 -> parts[0].toInt() * 3600 + parts[1].toInt() * 60 + parts[2].toInt()
                2 -> parts[0].toInt() * 60 + parts[1].toInt()
                else -> 0
            }
        } catch (_: Exception) { 0 }
    }

    // ════════════════════════════════════════════════════
    //  Autocomplete suggestions
    // ════════════════════════════════════════════════════
    private suspend fun doSuggest(query: String, callbackId: String) {
        try {
            val url = "https://suggestqueries.google.com/complete/search?client=firefox&ds=yt&q=${
                URLEncoder.encode(query, "UTF-8")}&hl=ko"
            val req = Request.Builder().url(url).addHeader("User-Agent", UA).build()
            val json = withContext(Dispatchers.IO) {
                client.newCall(req).execute().use { it.body!!.string() }
            }
            val arr = JSONArray(json)
            val suggestions = JSONArray()
            if (arr.length() > 1) {
                val items = arr.getJSONArray(1)
                for (i in 0 until min(8, items.length())) {
                    suggestions.put(items.getString(i))
                }
            }
            sendToJs(JSONObject().apply {
                put("type", "suggestResult")
                put("id", callbackId)
                put("success", true)
                put("suggestions", suggestions)
            })
        } catch (e: Exception) {
            sendToJs(JSONObject().apply {
                put("type", "suggestResult")
                put("id", callbackId)
                put("success", false)
                put("suggestions", JSONArray())
            })
        }
    }

    // ════════════════════════════════════════════════════
    //  Lyrics — lrclib → NetEase fallback
    // ════════════════════════════════════════════════════
    private suspend fun doFetchLyrics(rawTitle: String, channel: String, ytDuration: Double, callbackId: String) {
        val lines = tryLrclib(rawTitle, channel, ytDuration)
            ?: tryNetEase(rawTitle, channel, ytDuration)

        if (lines != null) {
            sendToJs(JSONObject().apply {
                put("type", "lyricsResult")
                put("id", callbackId)
                put("success", true)
                put("lines", lines)
            })
        } else {
            sendToJs(JSONObject().apply {
                put("type", "lyricsResult")
                put("id", callbackId)
                put("success", false)
                put("lines", JSONArray())
            })
        }
    }

    // ── 1st: lrclib.net ──────────────────────────────────
    private suspend fun tryLrclib(rawTitle: String, channel: String, ytDuration: Double): JSONArray? {
        return try {
            val cleanTitle = cleanTitle(rawTitle)
            val cleanArtist = cleanArtist(channel)

            var results = searchLrclib("$cleanTitle $cleanArtist")
            if (!hasSyncedResults(results)) results = searchLrclib(cleanTitle)
            if (!hasSyncedResults(results)) {
                val stripped = stripBrackets(cleanTitle)
                if (stripped != cleanTitle) results = searchLrclib(stripped)
            }
            if (!hasSyncedResults(results) && cleanArtist.isNotBlank())
                results = searchLrclib("$cleanArtist $cleanTitle")

            if (results == null || results.length() == 0) return null

            data class Candidate(val lrc: String, val score: Double)
            val candidates = mutableListOf<Candidate>()

            for (i in 0 until results.length()) {
                val item = results.getJSONObject(i)
                val lrcText = item.optString("syncedLyrics").ifEmpty { continue }

                var lrcDur = getLrcLastTimestamp(lrcText)
                if (lrcDur <= 0) lrcDur = item.optDouble("duration", 0.0)

                var score = 0.0
                if (ytDuration > 0 && lrcDur > 0) {
                    val diff = abs(lrcDur - ytDuration)
                    score += when {
                        diff <= 3 -> 50.0
                        diff <= 10 -> 35.0
                        diff <= 30 -> 15.0
                        diff <= 60 -> 5.0
                        else -> -25.0
                    }
                }
                score += titleSimilarityScore(cleanTitle, item.optString("trackName")) * 30
                score += titleSimilarityScore(cleanArtist, item.optString("artistName")) * 20
                candidates.add(Candidate(lrcText, score))
            }

            if (candidates.isEmpty()) return null
            candidates.sortByDescending { it.score }
            val lines = parseLrc(candidates[0].lrc)
            if (lines.length() > 0) lines else null
        } catch (_: Exception) { null }
    }

    private suspend fun searchLrclib(query: String): JSONArray? {
        return try {
            val url = "https://lrclib.net/api/search?q=${URLEncoder.encode(query, "UTF-8")}"
            val req = Request.Builder().url(url).addHeader("User-Agent", UA).build()
            val json = withContext(Dispatchers.IO) {
                client.newCall(req).execute().use { it.body!!.string() }
            }
            JSONArray(json)
        } catch (_: Exception) { null }
    }

    private fun hasSyncedResults(results: JSONArray?): Boolean {
        if (results == null) return false
        for (i in 0 until results.length()) {
            val sl = results.getJSONObject(i).optString("syncedLyrics")
            if (sl.isNotBlank()) return true
        }
        return false
    }

    // ── 2nd: NetEase Cloud Music ─────────────────────────
    private suspend fun tryNetEase(rawTitle: String, channel: String, ytDuration: Double): JSONArray? {
        return try {
            val cleanTitle = cleanTitle(rawTitle)
            val cleanArtist = cleanArtist(channel)
            val queries = listOf("$cleanTitle $cleanArtist", cleanTitle, stripBrackets(cleanTitle)).distinct()

            var candidates: List<Pair<Long, Double>>? = null
            for (q in queries) {
                candidates = searchNetEase(q, cleanTitle, cleanArtist, ytDuration)
                if (!candidates.isNullOrEmpty()) break
            }
            if (candidates.isNullOrEmpty()) return null

            for (i in 0 until min(3, candidates.size)) {
                if (candidates[i].second < 40) break
                val lines = fetchNetEaseLrc(candidates[i].first)
                if (lines != null && lines.length() > 0) return lines
            }
            null
        } catch (_: Exception) { null }
    }

    private suspend fun searchNetEase(
        query: String, cleanTitle: String, cleanArtist: String, ytDuration: Double
    ): List<Pair<Long, Double>>? {
        return try {
            val url = "https://music.163.com/api/search/get?s=${URLEncoder.encode(query, "UTF-8")}&type=1&limit=10"
            val req = Request.Builder().url(url)
                .addHeader("Referer", "https://music.163.com")
                .addHeader("Cookie", "appver=8.0.0")
                .addHeader("User-Agent", UA)
                .build()
            val json = withContext(Dispatchers.IO) {
                client.newCall(req).execute().use { it.body!!.string() }
            }
            val doc = JSONObject(json)
            val songs = doc.optJSONObject("result")?.optJSONArray("songs") ?: return null
            val list = mutableListOf<Pair<Long, Double>>()
            for (i in 0 until songs.length()) {
                val song = songs.getJSONObject(i)
                val songId = song.getLong("id")
                val songTitle = song.optString("name")
                val artists = buildString {
                    song.optJSONArray("artists")?.let { arr ->
                        for (j in 0 until arr.length()) {
                            if (j > 0) append(" ")
                            append(arr.getJSONObject(j).optString("name"))
                        }
                    }
                }
                val duration = song.optDouble("duration", 0.0) / 1000.0
                var score = titleSimilarityScore(cleanTitle, songTitle) * 40 +
                        titleSimilarityScore(cleanArtist, artists) * 25
                if (ytDuration > 0 && duration > 0) {
                    val diff = abs(duration - ytDuration)
                    score += when {
                        diff <= 3 -> 30.0
                        diff <= 10 -> 18.0
                        diff <= 30 -> 8.0
                        else -> -15.0
                    }
                }
                list.add(Pair(songId, score))
            }
            list.sortByDescending { it.second }
            list
        } catch (_: Exception) { null }
    }

    private suspend fun fetchNetEaseLrc(songId: Long): JSONArray? {
        return try {
            val url = "https://music.163.com/api/song/lyric?id=$songId&lv=1&kv=1&tv=-1"
            val req = Request.Builder().url(url)
                .addHeader("Referer", "https://music.163.com")
                .addHeader("Cookie", "appver=8.0.0")
                .addHeader("User-Agent", UA)
                .build()
            val json = withContext(Dispatchers.IO) {
                client.newCall(req).execute().use { it.body!!.string() }
            }
            val doc = JSONObject(json)
            val lrcText = doc.optJSONObject("klyric")?.optString("lyric")?.ifEmpty { null }
                ?: doc.optJSONObject("lrc")?.optString("lyric")?.ifEmpty { null }
                ?: return null
            val lines = parseLrc(lrcText)
            if (lines.length() > 0) lines else null
        } catch (_: Exception) { null }
    }

    // ════════════════════════════════════════════════════
    //  LRC parser
    // ════════════════════════════════════════════════════
    private val creditLineRx = Regex(
        """^\s*(?:作词|作曲|编曲|混音|制作人|出品|录音|母带|OP|SP|厂牌|发行|MV监制|监制|制作|ISRC|专辑|歌手|作詞|作曲者|編曲|混音師)\s*[：:].{0,80}$"""
    )

    private fun parseLrc(lrc: String): JSONArray {
        data class Entry(val start: Double, val text: String)
        val list = mutableListOf<Entry>()
        val lineRx = Regex("""^\[(\d+):(\d+)\.(\d+)\](.*)""")
        for (line in lrc.split("\n")) {
            val m = lineRx.matchEntire(line.trim()) ?: continue
            val min = m.groupValues[1].toInt()
            val sec = m.groupValues[2].toInt()
            val ms = m.groupValues[3].padEnd(3, '0').substring(0, 3)
            val t = min * 60.0 + sec + ms.toInt() / 1000.0
            val text = m.groupValues[4].trim()
            if (text.isEmpty()) continue
            if (creditLineRx.containsMatchIn(text)) continue
            list.add(Entry(t, text))
        }
        list.sortBy { it.start }
        val result = JSONArray()
        for (i in list.indices) {
            val end = if (i + 1 < list.size) list[i + 1].start else list[i].start + 5.0
            result.put(JSONObject().apply {
                put("start", list[i].start)
                put("end", end)
                put("text", list[i].text)
            })
        }
        return result
    }

    private fun getLrcLastTimestamp(lrc: String): Double {
        var last = 0.0
        val rx = Regex("""^\[(\d+):(\d+)\.(\d+)\]""")
        for (line in lrc.split("\n")) {
            val m = rx.find(line.trim()) ?: continue
            val ms = m.groupValues[3].padEnd(3, '0').substring(0, 3)
            val t = m.groupValues[1].toInt() * 60.0 + m.groupValues[2].toInt() + ms.toInt() / 1000.0
            if (t > last) last = t
        }
        return last
    }

    // ════════════════════════════════════════════════════
    //  Helpers
    // ════════════════════════════════════════════════════
    private fun titleSimilarityScore(a: String, b: String): Double {
        if (a.isBlank() || b.isBlank()) return 0.0
        val al = a.lowercase(); val bl = b.lowercase()
        if (al == bl) return 1.0
        if (bl.contains(al) || al.contains(bl)) return 0.85
        val wa = Regex("""\W+""").split(al).filter { it.length > 1 }.toSet()
        val wb = Regex("""\W+""").split(bl).filter { it.length > 1 }.toSet()
        if (wa.isEmpty() || wb.isEmpty()) return 0.0
        return wa.intersect(wb).size.toDouble() / max(wa.size, wb.size)
    }

    private fun stripBrackets(t: String): String {
        return t.replace(Regex("""\([^)]*\)"""), "")
            .replace(Regex("""\[[^\]]*\]"""), "")
            .replace(Regex("""\s{2,}"""), " ")
            .trim()
    }

    private fun cleanTitle(t: String): String {
        val opts = setOf(RegexOption.IGNORE_CASE)
        val tagRx = """official\s*(?:music\s*)?(?:video|audio|mv|lyric\s*video|visualizer)?""" +
                """|m/?v|music\s*video|audio(?:\s*only)?""" +
                """|lyrics?\s*(?:video|ver(?:sion)?)?|lyric\s*video|visualizer""" +
                """|live(?:\s+(?:performance|version|session|at\s+.+?))?""" +
                """|performance(?:\s+video)?|concert(?:\s+version)?""" +
                """|hd|4k|1080p|720p|full\s+(?:song|version|album)""" +
                """|remaster(?:ed)?(?:\s+version)?|re-?upload""" +
                """|fan\s+(?:made|video|edit|cam)|color\s*coded""" +
                """|eng(?:lish)?\s*(?:ver\.?|version|sub(?:title)?s?)?""" +
                """|kor(?:ean)?\s*(?:ver\.?|version)?|jp(?:n)?\s*(?:ver\.?|version)?""" +
                """|공식|공식\s*(?:뮤직\s*비디오|음원|영상)?""" +
                """|뮤직\s*비디오|음원|영상|티저|예고편|메이킹필름|안무""" +
                """|prod(?:uced)?\s*(?:by\s*)?.+?|feat\.?\s*.+?|ft\.?\s*.+?|with\s+.+?"""
        var r = t
        r = r.replace(Regex("""\(\s*(?:$tagRx)[^)]*\)""", opts), "").trim()
        r = r.replace(Regex("""\[\s*(?:$tagRx)[^\]]*\]""", opts), "").trim()
        r = r.replace(Regex("""\s*[-|]\s*(?:$tagRx)\s*$""", opts), "").trim()
        r = r.replace(Regex("""\s+(?:feat\.?|ft\.?|with)\s+.+$""", opts), "").trim()
        r = r.replace(Regex("""\s+prod(?:uced)?\s*(?:by\s*).+$""", opts), "").trim()
        r = r.replace(Regex("""[\u2013\u2014]+"""), "-").trim()
        r = r.replace(Regex("""\s{2,}"""), " ").trim()
        return r
    }

    private fun cleanArtist(c: String): String {
        val opts = setOf(RegexOption.IGNORE_CASE)
        var r = c
        r = r.replace(Regex("""\s*[-–]\s*Topic\s*$""", opts), "").trim()
        r = r.replace(Regex("""VEVO$""", opts), "").trim()
        r = r.replace(Regex("""\s*(?:Records|Entertainment|Music|Official|Label|Studios?)\s*$""", opts), "").trim()
        return r.replace(Regex("""\s{2,}"""), " ").trim()
    }

    companion object {
        private const val UA = "Mozilla/5.0 (Linux; Android 14; Pixel 7) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"
    }
}
