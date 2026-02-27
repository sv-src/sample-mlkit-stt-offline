package com.example.mlkit_stt

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.FloatingActionButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.google.mlkit.genai.common.DownloadStatus
import com.google.mlkit.genai.common.FeatureStatus
import com.google.mlkit.genai.common.audio.AudioSource
import com.google.mlkit.genai.speechrecognition.SpeechRecognition
import com.google.mlkit.genai.speechrecognition.SpeechRecognizer
import com.google.mlkit.genai.speechrecognition.SpeechRecognizerOptions
import com.google.mlkit.genai.speechrecognition.SpeechRecognizerResponse
import com.google.mlkit.genai.speechrecognition.speechRecognizerOptions
import com.google.mlkit.genai.speechrecognition.speechRecognizerRequest
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.util.Locale

class MainActivity : ComponentActivity() {

    companion object {
        private const val TAG = "SpeechEchoApp"
    }

    private val enLocale = Locale.forLanguageTag("en-US")
    private val deLocale = Locale.forLanguageTag("de-DE")

    private var activeRecognizer: SpeechRecognizer? = null
    private var tts: TextToSpeech? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private var recognitionJob: Job? = null
    private var sttDoneMs: Long = 0L    // timestamp when FinalTextResponse is received
    private var listenStartMs: Long = 0L // timestamp when mic opens / recognizer starts

    // Compose-observable state
    private var status by mutableStateOf("Initializing...")
    private var recognizedText by mutableStateOf("")
    private var isListening by mutableStateOf(false)
    private var micEnabled by mutableStateOf(false)
    private var isDownloading by mutableStateOf(false)
    private var selectedLocale by mutableStateOf(Locale.forLanguageTag("en-US"))

