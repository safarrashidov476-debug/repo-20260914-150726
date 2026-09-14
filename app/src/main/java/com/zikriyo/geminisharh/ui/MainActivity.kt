package com.zikriyo.geminisharh.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.media.MediaScannerConnection
import android.os.Environment
import android.provider.OpenableColumns
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.zikriyo.geminisharh.R
import com.zikriyo.geminisharh.api.AzureTtsClient
import com.zikriyo.geminisharh.api.EdgeTtsClient
import com.zikriyo.geminisharh.api.GeminiApiClient
import com.zikriyo.geminisharh.data.Prefs
import com.zikriyo.geminisharh.databinding.ActivityMainBinding
import com.zikriyo.geminisharh.util.MediaComposer
import com.zikriyo.geminisharh.util.OutputSaver
import com.zikriyo.geminisharh.util.TimestampParser
import com.zikriyo.geminisharh.util.TtsHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var prefs: Prefs
    private var selectedUri: Uri? = null
    private var selectedName: String = ""
    private var isProcessing = false

    private val pickVideo = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri != null) {
            selectedUri = uri
            selectedName = queryDisplayName(uri) ?: "video.mp4"
            binding.tvSelectedVideo.text = selectedName
            binding.btnStart.isEnabled = true
            appendLog("Tanlandi: $selectedName")
        }
    }

    private val requestPermission = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { /* handled on demand */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)

        prefs = Prefs(this)

        binding.btnPickVideo.setOnClickListener {
            ensurePermissions()
            pickVideo.launch("video/*")
        }

        binding.btnStart.setOnClickListener {
            if (isProcessing) return@setOnClickListener
            startProcessing()
        }
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menu.add(0, 1, 0, getString(R.string.open_settings))
            .setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == 1) {
            startActivity(Intent(this, SettingsActivity::class.java))
            return true
        }
        return super.onOptionsItemSelected(item)
    }

    private fun ensurePermissions() {
        val needed = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= 33) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_MEDIA_VIDEO)
                != PackageManager.PERMISSION_GRANTED
            ) needed.add(Manifest.permission.READ_MEDIA_VIDEO)
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
            ) needed.add(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE)
                != PackageManager.PERMISSION_GRANTED
            ) needed.add(Manifest.permission.READ_EXTERNAL_STORAGE)
        }
        if (needed.isNotEmpty()) requestPermission.launch(needed.toTypedArray())
    }

    private fun startProcessing() {
        val apiKey = prefs.apiKey
        if (apiKey.isBlank()) {
            Toast.makeText(this, R.string.no_api_key, Toast.LENGTH_LONG).show()
            startActivity(Intent(this, SettingsActivity::class.java))
            return
        }
        val uri = selectedUri
        if (uri == null) {
            Toast.makeText(this, R.string.select_video_first, Toast.LENGTH_SHORT).show()
            return
        }

        isProcessing = true
        binding.btnStart.isEnabled = false
        binding.btnPickVideo.isEnabled = false
        binding.progressBar.visibility = View.VISIBLE
        binding.tvStatus.setText(R.string.status_uploading)
        appendLog("Jarayon boshlandi…")
        appendLog("Ovoz: ${prefs.selectedVoiceId}")
        appendLog("Model: ${prefs.selectedVisionModel}")

        lifecycleScope.launch {
            try {
                val resultDir = withContext(Dispatchers.IO) {
                    processVideo(uri, apiKey)
                }
                binding.tvStatus.setText(R.string.success)
                appendLog("Tayyor! Papka: ${resultDir.absolutePath}")
                Toast.makeText(this@MainActivity, R.string.success, Toast.LENGTH_LONG).show()
            } catch (e: Exception) {
                binding.tvStatus.text = getString(R.string.error_generic) + ": ${e.message}"
                appendLog("XATO: ${e.message}")
                e.printStackTrace()
            } finally {
                isProcessing = false
                binding.btnStart.isEnabled = true
                binding.btnPickVideo.isEnabled = true
                binding.progressBar.visibility = View.GONE
            }
        }
    }

    private suspend fun processVideo(uri: Uri, apiKey: String): File {
        val delayMs = (prefs.requestDelaySec * 1000).toLong()
        val client = GeminiApiClient(apiKey, delayMs)
        val voiceId = prefs.selectedVoiceId
        val modelId = prefs.selectedVisionModel

        // Copy content URI to cache file
        updateStatus(R.string.status_uploading)
        val ext = selectedName.substringAfterLast('.', "mp4").lowercase()
        val mime = when (ext) {
            "mp4", "m4v" -> "video/mp4"
            "webm" -> "video/webm"
            "mov" -> "video/quicktime"
            "avi" -> "video/avi"
            "mkv" -> "video/x-matroska"
            "3gp" -> "video/3gpp"
            "wmv" -> "video/wmv"
            else -> "video/mp4"
        }
        val cacheFile = File(cacheDir, "input_${System.currentTimeMillis()}.$ext")
        contentResolver.openInputStream(uri)?.use { input ->
            FileOutputStream(cacheFile).use { output -> input.copyTo(output) }
        } ?: throw Exception("Videoni o‘qib bo‘lmadi")

        val sizeMb = cacheFile.length() / (1024.0 * 1024.0)
        appendLog("Fayl nusxalandi: %.1f MB, mime=$mime".format(sizeMb))
        if (sizeMb > 100) {
            appendLog("OGOHLANTIRISH: Fayl katta (>100 MB). Qisqaroq yoki siqilgan video sinab ko‘ring.")
        }

        // Upload + tahlil (Files API, FAILED bo'lsa inline fallback)
        updateStatus(R.string.status_analyzing)
        var rawText: String? = null
        try {
            val uploaded = client.uploadVideo(cacheFile, mime)
            appendLog("Yuklandi: ${uploaded.name}")
            appendLog("URI: ${uploaded.uri}")

            val active = client.waitUntilActive(uploaded.name)
            if (!active) throw Exception("Video serverda ACTIVE holatga o'tmadi (timeout)")
            appendLog("Video ACTIVE")

            rawText = client.generateTimedDescription(uploaded.uri, mime, "uz", modelId)
        } catch (e: Exception) {
            val msg = e.message ?: ""
            if (msg.contains("FAILED") || msg.contains("qayta ishlanmadi")) {
                val maxInline = 18L * 1024 * 1024
                if (cacheFile.length() <= maxInline) {
                    appendLog("Files API FAILED - inline usul bilan qayta urinilmoqda...")
                    rawText = client.generateTimedDescriptionInline(cacheFile, mime, "uz", modelId)
                } else {
                    val mb = cacheFile.length() / 1024.0 / 1024.0
                    val mbStr = String.format("%.1f", mb)
                    throw Exception(
                        "Video serverda qayta ishlanmadi va fayl inline uchun katta " +
                            "($mbStr MB > 18 MB). Qisqaroq yoki H.264 MP4 qilib qayta kodlang. " +
                            "Asosiy xato: $msg"
                    )
                }
            } else {
                throw e
            }
        }
        val finalText = rawText ?: throw Exception("Gemini javobi bo'sh")
        appendLog("Gemini javobi olingan (${finalText.length} belgi)")

        val segments = TimestampParser.parse(finalText)
        if (segments.isEmpty()) {
            throw Exception("Vaqt kodlari topilmadi. Gemini javobi:\n$finalText")
        }
        appendLog("${segments.size} ta segment topildi")

        // Ochiq papka: Yuklamalar/GeminiSharh_...
        var outDir = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
            "GeminiSharh_${System.currentTimeMillis()}"
        )
        if (!outDir.exists() && !outDir.mkdirs()) {
            outDir = File(
                getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS),
                "GeminiSharh_${System.currentTimeMillis()}"
            )
            outDir.mkdirs()
            appendLog("Yuklamalar yopiq — ichki papka ishlatiladi")
        }
        appendLog("Natija papkasi: ${outDir.absolutePath}")

        // Save SRT + TXT
        updateStatus(R.string.status_saving)
        File(outDir, "description.srt").writeText(TimestampParser.createSrt(segments))
        File(outDir, "description.txt").writeText(TimestampParser.createTxt(segments))
        File(outDir, "raw_gemini.txt").writeText(finalText)

        // TTS: 1) Edge (norasmiy, kalitsiz, Sardor) → 2) Azure → 3) System TTS
        updateStatus(R.string.status_tts)
        val audioDir = File(outDir, "audio_segments")
        audioDir.mkdirs()

        val isNeuralVoice = voiceId.contains("Neural") ||
            voiceId.startsWith("uz-") || voiceId.startsWith("ru-") || voiceId.startsWith("en-")

        val edge = EdgeTtsClient()
        val azureKey = prefs.azureKey
        val azure = if (azureKey.isNotBlank()) AzureTtsClient(azureKey, prefs.azureRegion) else null

        var edgeOkCount = 0
        var azureOkCount = 0
        var systemUsed = false

        segments.forEachIndexed { idx, seg ->
            val audioFile = File(audioDir, "seg_%03d.mp3".format(idx))
            var ok = false

            // 1. Edge TTS (botlardagi kabi, kalitsiz)
            if (isNeuralVoice) {
                ok = edge.synthesizeToFile(seg.text, voiceId, audioFile)
                if (ok) {
                    edgeOkCount++
                    appendLog("TTS ${idx + 1}/${segments.size} [Edge]: OK")
                }
            }

            // 2. Azure fallback
            if (!ok && azure != null) {
                ok = azure.synthesizeToFile(seg.text, voiceId, audioFile)
                if (ok) {
                    azureOkCount++
                    appendLog("TTS ${idx + 1}/${segments.size} [Azure]: OK")
                }
            }

            // 3. System TTS last resort
            if (!ok) {
                systemUsed = true
                val wavFile = File(audioDir, "seg_%03d.wav".format(idx))
                val tts = TtsHelper(this@MainActivity)
                try {
                    ok = tts.speakToFile(seg.text, wavFile)
                    appendLog("TTS ${idx + 1}/${segments.size} [System]: ${if (ok) "OK" else "FAIL"}")
                } finally {
                    tts.shutdown()
                }
            }

            Thread.sleep(150) // engil rate-limit
        }

        appendLog("TTS yakun: Edge=$edgeOkCount, Azure=$azureOkCount, System=${if (systemUsed) "ha" else "yoq"}")

        // Segment fayllar ro'yxati
        val segFiles = segments.indices.map { idx ->
            val mp3 = File(audioDir, "seg_%03d.mp3".format(idx))
            val wav = File(audioDir, "seg_%03d.wav".format(idx))
            when {
                mp3.exists() && mp3.length() > 0 -> mp3
                wav.exists() && wav.length() > 0 -> wav
                else -> mp3
            }
        }

        // 1) Timestamp bo'yicha bitta narration
        updateStatus(R.string.status_saving)
        appendLog("Narration yig'ilmoqda...")
        val narrationFile = File(outDir, "narration.mp3")
        var narrOk = MediaComposer.buildTimedNarration(segments, segFiles, narrationFile)
        if (!narrOk) {
            appendLog("Timed narration muvaffaqiyatsiz — oddiy concat...")
            narrOk = MediaComposer.concatAudioSimple(segFiles, narrationFile)
        }
        appendLog("Narration: ${if (narrOk) "OK (${narrationFile.length()/1024} KB)" else "FAIL"}")

        // 2) Original video + narration (original ovoz pastroq ~28%)
        if (narrOk && narrationFile.exists()) {
            appendLog("Video bilan birlashtirilmoqda (original ovoz past)...")
            val finalVideo = File(outDir, "video_with_sharh.mp4")
            val mergeOk = MediaComposer.mergeVideoWithNarration(
                videoFile = cacheFile,
                narrationFile = narrationFile,
                outputFile = finalVideo,
                originalVolume = 0.28,
                narrationVolume = 1.0
            )
            if (mergeOk) {
                appendLog("Tayyor video: ${finalVideo.name} (${finalVideo.length() / 1024} KB)")
            } else {
                appendLog("Birlashtirish muvaffaqiyatsiz — alohida narration.mp3 saqlangan")
            }
        }

        // Media scanner + Yuklamalar/GeminiSharh_* ga e'lon qilish
        try {
            val toScan = outDir.listFiles()?.map { it.absolutePath }?.toTypedArray() ?: emptyArray()
            if (toScan.isNotEmpty()) {
                MediaScannerConnection.scanFile(this@MainActivity, toScan, null, null)
            }
            audioDir.listFiles()?.map { it.absolutePath }?.toTypedArray()?.let { arr ->
                if (arr.isNotEmpty()) MediaScannerConnection.scanFile(this@MainActivity, arr, null, null)
            }
            val pub = OutputSaver.publishToDownloads(this@MainActivity, outDir, outDir.name)
            appendLog("Yuklamalarda: $pub")
        } catch (e: Exception) {
            appendLog("Publish: ${e.message}")
        }

        // Clean cache
        cacheFile.delete()

        return outDir
    }

    private fun updateStatus(resId: Int) {
        runOnUiThread { binding.tvStatus.setText(resId) }
    }

    private fun appendLog(msg: String) {
        runOnUiThread {
            binding.tvLog.append("$msg\n")
        }
    }

    private fun queryDisplayName(uri: Uri): String? {
        contentResolver.query(uri, null, null, null, null)?.use { c ->
            val idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (idx >= 0 && c.moveToFirst()) return c.getString(idx)
        }
        return null
    }
}
