package com.aira.assistant

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
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
import java.util.Locale

class WakeWordService : Service() {
    private val handler = Handler(Looper.getMainLooper())
    private var speechRecognizer: SpeechRecognizer? = null
    private var listening = false
    private var waitingForCommand = false
    private val restartListening = Runnable { startRecognition() }
    private val clearWaitingForCommand = Runnable { waitingForCommand = false }

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
        return START_NOT_STICKY
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
                    if (error == SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS ||
                        error == SpeechRecognizer.ERROR_CLIENT
                    ) {
                        stopSelf()
                    } else {
                        handler.removeCallbacks(restartListening)
                        handler.postDelayed(restartListening, RETRY_DELAY_MS)
                    }
                }

                override fun onResults(results: Bundle?) {
                    listening = false
                    val phrase = results
                        ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        ?.firstOrNull()
                    if (phrase != null) notifyIfWakePhraseHeard(phrase)
                    handler.removeCallbacks(restartListening)
                    handler.postDelayed(restartListening, RETRY_DELAY_MS)
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
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault().toLanguageTag())
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        }
        try {
            listening = true
            recognizer.startListening(request)
        } catch (_: SecurityException) {
            listening = false
            stopSelf()
        } catch (_: IllegalStateException) {
            listening = false
            handler.removeCallbacks(restartListening)
            handler.postDelayed(restartListening, RETRY_DELAY_MS)
        }
    }

    private fun notifyIfWakePhraseHeard(recognizedText: String) {
        val normalized = recognizedText.lowercase(Locale.ROOT)
            .replace(Regex("[^\\p{L}\\p{N}\\s]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
        val wakeIndex = normalized.indexOf(WAKE_PHRASE)
        if (wakeIndex < 0) {
            if (waitingForCommand) {
                waitingForCommand = false
                handler.removeCallbacks(clearWaitingForCommand)
                handleRecognizedCommand(recognizedText.trim())
            }
            return
        }

        val rawWakePhrase = Regex("""hey\s+max""", RegexOption.IGNORE_CASE).find(recognizedText)
        val command = rawWakePhrase?.let {
            recognizedText.substring(it.range.last + 1).trim().trimStart(',', '.', ':', ';', '-', ' ')
        }.orEmpty()
        if (command.isBlank()) {
            waitingForCommand = true
            handler.removeCallbacks(clearWaitingForCommand)
            handler.postDelayed(clearWaitingForCommand, COMMAND_TIMEOUT_MS)
            postAssistantNotification(
                title = "Max heard “Hey Max”",
                message = "Say a command now, or tap to open Max."
            )
            return
        }

        waitingForCommand = false
        handler.removeCallbacks(clearWaitingForCommand)
        handleRecognizedCommand(command)
    }

    private fun handleRecognizedCommand(command: String) {
        val typedText = command.replaceFirst(
            Regex("""^(?:type|write|enter)\s+""", RegexOption.IGNORE_CASE),
            ""
        ).trim()
        if (typedText != command.trim()) {
            if (AiraAccessibilityService.typeIntoFocusedField(typedText)) {
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

        if (command.startsWith("open ", ignoreCase = true)) {
            val appName = command.substringAfter(' ').trim().trimEnd('.', ',', '!', '?')
            if (appName.isBlank()) {
                openAssistantWithCommand(command, "Say the name of the app you want to open.")
                return
            }
            val appIntent = findAppLaunchIntent(appName)
            if (appIntent != null) {
                try {
                    startActivity(appIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    postAssistantNotification(
                        title = "Max is opening $appName",
                        message = "If Android blocks the launch, tap to open it.",
                        targetIntent = appIntent
                    )
                } catch (_: SecurityException) {
                    postAssistantNotification(
                        title = "Tap to open $appName",
                        message = "Android needs your confirmation to open this app.",
                        targetIntent = appIntent
                    )
                }
            } else {
                openAssistantWithCommand(command, "I couldn’t find $appName. Tap to see the app list.")
            }
            return
        }

        openAssistantWithCommand(command, "Tap to review your recognized command.")
    }

    private fun findAppLaunchIntent(appName: String): Intent? {
        val launcherIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val apps = packageManager.queryIntentActivities(launcherIntent, 0)
        val app = apps.firstOrNull {
            it.loadLabel(packageManager).toString().equals(appName, ignoreCase = true)
        } ?: apps.firstOrNull {
            it.loadLabel(packageManager).toString().startsWith(appName, ignoreCase = true)
        } ?: apps.firstOrNull {
            it.loadLabel(packageManager).toString().contains(appName, ignoreCase = true)
        } ?: return null
        return packageManager.getLaunchIntentForPackage(app.activityInfo.packageName)
    }

    private fun openAssistantWithCommand(command: String, message: String) {
        postAssistantNotification("Max heard “Hey Max”", message) {
            putExtra(EXTRA_COMMAND, command)
        }
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
        waitingForCommand = false
        speechRecognizer?.cancel()
        speechRecognizer?.destroy()
        speechRecognizer = null
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

        private const val CHANNEL_ID = "max_wake_phrase"
        private const val NOTIFICATION_ID = 3101
        private const val WAKE_NOTIFICATION_ID = 3102
        private const val OPEN_REQUEST_CODE = 3103
        private const val STOP_REQUEST_CODE = 3104
        private const val RETRY_DELAY_MS = 900L
        private const val COMMAND_TIMEOUT_MS = 8_000L
        private const val WAKE_PHRASE = "hey max"
    }
}
