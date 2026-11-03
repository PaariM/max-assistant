package com.aira.assistant

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.Manifest
import android.content.pm.PackageManager.PERMISSION_GRANTED
import android.os.Build
import android.net.Uri
import android.os.Bundle
import android.provider.ContactsContract
import android.provider.Settings
import android.speech.RecognizerIntent
import android.speech.tts.TextToSpeech
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.Contacts
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Phone
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.layout.heightIn
import java.util.Locale

private val Midnight = Color(0xFF101322)
private val Panel = Color(0xFF1B2033)
private val Teal = Color(0xFF71E0D0)
private val Muted = Color(0xFF9BA4B9)

data class InstalledApp(val name: String, val packageName: String)

fun findInstalledAppLaunchIntent(context: Context, requestedName: String): Intent? {
    val cleanedRequest = requestedName
        .trim()
        .replace(Regex("""^(?:please\s+)?(?:(?:open|launch|start|run)\s+|go\s+to\s+)""", RegexOption.IGNORE_CASE), "")
        .replace(Regex("""^(?:my|the)\s+""", RegexOption.IGNORE_CASE), "")
        .replace(Regex("""\s+(?:app|application|please)$""", RegexOption.IGNORE_CASE), "")
        .trim()
    val query = cleanedRequest.lowercase(Locale.ROOT)
        .replace(Regex("""[^\p{L}\p{N}]"""), "")
    if (query.isBlank()) return null

    val launcherIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
    val candidates = context.packageManager.queryIntentActivities(launcherIntent, PackageManager.MATCH_ALL)
        .mapNotNull { result ->
            val activityInfo = result.activityInfo ?: return@mapNotNull null
            if (activityInfo.packageName == context.packageName) return@mapNotNull null
            val label = result.loadLabel(context.packageManager).toString()
            val normalizedLabel = label.lowercase(Locale.ROOT)
                .replace(Regex("""[^\p{L}\p{N}]"""), "")
            Triple(label, normalizedLabel, activityInfo.packageName)
        }
    val match = candidates.firstOrNull { it.second == query }
        ?: candidates.firstOrNull { it.second.startsWith(query) }
        ?: candidates.firstOrNull { it.second.contains(query) }
        ?: return null
    return context.packageManager.getLaunchIntentForPackage(match.third)
}

private data class SpeechLanguage(val tag: String, val name: String)

private val speechLanguages = listOf(
    SpeechLanguage("en-US", "English"),
    SpeechLanguage("hi-IN", "हिन्दी"),
    SpeechLanguage("es-ES", "Español"),
    SpeechLanguage("fr-FR", "Français"),
    SpeechLanguage("de-DE", "Deutsch"),
    SpeechLanguage("pt-BR", "Português"),
    SpeechLanguage("ar-SA", "العربية"),
    SpeechLanguage("zh-CN", "中文"),
    SpeechLanguage("ja-JP", "日本語"),
    SpeechLanguage("ko-KR", "한국어"),
    SpeechLanguage("it-IT", "Italiano"),
    SpeechLanguage("ru-RU", "Русский"),
    SpeechLanguage("bn-BD", "বাংলা"),
    SpeechLanguage("ur-PK", "اردو"),
    SpeechLanguage("pa-IN", "ਪੰਜਾਬੀ"),
    SpeechLanguage("ta-IN", "தமிழ்"),
    SpeechLanguage("te-IN", "తెలుగు"),
    SpeechLanguage("mr-IN", "मराठी"),
    SpeechLanguage("gu-IN", "ગુજરાતી"),
    SpeechLanguage("kn-IN", "ಕನ್ನಡ"),
    SpeechLanguage("ml-IN", "മലയാളം"),
    SpeechLanguage("id-ID", "Bahasa Indonesia"),
    SpeechLanguage("tr-TR", "Türkçe"),
    SpeechLanguage("vi-VN", "Tiếng Việt"),
    SpeechLanguage("th-TH", "ไทย"),
    SpeechLanguage("nl-NL", "Nederlands"),
    SpeechLanguage("pl-PL", "Polski"),
    SpeechLanguage("uk-UA", "Українська"),
    SpeechLanguage("fa-IR", "فارسی"),
    SpeechLanguage("sw-KE", "Kiswahili")
)

