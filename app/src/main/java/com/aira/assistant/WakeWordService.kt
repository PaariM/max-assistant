package com.aira.assistant

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import androidx.core.content.ContextCompat
import java.util.Locale

class WakeWordService : Service() {
    private val handler = Handler(Looper.getMainLooper())
    private var speechRecognizer: SpeechRecognizer? = null
    private var listening = false
    private var waitingForCommand = false
    private var recognitionRetryAttempt = 0
    private var textToSpeech: TextToSpeech? = null
    private var textToSpeechReady = false
    private var speechOutputInProgress = false
    private val restartListening = Runnable { startRecognition() }
    private val clearWaitingForCommand = Runnable { waitingForCommand = false }
    private val restartAfterSpeech = Runnable {
        speechOutputInProgress = false
        handler.removeCallbacks(restartListening)
        handler.postDelayed(restartListening, SPEECH_RESTART_DELAY_MS)
    }

    override fun onCreate() {
        super.onCreate()
        textToSpeech = TextToSpeech(this) { result ->
            textToSpeechReady = result == TextToSpeech.SUCCESS
            if (textToSpeechReady) {
                val languageTag = getSharedPreferences(PREFERENCES_NAME, MODE_PRIVATE)
                    .getString(PREFERENCE_LANGUAGE, Locale.getDefault().toLanguageTag())
                    ?: Locale.getDefault().toLanguageTag()
                textToSpeech?.language = Locale.forLanguageTag(languageTag)
                textToSpeech?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) = Unit

                    override fun onDone(utteranceId: String?) {
                        if (utteranceId?.startsWith(VOICE_UTTERANCE_PREFIX) == true) {
                            handler.post(restartAfterSpeech)
                        }
                    }

                    @Deprecated("Deprecated in Java")
                    override fun onError(utteranceId: String?) {
                        if (utteranceId?.startsWith(VOICE_UTTERANCE_PREFIX) == true) {
                            handler.post(restartAfterSpeech)
                        }
                    }
                })
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        createNotificationChannel()
        val notification = buildListeningNotification()
        if (Build.VERSION.SDK_INT >= 30) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
        getSharedPreferences(PREFERENCES_NAME, MODE_PRIVATE)
            .edit()
            .putBoolean(PREFERENCE_LISTENING, true)
            .apply()
        if (speechRecognizer == null) createSpeechRecognizer()
        startRecognition()
        return START_STICKY
    }

    private fun createSpeechRecognizer() {
        speechRecognizer = if (
            Build.VERSION.SDK_INT >= 31 &&
            SpeechRecognizer.isOnDeviceRecognitionAvailable(this)
        ) {
            SpeechRecognizer.createOnDeviceSpeechRecognizer(this)
        } else {
            SpeechRecognizer.createSpeechRecognizer(this)
        }.also { recognizer ->
            recognizer.setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) = Unit
                override fun onBeginningOfSpeech() = Unit
                override fun onRmsChanged(rmsdB: Float) = Unit
                override fun onBufferReceived(buffer: ByteArray?) = Unit
                override fun onEndOfSpeech() = Unit
                override fun onEvent(eventType: Int, params: Bundle?) = Unit

                override fun onError(error: Int) {
                    listening = false
                    if (error == SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS) {
                        stopSelf()
                    } else {
                        scheduleRecognitionRetry()
                    }
                }

                override fun onResults(results: Bundle?) {
                    listening = false
                    recognitionRetryAttempt = 0
                    val alternatives = results
                        ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        .orEmpty()
                    val phrase = alternatives.firstOrNull(::containsWakePhrase)
                        ?: alternatives.firstOrNull()
                    if (phrase != null) notifyIfWakePhraseHeard(phrase)
                    if (!speechOutputInProgress) {
                        handler.removeCallbacks(restartListening)
                        handler.postDelayed(
                            restartListening,
                            if (waitingForCommand) WAKE_ACK_RETRY_DELAY_MS else RETRY_DELAY_MS
                        )
                    }
                }

                override fun onPartialResults(partialResults: Bundle?) = Unit
            })
        }
    }

    private fun startRecognition() {
        val recognizer = speechRecognizer ?: return
        if (listening) return
        val request = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(
                RecognizerIntent.EXTRA_LANGUAGE,
                getSharedPreferences(PREFERENCES_NAME, MODE_PRIVATE)
                    .getString(PREFERENCE_LANGUAGE, Locale.getDefault().toLanguageTag())
                    ?: Locale.getDefault().toLanguageTag()
            )
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, MAX_RECOGNITION_ALTERNATIVES)
            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
        }
        try {
            listening = true
            recognizer.startListening(request)
        } catch (_: SecurityException) {
            listening = false
            stopSelf()
        } catch (_: IllegalStateException) {
            listening = false
            scheduleRecognitionRetry()
        }
    }

    private fun scheduleRecognitionRetry() {
        handler.removeCallbacks(restartListening)
        val multiplier = 1L shl recognitionRetryAttempt.coerceAtMost(MAX_RETRY_SHIFT)
        val delay = (RETRY_DELAY_MS * multiplier).coerceAtMost(MAX_RETRY_DELAY_MS)
        recognitionRetryAttempt = (recognitionRetryAttempt + 1).coerceAtMost(MAX_RETRY_SHIFT + 1)
        handler.postDelayed(restartListening, delay)
    }

    private fun containsWakePhrase(text: String): Boolean =
        WAKE_PHRASE_PATTERN.containsMatchIn(text)

    private fun notifyIfWakePhraseHeard(recognizedText: String) {
        val rawWakePhrase = WAKE_PHRASE_PATTERN.find(recognizedText)
        if (rawWakePhrase == null) {
            if (waitingForCommand) {
                waitingForCommand = false
                handler.removeCallbacks(clearWaitingForCommand)
                handleRecognizedCommand(recognizedText.trim())
            }
            return
        }

        val command = recognizedText.substring(rawWakePhrase.range.last + 1)
            .trim()
            .trimStart(',', '.', ':', ';', '-', ' ')
        if (command.isBlank()) {
            waitingForCommand = true
            handler.removeCallbacks(clearWaitingForCommand)
            handler.postDelayed(clearWaitingForCommand, COMMAND_TIMEOUT_MS)
            speakResponse("I'm listening.")
            postAssistantNotification(
                title = "Max heard “Hey Max”",
                message = "Max is listening for your command now."
            )
            return
        }

        waitingForCommand = false
        handler.removeCallbacks(clearWaitingForCommand)
        handleRecognizedCommand(command)
    }

    private fun handleRecognizedCommand(command: String) {
        val messageRequest = MESSAGE_TO_CONTACT_PATTERN.matchEntire(command.trim())
        if (messageRequest != null) {
            openContactMessage(
                command,
                messageRequest.groupValues[1].trim(),
                messageRequest.groupValues[2].trim()
            )
            return
        }

        val typedText = command.replaceFirst(
            Regex("""^(?:type|write|enter)\s+""", RegexOption.IGNORE_CASE),
            ""
        ).trim()
        if (typedText != command.trim()) {
            if (AiraAccessibilityService.typeIntoFocusedField(typedText)) {
                speakResponse("Text added. Review it before sending.")
                postAssistantNotification(
                    title = "Max added a text draft",
                    message = "Review the focused field. Max did not press Send.",
                    openMainActivity = false
                )
            } else {
                openAssistantWithCommand(
                    command,
                    "Enable Max focused text entry in Accessibility settings and focus a text field first."
                )
            }
            return
        }

        val openRequest = Regex(
            """^(?:please\s+)?(?:(?:open|launch|start|run)\s+|go\s+to\s+)(.+)$""",
            RegexOption.IGNORE_CASE
        ).matchEntire(command.trim())
        if (openRequest != null) {
            val appName = openRequest.groupValues[1].trim().trimEnd('.', ',', '!', '?')
            val appIntent = findInstalledAppLaunchIntent(this, appName)
            if (appIntent != null) {
                try {
                    startActivity(appIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    speakResponse("Opening $appName.")
                    postAssistantNotification(
                        title = "Max is opening $appName",
                        message = "If Android blocks the launch, tap to open it.",
                        targetIntent = appIntent
                    )
                } catch (_: ActivityNotFoundException) {
                    openAssistantWithCommand(command, "Android couldn’t launch $appName. Open Max and choose it from the app list.")
                } catch (_: SecurityException) {
                    speakResponse("Android needs you to tap the notification to open $appName.")
                    postAssistantNotification(
                        title = "Tap to open $appName",
                        message = "Android needs your confirmation to open this app.",
                        targetIntent = appIntent
                    )
                }
            } else {
                openAssistantWithCommand(command, "I couldn’t find $appName. Open Max and say “show apps” to view available apps.")
            }
            return
        }

        openAssistantWithCommand(command, "Tap to review your recognized command.")
    }

    private fun openContactMessage(command: String, message: String, contactName: String) {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_CONTACTS) !=
            android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            openAssistantWithCommand(
                command,
                "Enable voice contact lookup in Max first. Then I can open a draft for the named contact."
            )
            return
        }

        val phoneNumbers = try {
            matchingContactPhoneNumbers(this, contactName)
        } catch (_: SecurityException) {
            emptyList()
        }
        val packageName = findWhatsAppPackageName(this)
        if (phoneNumbers.size != 1 || packageName == null) {
            val reason = when {
                packageName == null -> "WhatsApp isn’t installed."
                phoneNumbers.isEmpty() -> "I couldn’t find one exact contact match for $contactName."
                else -> "$contactName has more than one phone number."
            }
            openAssistantWithCommand(command, "$reason Tap to choose the contact and review the message.")
            return
        }

        val launchIntent = createWhatsAppDraftIntent(packageName, phoneNumbers.single(), message)
            ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (launchIntent == null) {
            openAssistantWithCommand(command, "I couldn’t prepare that WhatsApp draft.")
            return
        }
        try {
            startActivity(launchIntent)
            speakResponse("WhatsApp draft ready for $contactName. Review it before sending.")
            postAssistantNotification(
                title = "WhatsApp draft ready for $contactName",
                message = "Review the message and tap Send yourself.",
                targetIntent = launchIntent
            )
        } catch (_: ActivityNotFoundException) {
            openAssistantWithCommand(command, "I couldn’t open WhatsApp. Tap to review the message.")
        } catch (_: SecurityException) {
            openAssistantWithCommand(command, "Android blocked this background launch. Tap to review the message.")
        }
    }

    private fun openAssistantWithCommand(command: String, message: String) {
        speakResponse(message)
        postAssistantNotification("Max heard “Hey Max”", message) {
            putExtra(EXTRA_COMMAND, command)
        }
    }

    private fun speakResponse(text: String) {
        if (!textToSpeechReady) return
        speechOutputInProgress = true
        handler.removeCallbacks(restartListening)
        handler.removeCallbacks(restartAfterSpeech)
        textToSpeech?.speak(
            text,
            TextToSpeech.QUEUE_FLUSH,
            null,
            "$VOICE_UTTERANCE_PREFIX${System.nanoTime()}"
        )
        handler.postDelayed(restartAfterSpeech, SPEECH_MAX_DURATION_MS)
    }

    private fun postAssistantNotification(
        title: String,
        message: String,
        targetIntent: Intent? = null,
        openMainActivity: Boolean = true,
        configureIntent: Intent.() -> Unit = {}
    ) {
        val launchIntent = (targetIntent ?: if (openMainActivity) {
            Intent(this, MainActivity::class.java)
        } else {
            null
        })?.apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            configureIntent()
        }
        val openTarget = launchIntent?.let {
            PendingIntent.getActivity(
                this,
                OPEN_REQUEST_CODE,
                it,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        }
        val builder = Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle(title)
            .setContentText(message)
            .setAutoCancel(true)
        if (openTarget != null) builder.setContentIntent(openTarget)
        val notification = builder.build()
        getSystemService(NotificationManager::class.java).notify(WAKE_NOTIFICATION_ID, notification)
    }

    private fun buildListeningNotification(): Notification {
        val stopIntent = PendingIntent.getService(
            this,
            STOP_REQUEST_CODE,
            Intent(this, WakeWordService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("Max wake phrase is on")
            .setContentText("Microphone active. Say “Hey Max” to wake me.")
            .setOngoing(true)
            .setCategory(Notification.CATEGORY_SERVICE)
            .addAction(
                Notification.Action.Builder(
                    null,
                    "Stop listening",
                    stopIntent
                ).build()
            )
            .build()
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Max wake phrase",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Shows when optional wake phrase listening is active."
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        speechOutputInProgress = false
        waitingForCommand = false
        speechRecognizer?.cancel()
        speechRecognizer?.destroy()
        speechRecognizer = null
        textToSpeech?.stop()
        textToSpeech?.shutdown()
        textToSpeech = null
        getSharedPreferences(PREFERENCES_NAME, MODE_PRIVATE)
            .edit()
            .putBoolean(PREFERENCE_LISTENING, false)
            .apply()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val ACTION_START = "com.aira.assistant.action.START_WAKE"
        const val ACTION_STOP = "com.aira.assistant.action.STOP_WAKE"
        const val EXTRA_COMMAND = "com.aira.assistant.extra.WAKE_COMMAND"
        const val PREFERENCES_NAME = "aira_assistant"
        const val PREFERENCE_LISTENING = "wake_phrase_listening"
        const val PREFERENCE_LANGUAGE = "speech_language"

        private const val CHANNEL_ID = "max_wake_phrase"
        private const val NOTIFICATION_ID = 3101
        private const val WAKE_NOTIFICATION_ID = 3102
        private const val OPEN_REQUEST_CODE = 3103
        private const val STOP_REQUEST_CODE = 3104
        private const val RETRY_DELAY_MS = 900L
        private const val WAKE_ACK_RETRY_DELAY_MS = 1_500L
        private const val SPEECH_RESTART_DELAY_MS = 350L
        private const val SPEECH_MAX_DURATION_MS = 12_000L
        private const val VOICE_UTTERANCE_PREFIX = "max-voice-"
        private const val MAX_RETRY_DELAY_MS = 30_000L
        private const val MAX_RETRY_SHIFT = 5
        private const val MAX_RECOGNITION_ALTERNATIVES = 3
        private const val COMMAND_TIMEOUT_MS = 8_000L
        private val WAKE_PHRASE_PATTERN =
            Regex("""\bhey[\s,.:;'-]+(?:max|maks|macs)\b""", RegexOption.IGNORE_CASE)
        private val MESSAGE_TO_CONTACT_PATTERN =
            Regex("""(?:say|send|text|message)\s+(.+?)\s+to\s+(.+)""", RegexOption.IGNORE_CASE)
    }
}
