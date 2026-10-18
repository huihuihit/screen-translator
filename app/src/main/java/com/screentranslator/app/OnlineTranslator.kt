package com.screentranslator.app

import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/** 온라인 번역이 실패했을 때 던진다. [offline]이면 인터넷이 안 돼서 다른 온라인 번역도 소용없다. */
class OnlineTranslationException(message: String, val offline: Boolean = false) : Exception(message)

/**
 * 화면에서 읽은 글자만 인터넷 번역 서비스로 보낸다. 화면 이미지는 보내지 않는다.
 */
object OnlineTranslator {

    private const val GEMINI_URL = "https://generativelanguage.googleapis.com/v1beta/models/%s:generateContent"
    private const val CLOUD_URL = "https://translation.googleapis.com/language/translate/v2"
    private const val CLOUD_MAX_TEXTS = 128

    /**
     * 한 화면의 글 덩어리를 한꺼번에 Gemini에 보내 앞뒤 문맥을 살려 번역한다.
     * 결과는 [texts]와 같은 순서, 같은 개수다.
     */
    suspend fun gemini(texts: List<String>, apiKey: String, model: String): List<String> {
        val prompt = """
            아래 JSON 배열의 각 문자열은 휴대폰 한 화면에서 위에서부터 읽어 낸 글 덩어리들이다.
            앞뒤 덩어리를 문맥으로 참고해서 각 문자열을 자연스러운 한국어로 번역해라.
            결과는 입력과 같은 순서, 같은 개수(${texts.size}개)의 JSON 문자열 배열로만 답해라.
            사람 이름, 상표, 숫자, 주소는 알맞게 유지하고, 이미 한국어이거나 번역할 것이 없으면 그대로 둬라.

            ${JSONArray(texts)}
        """.trimIndent()
        val body = JSONObject()
            .put("contents", JSONArray().put(JSONObject()
                .put("role", "user")
                .put("parts", JSONArray().put(JSONObject().put("text", prompt)))))
            .put("generationConfig", JSONObject()
                .put("temperature", 0.2)
                .put("responseMimeType", "application/json")
                .put("responseSchema", JSONObject()
                    .put("type", "ARRAY")
                    .put("items", JSONObject().put("type", "STRING"))))

        val url = GEMINI_URL.format(URLEncoder.encode(model.trim(), "UTF-8"))
        val response = post(url, body, mapOf("x-goog-api-key" to apiKey.trim()), readTimeoutMs = 30_000)

        response.optJSONObject("promptFeedback")?.optString("blockReason")?.takeIf { it.isNotEmpty() }?.let {
            throw OnlineTranslationException("Gemini가 번역을 거부했어요 ($it)")
        }
        val candidate = response.optJSONArray("candidates")?.optJSONObject(0)
            ?: throw OnlineTranslationException("Gemini 응답이 비어 있어요")
        val finish = candidate.optString("finishReason")
        if (finish.isNotEmpty() && finish != "STOP") {
            throw OnlineTranslationException("Gemini가 번역을 끝내지 못했어요 ($finish)")
        }
        val parts = candidate.optJSONObject("content")?.optJSONArray("parts")
            ?: throw OnlineTranslationException("Gemini 응답에 글이 없어요")
        val text = (0 until parts.length()).joinToString("") { parts.getJSONObject(it).optString("text") }
        val array = runCatching { JSONArray(text.trim()) }.getOrNull()
            ?: throw OnlineTranslationException("Gemini 응답을 읽지 못했어요")
        if (array.length() != texts.size) {
            throw OnlineTranslationException("Gemini 번역 개수가 맞지 않아요 (${array.length()}/${texts.size})")
        }
        return List(array.length()) { array.optString(it) }
    }

    /** Google Cloud Translation(NMT) 기본 버전으로 번역한다. [source]가 null이면 언어를 자동 감지한다. */
    suspend fun cloud(texts: List<String>, apiKey: String, source: String?): List<String> =
        texts.chunked(CLOUD_MAX_TEXTS).flatMap { chunk ->
            val body = JSONObject()
                .put("q", JSONArray(chunk))
                .put("target", "ko")
                .put("format", "text")
                .put("model", "nmt")
            if (source != null) body.put("source", if (source == "zh") "zh-CN" else source)
            val url = "$CLOUD_URL?key=" + URLEncoder.encode(apiKey.trim(), "UTF-8")
            val translations = post(url, body, emptyMap(), readTimeoutMs = 15_000)
                .optJSONObject("data")?.optJSONArray("translations")
                ?: throw OnlineTranslationException("Cloud Translation 응답을 읽지 못했어요")
            if (translations.length() != chunk.size) {
                throw OnlineTranslationException("Cloud Translation 번역 개수가 맞지 않아요")
            }
            List(translations.length()) { unescape(translations.getJSONObject(it).optString("translatedText")) }
        }

    private suspend fun post(
        url: String,
        body: JSONObject,
        headers: Map<String, String>,
        readTimeoutMs: Int,
    ): JSONObject = withContext(Dispatchers.IO) {
        val connection = URL(url).openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "POST"
            connection.connectTimeout = 5_000
            connection.readTimeout = readTimeoutMs
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            headers.forEach { (k, v) -> connection.setRequestProperty(k, v) }
            connection.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }

            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            if (code !in 200..299) {
                val reason = when (code) {
                    429 -> "무료 사용량을 다 썼어요"
                    400, 401, 403 -> "API 키나 설정을 확인해 주세요"
                    else -> "서버 오류"
                }
                throw OnlineTranslationException("$reason (HTTP $code)")
            }
            JSONObject(text)
        } catch (e: IOException) {
            throw OnlineTranslationException("인터넷에 연결하지 못했어요", offline = true)
        } finally {
            connection.disconnect()
        }
    }

    /** format=text로 보내도 일부 기호가 HTML 엔티티로 올 때가 있다. */
    private fun unescape(text: String) = text
        .replace("&#39;", "'")
        .replace("&quot;", "\"")
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&amp;", "&")
}
