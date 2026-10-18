package com.screentranslator.app

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.EditText
import android.widget.RadioGroup
import android.widget.TextView
import android.widget.Toast
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.TranslatorOptions
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class MainActivity : Activity() {

    companion object {
        private const val REQUEST_PROJECTION = 1
        private const val REQUEST_NOTIFICATIONS = 2
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private lateinit var sourceGroup: RadioGroup
    private lateinit var overlayStatus: TextView
    private lateinit var overlayButton: Button
    private lateinit var modelStatus: TextView
    private lateinit var downloadButton: Button
    private lateinit var startButton: Button
    private lateinit var geminiKey: EditText
    private lateinit var geminiModel: EditText
    private lateinit var cloudKey: EditText
    private lateinit var cloudUsage: TextView

    private var downloading = false
    private var downloadedLanguages: Set<String> = emptySet()

    private val sourceButtons = mapOf(
        Prefs.AUTO to R.id.sourceAuto,
        TranslateLanguage.ENGLISH to R.id.sourceEnglish,
        TranslateLanguage.JAPANESE to R.id.sourceJapanese,
        TranslateLanguage.CHINESE to R.id.sourceChinese,
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        sourceGroup = findViewById(R.id.sourceGroup)
        overlayStatus = findViewById(R.id.overlayStatus)
        overlayButton = findViewById(R.id.overlayButton)
        modelStatus = findViewById(R.id.modelStatus)
        downloadButton = findViewById(R.id.downloadButton)
        startButton = findViewById(R.id.startButton)
        geminiKey = findViewById(R.id.geminiKey)
        geminiModel = findViewById(R.id.geminiModel)
        cloudKey = findViewById(R.id.cloudKey)
        cloudUsage = findViewById(R.id.cloudUsage)

        val settings = Prefs.settings(this)
        geminiKey.setText(settings.geminiKey)
        geminiModel.setText(settings.geminiModel)
        cloudKey.setText(settings.cloudKey)
        findViewById<Button>(R.id.saveKeysButton).setOnClickListener {
            Prefs.setOnline(this, geminiKey.text.toString(), geminiModel.text.toString(), cloudKey.text.toString())
            geminiModel.setText(Prefs.settings(this).geminiModel)
            Toast.makeText(this, "저장했어요. 다음 번역부터 적용돼요.", Toast.LENGTH_SHORT).show()
        }

        sourceGroup.check(sourceButtons[Prefs.sourceLanguage(this)] ?: R.id.sourceAuto)
        sourceGroup.setOnCheckedChangeListener { _, checkedId ->
            val language = sourceButtons.entries.firstOrNull { it.value == checkedId }?.key ?: Prefs.AUTO
            Prefs.setSourceLanguage(this, language)
        }

        overlayButton.setOnClickListener {
            startActivity(
                Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
            )
        }
        downloadButton.setOnClickListener { downloadModels() }
        startButton.setOnClickListener {
            if (ScreenCaptureService.isRunning) {
                ScreenCaptureService.stop(this)
                startButton.postDelayed({ refreshStatus() }, 300)
            } else {
                startTranslating()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        refreshStatus()
    }

    private fun refreshStatus() {
        val canOverlay = Settings.canDrawOverlays(this)
        overlayStatus.text = if (canOverlay) "✓ 허용됨" else "✗ 아직 허용하지 않았어요. 아래 버튼을 눌러 '화면 번역기'를 허용해 주세요."
        overlayButton.isEnabled = !canOverlay
        startButton.text = if (ScreenCaptureService.isRunning) "번역 중지" else "번역 시작"
        cloudUsage.text = "이번 달 Cloud 사용량: %,d / 500,000자 (무료분을 다 쓰면 기기 안 번역으로 바꿔요)"
            .format(Prefs.cloudCharsThisMonth(this))

        scope.launch {
            downloadedLanguages = runCatching { TranslationEngine.downloadedLanguages() }.getOrDefault(emptySet())
            showModelStatus()
        }
    }

    private fun showModelStatus() {
        if (downloading) return
        val hasKorean = TranslateLanguage.KOREAN in downloadedLanguages
        modelStatus.text = Prefs.SOURCE_LANGUAGES.entries.joinToString("   ") { (code, name) ->
            val ready = hasKorean && (code == TranslateLanguage.ENGLISH || code in downloadedLanguages)
            "$name ${if (ready) "✓" else "✗"}"
        }
        downloadButton.isEnabled = !allModelsReady()
        downloadButton.text = if (allModelsReady()) "번역 모델 준비 완료" else "번역 모델 다운로드 (약 90MB)"
    }

    private fun allModelsReady(): Boolean {
        val needed = Prefs.SOURCE_LANGUAGES.keys + TranslateLanguage.KOREAN - TranslateLanguage.ENGLISH
        return downloadedLanguages.containsAll(needed)
    }

    private fun downloadModels() {
        if (downloading) return
        downloading = true
        downloadButton.isEnabled = false
        scope.launch {
            var failed: Exception? = null
            for ((code, name) in Prefs.SOURCE_LANGUAGES) {
                modelStatus.text = "$name → 한국어 모델 받는 중…"
                val translator = Translation.getClient(
                    TranslatorOptions.Builder()
                        .setSourceLanguage(code)
                        .setTargetLanguage(TranslateLanguage.KOREAN)
                        .build()
                )
                try {
                    translator.downloadModelIfNeeded().await()
                } catch (e: Exception) {
                    failed = e
                } finally {
                    translator.close()
                }
                if (failed != null) break
            }
            downloading = false
            if (failed != null) {
                Toast.makeText(
                    this@MainActivity,
                    "다운로드하지 못했어요. 인터넷 연결을 확인하고 다시 눌러 주세요. (${failed.message})",
                    Toast.LENGTH_LONG,
                ).show()
            }
            refreshStatus()
        }
    }

    private fun startTranslating() {
        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "먼저 1단계 '다른 앱 위에 표시' 권한을 허용해 주세요.", Toast.LENGTH_LONG).show()
            return
        }
        val settings = Prefs.settings(this)
        val hasOnline = settings.geminiKey.isNotBlank() || settings.cloudKey.isNotBlank()
        if (!hasOnline && TranslateLanguage.KOREAN !in downloadedLanguages) {
            Toast.makeText(this, "2단계에서 번역 모델을 받거나, 온라인 번역 키를 넣어 주세요.", Toast.LENGTH_LONG).show()
            return
        }
        // 알림 권한은 없어도 동작하지만, 있어야 알림에서 '중지'를 누를 수 있다.
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQUEST_NOTIFICATIONS)
            return
        }
        requestProjection()
    }

    private fun requestProjection() {
        val manager = getSystemService(MediaProjectionManager::class.java)
        @Suppress("DEPRECATION")
        startActivityForResult(manager.createScreenCaptureIntent(), REQUEST_PROJECTION)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_NOTIFICATIONS) requestProjection()
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQUEST_PROJECTION) return
        if (resultCode != RESULT_OK || data == null) {
            Toast.makeText(this, "화면 캡처를 허용해야 번역할 수 있어요.", Toast.LENGTH_LONG).show()
            return
        }
        ScreenCaptureService.start(this, resultCode, data)
        Toast.makeText(this, "번역할 앱을 열고 떠 있는 '번역' 버튼을 눌러 보세요.", Toast.LENGTH_LONG).show()
        moveTaskToBack(true)
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }
}