    private val micPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            micEnabled = true
            status = "Tap the mic to speak."
        } else {
            status = "Microphone permission denied."
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    SpeechEchoScreen(
                        status = status,
                        recognizedText = recognizedText,
                        isListening = isListening,
                        micEnabled = micEnabled,
                        isDownloading = isDownloading,
                        selectedLocale = selectedLocale,
                        onLanguageChange = { switchLanguage(it) },
                        onMicClick = { if (isListening) stopListening() else startListening() }
                    )
                }
            }
        }

        initTts()
        setupMlKitRecognizers()
    }

    // ── TTS ──────────────────────────────────────────────────────────────────

    private fun initTts() {
        tts = TextToSpeech(this) { initStatus ->
            if (initStatus != TextToSpeech.SUCCESS) {
                status = "TTS initialization failed."
                return@TextToSpeech
            }
            val enAvail = tts?.isLanguageAvailable(enLocale) ?: TextToSpeech.LANG_NOT_SUPPORTED
            val deAvail = tts?.isLanguageAvailable(deLocale) ?: TextToSpeech.LANG_NOT_SUPPORTED

            val missing = buildList {
                if (enAvail == TextToSpeech.LANG_MISSING_DATA) add("English (en-US)")
                if (deAvail == TextToSpeech.LANG_MISSING_DATA) add("German (de-DE)")
            }
            if (missing.isNotEmpty()) {
                Log.d(TAG, "TTS models missing: $missing — opening installer")
                startActivity(
                    Intent(TextToSpeech.Engine.ACTION_INSTALL_TTS_DATA)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            }
            if (enAvail != TextToSpeech.LANG_MISSING_DATA) {
                tts?.setLanguage(enLocale)
            }
        }
    }

    // ── ML Kit STT setup ─────────────────────────────────────────────────────

    /**
     * On startup:
     *  1. Creates en-US recognizer and downloads model if needed (blocks mic until ready).
     *  2. Downloads de-DE model in the background so switching to German is instant.
     */
    private fun setupMlKitRecognizers() {
        lifecycleScope.launch {

            // ── English (default, required first) ──
            val enOptions = speechRecognizerOptions {
                locale = enLocale
                preferredMode = SpeechRecognizerOptions.Mode.MODE_BASIC
            }
            activeRecognizer = SpeechRecognition.getClient(enOptions)

            when (activeRecognizer!!.checkStatus()) {
                FeatureStatus.AVAILABLE -> {
                    Log.d(TAG, "en-US model ready")
                    setupMicPermission()
                }
                FeatureStatus.DOWNLOADABLE, FeatureStatus.DOWNLOADING -> {
                    downloadModel(activeRecognizer!!, "English")
                }
                FeatureStatus.UNAVAILABLE -> {
                    status = "On-device STT not supported on this device.\n" +
                            "Requires Android 12+ with a locked bootloader."
                    Log.e(TAG, "en-US STT unavailable")
                }
            }

            // ── German (background, so switching later is instant) ──
            launch {
                try {
                    val deOptions = speechRecognizerOptions {
                        locale = deLocale
                        preferredMode = SpeechRecognizerOptions.Mode.MODE_BASIC
                    }
                    val deRecognizer = SpeechRecognition.getClient(deOptions)
                    when (deRecognizer.checkStatus()) {
                        FeatureStatus.DOWNLOADABLE, FeatureStatus.DOWNLOADING -> {
                            Log.d(TAG, "Downloading de-DE model in background…")
                            deRecognizer.download().collect { dl ->
                                when (dl) {
                                    is DownloadStatus.DownloadCompleted ->
                                        Log.d(TAG, "de-DE model downloaded")
                                    is DownloadStatus.DownloadFailed ->
                                        Log.w(TAG, "de-DE model download failed")
                                    else -> {}
                                }
                            }
                        }
                        FeatureStatus.AVAILABLE -> Log.d(TAG, "de-DE model already available")
                        else -> Log.w(TAG, "de-DE unavailable on this device")
                    }
                    deRecognizer.close()
                } catch (e: Exception) {
                    Log.w(TAG, "de-DE model check failed: ${e.message}")
                }
            }
        }
    }

    /** Downloads [recognizer]'s model and enables mic when complete. */
    private suspend fun downloadModel(recognizer: SpeechRecognizer, langName: String) {
        isDownloading = true
        status = "Downloading $langName STT model…"
        Log.d(TAG, "Downloading $langName model…")
        recognizer.download().collect { dl ->
            when (dl) {
                is DownloadStatus.DownloadProgress ->
                    Log.d(TAG, "$langName download in progress…")
                is DownloadStatus.DownloadCompleted -> {
                    isDownloading = false
                    Log.d(TAG, "$langName model downloaded")
                    setupMicPermission()
                }
                is DownloadStatus.DownloadFailed -> {
                    isDownloading = false
                    status = "$langName model download failed. Check your internet connection."
                    Log.e(TAG, "$langName model download failed")
                }
                else -> {}
            }
        }
    }

    // ── Language switching ────────────────────────────────────────────────────

    /**
     * Switches both STT and TTS to [locale].
     * The model for the new locale should already be downloaded from startup;
     * if not, it is downloaded on demand before enabling the mic.
     */
    private fun switchLanguage(locale: Locale) {
        if (locale == selectedLocale) return
        if (isListening) stopListening()

        selectedLocale = locale
        micEnabled = false
        recognizedText = ""

        lifecycleScope.launch {
            // Close old recognizer and create one for the new locale
            activeRecognizer?.close()
            val options = speechRecognizerOptions {
                this.locale = locale
                preferredMode = SpeechRecognizerOptions.Mode.MODE_BASIC
            }
            val newRecognizer = SpeechRecognition.getClient(options)
            activeRecognizer = newRecognizer

            when (newRecognizer.checkStatus()) {
                FeatureStatus.AVAILABLE -> setupMicPermission()
                FeatureStatus.DOWNLOADABLE, FeatureStatus.DOWNLOADING ->
                    downloadModel(newRecognizer, locale.displayLanguage)
                else -> {
                    status = "${locale.displayLanguage} STT not available on this device."
                    Log.e(TAG, "${locale.displayLanguage} STT unavailable")
                }
            }

            // Update TTS to the same locale
            tts?.setLanguage(locale)
        }
    }

    // ── Permission ────────────────────────────────────────────────────────────

    private fun setupMicPermission() {
        if (ContextCompat.checkSelfPermission(
                this, Manifest.permission.RECORD_AUDIO
            ) == PackageManager.PERMISSION_GRANTED
        ) {
            micEnabled = true
            status = "Tap the mic to speak."
        } else {
            micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    // ── Recognition ───────────────────────────────────────────────────────────

    private fun startListening() {
        recognitionJob?.cancel()
        isListening = true
        recognizedText = ""
        status = "Listening…"

        listenStartMs = System.currentTimeMillis()
        recognitionJob = lifecycleScope.launch {
            try {
                val request = speechRecognizerRequest {
                    audioSource = AudioSource.fromMic()
                }
                activeRecognizer!!.startRecognition(request).collect { response ->
                    when (response) {
                        is SpeechRecognizerResponse.PartialTextResponse ->
                            recognizedText = response.text
                        is SpeechRecognizerResponse.FinalTextResponse -> {
                            val text = response.text
                            recognizedText = text
                            isListening = false
                            sttDoneMs = System.currentTimeMillis()
                            val sttDurationMs = sttDoneMs - listenStartMs
                            Log.i(TAG, "STT duration (mic open → text ready): ${sttDurationMs}ms")
                            activeRecognizer?.stopRecognition()
                            speakBack(text)
                        }
                        is SpeechRecognizerResponse.CompletedResponse -> {
                            isListening = false
                            if (recognizedText.isEmpty())
                                status = "No speech detected. Tap to try again."
                        }
                        is SpeechRecognizerResponse.ErrorResponse -> {
                            isListening = false
                            status = "Recognition error. Tap to try again."
                            Log.e(TAG, "STT error: $response")
                        }
                    }
                }
            } catch (e: Exception) {
                isListening = false
                status = "Recognition failed: ${e.message}"
                Log.e(TAG, "startRecognition exception", e)
            }
        }
    }

    private fun stopListening() {
        isListening = false
        recognitionJob?.cancel()
        lifecycleScope.launch { activeRecognizer?.stopRecognition() }
        status = "Tap the mic to speak."
    }

    // ── TTS echo ─────────────────────────────────────────────────────────────

    private fun speakBack(text: String) {
        status = "Speaking back…"
        tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {
                val latencyMs = System.currentTimeMillis() - sttDoneMs
                Log.i(TAG, "STT→TTS latency: ${latencyMs}ms")
            }
            override fun onDone(utteranceId: String?) {
                mainHandler.post { status = "Tap the mic to speak again." }
            }
            @Deprecated("Deprecated in API 21", ReplaceWith("onError(utteranceId, errorCode)"))
            override fun onError(utteranceId: String?) {
                mainHandler.post { status = "TTS error. Tap to try again." }
            }
            override fun onError(utteranceId: String?, errorCode: Int) {
                mainHandler.post { status = "TTS error ($errorCode). Tap to try again." }
            }
        })
        Log.i(TAG, "TTS speaking: \"$text\"")
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "ECHO_UTTERANCE")
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    override fun onResume() {
        super.onResume()
        if (tts != null) {
            val avail = tts?.isLanguageAvailable(selectedLocale) ?: TextToSpeech.LANG_NOT_SUPPORTED
            if (avail != TextToSpeech.LANG_MISSING_DATA) tts?.setLanguage(selectedLocale)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        recognitionJob?.cancel()
        activeRecognizer?.close()
        tts?.stop()
        tts?.shutdown()
    }
}

// ── Compose UI ────────────────────────────────────────────────────────────────

@Composable
fun SpeechEchoScreen(
    status: String,
    recognizedText: String,
    isListening: Boolean,
    micEnabled: Boolean,
    isDownloading: Boolean,
    selectedLocale: Locale,
    onLanguageChange: (Locale) -> Unit,
    onMicClick: () -> Unit
) {
    val enLocale = Locale.forLanguageTag("en-US")
    val deLocale = Locale.forLanguageTag("de-DE")
    val languages = listOf(enLocale to "EN", deLocale to "DE")

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 24.dp, vertical = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = "Speech Echo",
            style = MaterialTheme.typography.headlineMedium
        )

        // Language selector — disabled while listening or downloading
        SingleChoiceSegmentedButtonRow(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 16.dp)
        ) {
            languages.forEachIndexed { index, (locale, label) ->
                SegmentedButton(
                    selected = selectedLocale == locale,
                    onClick = { onLanguageChange(locale) },
                    enabled = !isListening && !isDownloading,
                    shape = SegmentedButtonDefaults.itemShape(index = index, count = languages.size),
                    label = { Text(label) }
                )
            }
        }

        // Recognized text card
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .padding(vertical = 20.dp),
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant
            )
        ) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                if (recognizedText.isEmpty()) {
                    Text(
                        text = "Your words will appear here",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(24.dp)
                    )
                } else {
                    Text(
                        text = recognizedText,
                        style = MaterialTheme.typography.headlineSmall,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(24.dp)
                    )
                }
            }
        }

        // Indeterminate progress bar — visible only while a model is downloading
        if (isDownloading) {
            LinearProgressIndicator(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 8.dp)
            )
        }

        // Status label
        Text(
            text = status,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.65f),
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(bottom = 24.dp)
        )

        // Mic FAB — turns red with Stop icon while listening
        FloatingActionButton(
            onClick = { if (micEnabled) onMicClick() },
            shape = CircleShape,
            containerColor = if (isListening)
                MaterialTheme.colorScheme.error
            else
                MaterialTheme.colorScheme.primary,
            elevation = FloatingActionButtonDefaults.elevation(
                defaultElevation = if (isListening) 12.dp else 6.dp
            ),
            modifier = Modifier.size(80.dp)
        ) {
            Icon(
                imageVector = if (isListening) Icons.Filled.Stop else Icons.Filled.Mic,
                contentDescription = if (isListening) "Stop listening" else "Start listening",
                modifier = Modifier.size(36.dp),
                tint = MaterialTheme.colorScheme.onPrimary
            )
        }

        Spacer(modifier = Modifier.height(16.dp))
    }
}