private fun isAccessibilityServiceEnabled(context: Context): Boolean {
    val serviceName = ComponentName(context, AiraAccessibilityService::class.java).flattenToString()
    return Settings.Secure.getString(
        context.contentResolver,
        Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
    ).orEmpty().split(':').any { it.equals(serviceName, ignoreCase = true) }
}

private fun openWhatsAppDraft(context: Context, packageName: String, phoneNumber: String, message: String): Boolean {
    val digits = phoneNumber.filter(Char::isDigit)
    if (digits.isBlank()) return false
    val uri = Uri.Builder()
        .scheme("https")
        .authority("wa.me")
        .appendPath(digits)
        .apply { if (message.isNotBlank()) appendQueryParameter("text", message) }
        .build()
    return try {
        context.startActivity(
            Intent(Intent.ACTION_VIEW, uri)
                .setPackage(packageName)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
        true
    } catch (_: ActivityNotFoundException) {
        false
    }
}

private fun shareMessage(context: Context, text: String, packageName: String? = null): Boolean {
    val sendIntent = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_TEXT, text)
        packageName?.let(::setPackage)
    }
    return try {
        context.startActivity(
            Intent.createChooser(sendIntent, "Choose a chat and review your message")
        )
        true
    } catch (_: ActivityNotFoundException) {
        false
    }
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val wakeCommand = intent.getStringExtra(WakeWordService.EXTRA_COMMAND)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme(
                background = Midnight,
                surface = Panel,
                primary = Teal,
                onPrimary = Midnight,
                onBackground = Color.White,
                onSurface = Color.White,
                secondary = Muted
            )) {
                AssistantScreen(wakeCommand)
            }
        }
    }
}

