package com.screentranslator.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Rect
import com.google.mlkit.common.model.RemoteModelManager
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.TranslateRemoteModel
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.Translator
import com.google.mlkit.nl.translate.TranslatorOptions
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import com.google.mlkit.vision.text.japanese.JapaneseTextRecognizerOptions
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

/** 번역에 필요한 모델이 아직 기기에 없을 때 던진다. */
class ModelNotDownloadedException(
    val language: String,
    val onlineProblems: List<String> = emptyList(),
) : Exception("번역 모델 없음: $language")

/**
 * 화면 이미지에서 글자를 찾아(OCR) 한국어로 번역한다. 글자 인식은 늘 기기 안에서 하고,
 * 온라인 번역을 쓸 때도 인식된 글자만 보낸다.
 */
class TranslationEngine(private val context: Context) {

    data class Item(
        val bounds: Rect,
        val lineCount: Int,
        val original: String,
        val translated: String,
    )

    private val latinRecognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    private val japaneseRecognizer =
        TextRecognition.getClient(JapaneseTextRecognizerOptions.Builder().build())
    private val chineseRecognizer =
        TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())
    private val translators = HashMap<String, Translator>()

    /** 한 화면의 번역 결과와 실제로 번역한 엔진 이름. */
    data class Result(val items: List<Item>, val engine: String)

    /**
     * 화면 글자를 읽고 번역한다. Gemini → Cloud Translation → 기기 안 번역 순서로 시도해서,
     * 앞의 것이 키가 없거나 한도 초과·차단·오류로 실패하면 다음 것을 쓴다.
     */
    suspend fun translateScreen(bitmap: Bitmap, settings: Prefs.Settings): Result {
        val sourceSetting = settings.sourceLanguage
        val image = InputImage.fromBitmap(bitmap, 0)
        val text = recognize(image, sourceSetting)

        val blocks = text.textBlocks.mapNotNull { block ->
            val bounds = block.boundingBox ?: return@mapNotNull null
            val language = if (sourceSetting == Prefs.AUTO) guessLanguage(block.text) else sourceSetting
            if (language == null || block.text.isBlank()) return@mapNotNull null
            Triple(bounds, block.lines.size.coerceAtLeast(1), language to joinLines(block, language))
        }
        if (blocks.isEmpty()) return Result(emptyList(), "")
        val originals = blocks.map { it.third.second }
        fun items(translated: List<String>) = blocks.mapIndexed { i, (bounds, lines, pair) ->
            Item(bounds, lines, pair.second, translated[i])
        }

        var offline = false
        val problems = mutableListOf<String>()
        if (settings.geminiKey.isNotBlank()) {
            try {
                return Result(items(OnlineTranslator.gemini(originals, settings.geminiKey, settings.geminiModel)), "Gemini")
            } catch (e: Exception) {
                offline = e is OnlineTranslationException && e.offline
                problems += "Gemini: ${e.message}"
            }
        }
        if (!offline && settings.cloudKey.isNotBlank()) {
            // 구글은 보낸 글자 수(공백 포함)로 요금을 매긴다. 무료분을 넘길 것 같으면 아예 보내지 않는다.
            val chars = originals.sumOf { it.length }.toLong()
            if (Prefs.cloudCharsThisMonth(context) + chars > Prefs.CLOUD_FREE_CHARS) {
                problems += "Cloud: 이번 달 무료분을 다 썼어요"
            } else {
                try {
                    val source = if (sourceSetting == Prefs.AUTO) null else sourceSetting
                    val translated = OnlineTranslator.cloud(originals, settings.cloudKey, source)
                    Prefs.addCloudChars(context, chars)
                    return Result(items(translated), "Cloud Translation")
                } catch (e: Exception) {
                    problems += "Cloud: ${e.message}"
                }
            }
        }

        val missing = missingLanguages(blocks.map { it.third.first }.toSet())
        if (missing.isNotEmpty()) throw ModelNotDownloadedException(missing.first(), problems)

        val translated = coroutineScope {
            blocks.map { (_, _, pair) ->
                async { translator(pair.first).translate(pair.second).await() }
            }.awaitAll()
        }
        return Result(items(translated), if (problems.isEmpty()) "기기 안 번역" else "기기 안 번역(온라인 실패)")
    }

    private suspend fun recognize(image: InputImage, sourceSetting: String): Text = when (sourceSetting) {
        TranslateLanguage.ENGLISH -> latinRecognizer.process(image).await()
        TranslateLanguage.JAPANESE -> japaneseRecognizer.process(image).await()
        TranslateLanguage.CHINESE -> chineseRecognizer.process(image).await()
        else -> {
            // 자동 감지: 일본어 인식기는 라틴 문자와 한자도 읽는다.
            // 가나 없이 한자만 보이면 중국어일 가능성이 높으니 중국어 인식기로 다시 읽는다.
            val result = japaneseRecognizer.process(image).await()
            if (!result.text.any(::isKana) && result.text.any(::isHan)) {
                chineseRecognizer.process(image).await()
            } else {
                result
            }
        }
    }

    /** 글자 종류로 언어를 추정한다. 한국어 등 번역할 필요가 없는 글은 null. */
    private fun guessLanguage(text: String): String? {
        var kana = 0
        var han = 0
        var hangul = 0
        var latin = 0
        for (c in text) {
            when {
                isKana(c) -> kana++
                isHan(c) -> han++
                c in '가'..'힣' || c in 'ㄱ'..'ㆎ' -> hangul++
                c in 'a'..'z' || c in 'A'..'Z' -> latin++
            }
        }
        return when {
            kana > 0 -> TranslateLanguage.JAPANESE
            hangul > 0 && hangul >= han + latin -> null
            han > 0 -> TranslateLanguage.CHINESE
            latin >= 2 -> TranslateLanguage.ENGLISH
            else -> null
        }
    }

    private fun joinLines(block: Text.TextBlock, language: String): String {
        val lines = block.lines.map { it.text.trim() }.filter { it.isNotEmpty() }
        if (lines.isEmpty()) return block.text
        if (language != TranslateLanguage.ENGLISH) return lines.joinToString("")
        val sb = StringBuilder()
        for (line in lines) {
            when {
                sb.isEmpty() -> sb.append(line)
                sb.endsWith("-") -> {
                    sb.setLength(sb.length - 1)
                    sb.append(line)
                }
                else -> sb.append(' ').append(line)
            }
        }
        return sb.toString()
    }

    /** 번역에 필요한데 아직 받지 않은 언어 모델. 영어 모델은 항상 들어 있다. */
    private suspend fun missingLanguages(sources: Set<String>): List<String> {
        val downloaded = downloadedLanguages()
        val needed = sources + TranslateLanguage.KOREAN - TranslateLanguage.ENGLISH
        return needed.filter { it !in downloaded }
    }

    private fun translator(source: String): Translator = synchronized(translators) {
        translators.getOrPut(source) {
            Translation.getClient(
                TranslatorOptions.Builder()
                    .setSourceLanguage(source)
                    .setTargetLanguage(TranslateLanguage.KOREAN)
                    .build()
            )
        }
    }

    fun close() {
        latinRecognizer.close()
        japaneseRecognizer.close()
        chineseRecognizer.close()
        synchronized(translators) {
            translators.values.forEach { it.close() }
            translators.clear()
        }
    }

    companion object {
        fun isKana(c: Char) = c in '぀'..'ヿ' || c in 'ㇰ'..'ㇿ'
        fun isHan(c: Char) = c in '一'..'鿿' || c in '㐀'..'䶿'

        suspend fun downloadedLanguages(): Set<String> =
            RemoteModelManager.getInstance()
                .getDownloadedModels(TranslateRemoteModel::class.java)
                .await()
                .map { it.language }
                .toSet()
    }
}