@Composable
private fun AssistantScreen(initialCommand: String?) {
    val context = LocalContext.current
    val activity = context as Activity
    var pendingVoiceCommand by remember { mutableStateOf(initialCommand) }
    var status by remember {
        mutableStateOf(
            if (initialCommand.isNullOrBlank()) "Ready when you are"
            else "Hey Max heard you. Processing your voice request."
        )
    }
    var wakeActive by remember {
        mutableStateOf(
            context.getSharedPreferences(WakeWordService.PREFERENCES_NAME, Context.MODE_PRIVATE)
                .getBoolean(WakeWordService.PREFERENCE_LISTENING, false)
        )
    }
    var accessibilityEnabled by remember { mutableStateOf(isAccessibilityServiceEnabled(context)) }
    var expandedApps by remember { mutableStateOf(false) }
    var pendingWhatsAppMessage by remember { mutableStateOf<String?>(null) }
    var speechLanguageTag by remember {
        mutableStateOf(
            context.getSharedPreferences(WakeWordService.PREFERENCES_NAME, Context.MODE_PRIVATE)
                .getString(WakeWordService.PREFERENCE_LANGUAGE, Locale.getDefault().toLanguageTag())
                ?: Locale.getDefault().toLanguageTag()
        )
    }
    var speechLanguageMenuExpanded by remember { mutableStateOf(false) }
    val apps = remember {
        val launcherIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        context.packageManager.queryIntentActivities(launcherIntent, PackageManager.MATCH_ALL)
            .mapNotNull { result ->
                val info = result.activityInfo ?: return@mapNotNull null
                if (info.packageName == context.packageName) return@mapNotNull null
                InstalledApp(
                    name = result.loadLabel(context.packageManager).toString(),
                    packageName = info.packageName
                )
            }
            .distinctBy { it.packageName }
            .sortedBy { it.name.lowercase(Locale.getDefault()) }
    }
    val speechLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val spokenText = result.data
            ?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
            ?.firstOrNull()
        if (result.resultCode == Activity.RESULT_OK && !spokenText.isNullOrBlank()) {
            pendingVoiceCommand = spokenText
            status = "Heard you. Working on it."
        } else {
            status = "I didn’t catch that. Try again."
        }
    }
    val contactsLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val contactUri = result.data?.data
        val message = pendingWhatsAppMessage
        pendingWhatsAppMessage = null
        if (result.resultCode != Activity.RESULT_OK || contactUri == null) {
            status = "Contact selection cancelled."
        } else if (message != null) {
            val phoneNumber = context.contentResolver.query(
                contactUri,
                arrayOf(ContactsContract.CommonDataKinds.Phone.NUMBER),
                null,
                null,
                null
            )?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) else null
            }
            val packageName = when {
                apps.any { it.packageName == "com.whatsapp" } -> "com.whatsapp"
                apps.any { it.packageName == "com.whatsapp.w4b" } -> "com.whatsapp.w4b"
                else -> null
            }
            if (phoneNumber.isNullOrBlank()) {
                status = "That contact doesn’t have a phone number to open in WhatsApp."
            } else if (packageName == null) {
                status = "WhatsApp isn’t installed. You can still share the message with another app."
                if (message.isNotBlank() && !shareMessage(context, message)) {
                    status = "No app is available to share that message."
                }
            } else if (openWhatsAppDraft(context, packageName, phoneNumber, message)) {
                status = if (message.isBlank()) {
                    "WhatsApp chat opened. Review anything you send."
                } else {
                    "WhatsApp opened with your draft. Review it and tap Send yourself."
                }
            } else {
                status = "I couldn’t open that WhatsApp chat. You can still share the text with another app."
                if (message.isNotBlank() && !shareMessage(context, message)) {
                    status = "No app is available to share that message."
                }
            }
        } else {
            status = "Contact selected. Choose Call or Message in Contacts."
        }
    }
    val accessibilitySettingsLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        accessibilityEnabled = isAccessibilityServiceEnabled(context)
        status = if (accessibilityEnabled) {
            "Focused text entry is on. Focus a text field and say “Hey Max, type …”."
        } else {
            "Focused text entry stays off until you enable it in Android Accessibility settings."
        }
    }
    val wakePermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        val microphoneGranted = grants[Manifest.permission.RECORD_AUDIO] == true ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PERMISSION_GRANTED
        val notificationsGranted = Build.VERSION.SDK_INT < 33 ||
            grants[Manifest.permission.POST_NOTIFICATIONS] == true ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PERMISSION_GRANTED
        if (microphoneGranted && notificationsGranted) {
            ContextCompat.startForegroundService(
                context,
                Intent(context, WakeWordService::class.java).setAction(WakeWordService.ACTION_START)
            )
            context.getSharedPreferences(WakeWordService.PREFERENCES_NAME, Context.MODE_PRIVATE)
                .edit()
                .putBoolean(WakeWordService.PREFERENCE_LISTENING, true)
                .apply()
            wakeActive = true
            status = "Wake phrase active. Say “Hey Max” to wake me."
        } else {
            status = "Microphone and notification permissions are needed for wake phrase listening."
        }
    }
    val textToSpeech = remember {
        TextToSpeech(context) { }
    }
    DisposableEffect(textToSpeech) {
        onDispose { textToSpeech.shutdown() }
    }

    fun reply(text: String) {
        status = text
        textToSpeech.speak(text, TextToSpeech.QUEUE_FLUSH, null, "max-reply")
    }

    fun startListening() {
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, speechLanguageTag)
            putExtra(RecognizerIntent.EXTRA_PROMPT, "Speak to Max")
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
        }
        try {
            speechLauncher.launch(intent)
            status = "Listening…"
        } catch (_: ActivityNotFoundException) {
            status = "No speech recognition service is available on this device."
        }
    }

    fun openContacts() {
        val intent = Intent(Intent.ACTION_PICK, ContactsContract.Contacts.CONTENT_URI)
        try {
            contactsLauncher.launch(intent)
        } catch (_: ActivityNotFoundException) {
            status = "No contacts app is available on this device."
        }
    }

    fun toggleWakePhrase() {
        if (wakeActive) {
            context.stopService(Intent(context, WakeWordService::class.java))
            context.getSharedPreferences(WakeWordService.PREFERENCES_NAME, Context.MODE_PRIVATE)
                .edit()
                .putBoolean(WakeWordService.PREFERENCE_LISTENING, false)
                .apply()
            wakeActive = false
            status = "Background wake phrase is off."
            return
        }
        val permissions = buildList {
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PERMISSION_GRANTED) {
                add(Manifest.permission.RECORD_AUDIO)
            }
            if (Build.VERSION.SDK_INT >= 33 &&
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PERMISSION_GRANTED
            ) {
                add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
        if (permissions.isEmpty()) {
            ContextCompat.startForegroundService(
                context,
                Intent(context, WakeWordService::class.java).setAction(WakeWordService.ACTION_START)
            )
            context.getSharedPreferences(WakeWordService.PREFERENCES_NAME, Context.MODE_PRIVATE)
                .edit()
                .putBoolean(WakeWordService.PREFERENCE_LISTENING, true)
                .apply()
            wakeActive = true
            status = "Wake phrase active. Say “Hey Max” to wake me."
        } else {
            wakePermissionLauncher.launch(permissions.toTypedArray())
        }
    }

    fun openApp(query: String): Boolean {
        val launchIntent = findInstalledAppLaunchIntent(context, query) ?: return false
        return try {
            context.startActivity(launchIntent)
            true
        } catch (_: ActivityNotFoundException) {
            false
        } catch (_: SecurityException) {
            false
        }
    }

    fun shareText(text: String, packageName: String? = null) {
        if (!shareMessage(activity, text, packageName)) {
            reply("I couldn’t find an app that can send that message.")
        }
    }

    fun openWhatsAppContact(message: String, contact: String) {
        val packageName = when {
            apps.any { it.packageName == "com.whatsapp" } -> "com.whatsapp"
            apps.any { it.packageName == "com.whatsapp.w4b" } -> "com.whatsapp.w4b"
            else -> null
        }
        if (packageName == null) {
            reply("WhatsApp isn’t installed.")
            if (message.isNotBlank()) shareText(message)
            return
        }
        pendingWhatsAppMessage = message
        reply("Choose $contact in Contacts. WhatsApp will open with your draft for you to review; Max won’t send it.")
        try {
            contactsLauncher.launch(
                Intent(
                    Intent.ACTION_PICK,
                    ContactsContract.CommonDataKinds.Phone.CONTENT_URI
                )
            )
        } catch (_: ActivityNotFoundException) {
            pendingWhatsAppMessage = null
            reply("No contacts picker is available. You can open WhatsApp and choose the chat yourself.")
        }
    }

    fun handleCommand(rawCommand: String) {
        val command = rawCommand.trim()
        if (command.isEmpty()) return
        val lower = command.lowercase(Locale.getDefault())

        val addressedCommand = command.replace(
            Regex("""\s+(?:chat|on whatsapp|via whatsapp|in whatsapp)$""", RegexOption.IGNORE_CASE),
            ""
        )
        val addressedMessage = Regex(
            """^(?:say|send|text|message)\s+(.+?)\s+to\s+(.+)$""",
            RegexOption.IGNORE_CASE
        ).matchEntire(addressedCommand)
        if (addressedMessage != null) {
            openWhatsAppContact(
                addressedMessage.groupValues[1].trim(),
                addressedMessage.groupValues[2].trim()
            )
            return
        }

        val whatsappMessage = Regex(
            """^(?:open\s+whatsapp\s+and\s+(?:type|write|send)|(?:send|message))\s+(.+?)(?:\s+(?:on|via|in|to)\s+whatsapp)?$""",
            RegexOption.IGNORE_CASE
        ).matchEntire(command)
        if (whatsappMessage != null && (
                lower.contains("whatsapp") ||
                    lower.startsWith("open whatsapp") ||
                    lower.startsWith("send ") ||
                    lower.startsWith("message ")
                )
        ) {
            val text = whatsappMessage.groupValues[1].trim()
            if (apps.none { it.packageName == "com.whatsapp" || it.packageName == "com.whatsapp.w4b" }) {
                reply("WhatsApp isn’t installed. You can still share this text with another app.")
                shareText(text)
            } else {
                val packageName = if (apps.any { it.packageName == "com.whatsapp" }) {
                    "com.whatsapp"
                } else {
                    "com.whatsapp.w4b"
                }
                reply("Opening WhatsApp with your text ready. Choose a chat, then review and send it.")
                shareText(text, packageName)
            }
            return
        }

        when {
            lower.startsWith("open chat with ") -> {
                openWhatsAppContact("", command.substringAfter("open chat with ", "").trim())
            }
            lower == "contacts" || lower == "open contacts" -> openContacts()
            lower.startsWith("type ") || lower.startsWith("write ") || lower.startsWith("enter ") -> {
                reply("For cross-app typing, enable Max’s Accessibility service, focus the text field, then say “Hey Max, type …”. I never press Send.")
            }
            lower == "show apps" || lower == "list apps" -> {
                expandedApps = true
                reply("Here are the apps available to open.")
            }
            lower.startsWith("open ") || lower.startsWith("please open ") || lower.startsWith("launch ") ||
                lower.startsWith("start ") || lower.startsWith("run ") || lower.startsWith("go to ") -> {
                if (openApp(command)) {
                    reply("Opening ${command.substringAfter(' ').trim()}.")
                } else {
                    reply("I couldn’t match that to an installed app. Say “show apps” to see the available app names.")
                }
            }
            lower.startsWith("call ") -> {
                val number = command.substringAfter(' ').filter { it.isDigit() || it == '+' }
                val intent = Intent(Intent.ACTION_DIAL, Uri.parse("tel:$number"))
                try {
                    context.startActivity(intent)
                    reply("The dialer is ready. Review the number and tap Call.")
                } catch (_: ActivityNotFoundException) {
                    reply("No phone dialer is available on this device.")
                }
            }
            lower.startsWith("message ") || lower.startsWith("send ") -> {
                val text = command.substringAfter(' ').trim()
                if (text.isNotEmpty()) {
                    reply("Choose a messaging app and review your message before sending.")
                    shareText(text)
                } else {
                    reply("Tell me what you’d like to share.")
                }
            }
            else -> reply("I can open installed apps, prepare a message to share, open Contacts, or bring up the dialer.")
        }
    }

    LaunchedEffect(pendingVoiceCommand) {
        val command = pendingVoiceCommand ?: return@LaunchedEffect
        pendingVoiceCommand = null
        handleCommand(command)
    }

    Surface(modifier = Modifier.fillMaxSize(), color = Midnight) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 18.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .background(
                            Brush.linearGradient(listOf(Teal, Color(0xFF9A8BFF))),
                            CircleShape
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Text("M", color = Midnight, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                }
                Spacer(Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text("Max", fontSize = 23.sp, fontWeight = FontWeight.Bold)
                    Text("YOUR EVERYDAY ASSISTANT", color = Muted, fontSize = 10.sp, letterSpacing = 1.5.sp)
                }
                IconButton(onClick = { expandedApps = !expandedApps }) {
                    Icon(Icons.Default.Apps, contentDescription = "Show apps", tint = Teal)
                }
            }

            Card(
                colors = CardDefaults.cardColors(containerColor = Panel),
                shape = RoundedCornerShape(28.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(
                            Brush.linearGradient(
                                listOf(Color(0xFF242C45), Color(0xFF192A3A), Panel)
                            )
                        )
                        .padding(vertical = 24.dp, horizontal = 20.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Box(
                            modifier = Modifier
                                .size(92.dp)
                                .background(Teal.copy(alpha = 0.12f), CircleShape)
                                .padding(12.dp)
                                .background(
                                    Brush.linearGradient(listOf(Teal, Color(0xFF9A8BFF))),
                                    CircleShape
                                ),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(Icons.Default.Mic, contentDescription = null, tint = Midnight, modifier = Modifier.size(34.dp))
                        }
                        Spacer(Modifier.height(14.dp))
                        Text(status, color = Color.White, fontWeight = FontWeight.Medium)
                        Text(
                            "Speech recognition availability depends on your device",
                            color = Muted,
                            fontSize = 12.sp,
                            modifier = Modifier.padding(top = 5.dp)
                        )
                        Box {
                            Button(
                                onClick = { speechLanguageMenuExpanded = true },
                                modifier = Modifier.padding(top = 10.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF30374D)),
                                shape = RoundedCornerShape(12.dp)
                            ) {
                                Text(
                                    "Language: ${speechLanguages.firstOrNull { it.tag == speechLanguageTag }?.name ?: speechLanguageTag}",
                                    color = Color.White
                                )
                            }
                            DropdownMenu(
                                expanded = speechLanguageMenuExpanded,
                                onDismissRequest = { speechLanguageMenuExpanded = false },
                                modifier = Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState())
                            ) {
                                speechLanguages.forEach { option ->
                                    DropdownMenuItem(
                                        text = { Text(option.name) },
                                        onClick = {
                                            speechLanguageTag = option.tag
                                            speechLanguageMenuExpanded = false
                                            context.getSharedPreferences(
                                                WakeWordService.PREFERENCES_NAME,
                                                Context.MODE_PRIVATE
                                            ).edit()
                                                .putString(WakeWordService.PREFERENCE_LANGUAGE, option.tag)
                                                .apply()
                                            status = "Speech language set to ${option.name}."
                                        }
                                    )
                                }
                            }
                        }
                        Button(
                            onClick = ::startListening,
                            modifier = Modifier.padding(top = 15.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = Teal),
                            shape = RoundedCornerShape(20.dp)
                        ) {
                            Icon(Icons.Default.Mic, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text("Talk to Max", color = Midnight, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                QuickAction(
                    label = "Contacts",
                    icon = { Icon(Icons.Default.Contacts, contentDescription = null) },
                    modifier = Modifier.weight(1f),
                    onClick = ::openContacts
                )
                QuickAction(
                    label = "Dial",
                    icon = { Icon(Icons.Default.Phone, contentDescription = null) },
                    modifier = Modifier.weight(1f),
                    onClick = {
                        try {
                            context.startActivity(Intent(Intent.ACTION_DIAL))
                        } catch (_: ActivityNotFoundException) {
                            status = "No phone dialer is available on this device."
                        }
                    }
                )
                QuickAction(
                    label = "Apps",
                    icon = { Icon(Icons.Default.Apps, contentDescription = null) },
                    modifier = Modifier.weight(1f),
                    onClick = { expandedApps = !expandedApps }
                )
            }

            Button(
                onClick = ::toggleWakePhrase,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (wakeActive) Color(0xFF343B50) else Color(0xFF243A42)
                ),
                shape = RoundedCornerShape(16.dp)
            ) {
                Icon(Icons.Default.Mic, contentDescription = null, tint = Teal, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(
                    if (wakeActive) "Turn off “Hey Max” listening" else "Enable “Hey Max” wake phrase",
                    color = Color.White
                )
            }
            Text(
                "Enable listening once and allow the Android microphone and notification prompts. Then say “Hey Max, open Instagram.” App launching does not need Accessibility; that separate setting is only for typing into another app.",
                color = Muted,
                fontSize = 12.sp
            )

            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = Panel),
                shape = RoundedCornerShape(16.dp)
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text("Type into another app", fontWeight = FontWeight.SemiBold)
                    Text(
                        "Enable Android Accessibility, open a chat yourself, and focus its message field. “Hey Max, type …” replaces the focused field; Max won’t read it or press Send.",
                        color = Muted,
                        fontSize = 12.sp
                    )
                    Button(
                        onClick = {
                            try {
                                accessibilitySettingsLauncher.launch(
                                    Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                                )
                            } catch (_: ActivityNotFoundException) {
                                status = "Accessibility settings aren’t available on this device."
                            }
                        },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (accessibilityEnabled) Color(0xFF343B50) else Teal
                        ),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Text(
                            if (accessibilityEnabled) "Accessibility text entry is on" else "Set up text entry",
                            color = if (accessibilityEnabled) Color.White else Midnight
                        )
                    }
                }
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("QUICK START", color = Muted, fontSize = 11.sp, letterSpacing = 1.4.sp)
                Spacer(Modifier.weight(1f))
                Text("Try a command", color = Muted, fontSize = 11.sp)
            }
            Row(
                modifier = Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Suggestion("Open WhatsApp") { handleCommand("open WhatsApp") }
                Suggestion("Show apps") { handleCommand("show apps") }
                Suggestion("Open Contacts") { handleCommand("open contacts") }
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("YOUR APPS", color = Muted, fontSize = 11.sp, letterSpacing = 1.4.sp)
                Spacer(Modifier.weight(1f))
                Text(
                    if (expandedApps) "Show less" else "See all",
                    color = Teal,
                    fontSize = 12.sp,
                    modifier = Modifier.clickable { expandedApps = !expandedApps }
                )
            }
            val visibleApps = if (expandedApps) apps else apps.take(6)
            visibleApps.chunked(2).forEach { rowApps ->
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    rowApps.forEach { app ->
                        Card(
                            modifier = Modifier
                                .weight(1f)
                                .clickable {
                                    if (openApp(app.name)) reply("Opening ${app.name}.")
                                    else reply("I couldn’t open ${app.name}.")
                                },
                            colors = CardDefaults.cardColors(containerColor = Panel),
                            shape = RoundedCornerShape(16.dp)
                        ) {
                            Row(
                                modifier = Modifier.padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(34.dp)
                                        .background(Teal.copy(alpha = 0.14f), RoundedCornerShape(11.dp)),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(app.name.take(1).uppercase(), color = Teal, fontWeight = FontWeight.Bold)
                                }
                                Spacer(Modifier.width(9.dp))
                                Text(app.name, color = Color.White, fontSize = 13.sp, maxLines = 1)
                            }
                        }
                    }
                    if (rowApps.size == 1) Spacer(Modifier.weight(1f))
                }
            }

            Text(
                "Voice requests run after recognition. Review calls and messages before confirming them.",
                color = Muted,
                fontSize = 11.sp,
                modifier = Modifier.padding(bottom = 10.dp)
            )
        }
    }
}

@Composable
private fun QuickAction(
    label: String,
    icon: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Card(
        modifier = modifier.clickable(onClick = onClick),
        colors = CardDefaults.cardColors(containerColor = Panel),
        shape = RoundedCornerShape(16.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 13.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(5.dp)
        ) {
            Box(contentAlignment = Alignment.Center) {
                androidx.compose.runtime.CompositionLocalProvider(
                    androidx.compose.material3.LocalContentColor provides Teal
                ) { icon() }
            }
            Text(label, color = Color.White, fontSize = 12.sp)
        }
    }
}

@Composable
private fun Suggestion(label: String, onClick: () -> Unit) {
    Surface(
        modifier = Modifier.clickable(onClick = onClick),
        color = Panel,
        shape = RoundedCornerShape(18.dp)
    ) {
        Text(label, color = Teal, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 13.dp, vertical = 9.dp))
    }
}
