# Max Assistant

An Android assistant and installable, responsive browser companion.

## Run

Open this folder in Android Studio and run the `app` configuration on an Android device or emulator (Android 8.0/API 26 or later). The project uses Android Gradle Plugin 8.7.3, Kotlin 2.0.21, Gradle 8.9, and Java 17. A Gradle wrapper is included, so use `./gradlew :app:assembleDebug` to build a debug APK from a terminal with the Android SDK installed. The APK is written to `app/build/outputs/apk/debug/app-debug.apk`.

## Browser app

The progressive web app (PWA) lives in `site/app/`. It adapts to phone, tablet, and desktop screens; can be installed from a supported browser; and caches its app shell for offline reopening after its first successful load. It accepts voice requests while open, speaks replies, shares text, opens WhatsApp's web handoff, and opens the phone dialer. It has no typed-command box or conversation-history panel.

The browser app attempts to hand WhatsApp requests to the Android WhatsApp app, with its web link as a fallback. Mobile browsers cannot enumerate or launch arbitrary installed apps, inspect contacts, enter text into another app, or listen for “Hey Max” in the background. Install the Android APK to voice-launch apps shown in the device launcher. Speech recognition support and language coverage depend on the browser and its speech service.

On iPhone, open the deployed page in Safari, tap **Share**, then **Add to Home Screen**. On Android/desktop, use the browser's **Install app** control when offered. All versions still require a website host; GitHub Pages setup is below.

## Publish Android downloads and the website

The website source is in `site/`; `docs/index.html` serves the GitHub Pages landing page, with the PWA available under `/site/app/`. In GitHub, create a **public** repository, push this source to its `main` branch, then open **Settings → Pages**, choose **Deploy from a branch**, select `main` and `/docs`, and save. For `OWNER/max-assistant`, the site will be at `https://OWNER.github.io/max-assistant/`.

The website's Android download button uses the latest GitHub Release asset named `MaxAssistant.apk`. Create a release tagged `v1.0.0` and attach that APK. For future updates, build and sign a release APK using the same securely backed-up Android signing keystore; never commit the keystore or passwords. Android users can download and install the APK from the website; iPhone users can install the browser app.

GitHub Actions release and Pages workflows are also included under `.github/workflows/` for automated future builds. GitHub requires separate `workflow` authorization to upload or change those workflow files. Until that is granted and the workflows are uploaded, publish Pages from the `/docs` folder and attach APKs to releases manually.

## What it can do

- Start a device-provided speech recognition prompt using the selected language.
- Choose from 30 speech-recognition languages; availability depends on the device's speech service.
- Optionally listen for “Hey Max” through a microphone foreground service with a persistent notification and a stop control.
- Speak assistant responses using Android text-to-speech.
- Browse and launch installed apps visible through Android's launcher-intent package visibility.
- Open Contacts without requesting broad contacts access, or open the dialer for a user-reviewed call.
- Prepare text for WhatsApp or another share-compatible app. The user chooses a chat and reviews/sends the message.
- Select a contact from Android's system picker and open their WhatsApp chat with a message draft ready for review.

Examples: “Open WhatsApp”, “launch YouTube”, “start my Instagram app”, “go to Maps”, “Open chat with Haris”, “Say good morning to Haris”, “Open WhatsApp and type I’ll be there soon”, “Send running late via WhatsApp”, “Call 5551234567”, and “Show apps”. Voice app launching works in the Android app for apps available in the Android launcher. A browser cannot inspect the installed-app list.

For hands-free WhatsApp drafts by a spoken contact name, tap **Enable voice contact lookup** and grant Android's optional Contacts permission once. Then say “Hey Max, send good morning to Haris.” Max matches an exact contact name and opens the WhatsApp draft; it does not send the message. If the name is missing or has multiple phone numbers, Max asks you to choose the contact.

To turn on background wake listening, tap **Enable “Hey Max” wake phrase** and approve Android’s microphone and notification prompts once. Max answers “I’m listening”; say a command immediately after, or in one phrase such as “Hey Max, open Instagram.” App launching does not require the separate Accessibility setting; that setting is only for typing in another app. Android requires these explicit permissions and a persistent microphone notification; Max cannot bypass them. The service retries temporary speech-recognition failures and asks Android to restart it if the process is reclaimed. It does not automatically start after a phone reboot, so enable it again after restarting the device. Background app launches can still be restricted by some Android versions, in which case use the Max notification to continue.

## Optional Android focused text entry

The Android app can launch installed launcher apps with phrases such as “Hey Max, open Instagram”, “Hey Max, launch YouTube”, “Hey Max, start Maps”, or “Hey Max, go to WhatsApp”. The display name must be recognized and the app must be installed with a launcher entry. For dictated typing while another app is open, first turn on the optional **Max focused text entry** service from the app's **Set up text entry** button and Android Accessibility settings. For example, say **“Hey Max, open WhatsApp”**, select a conversation yourself, focus its message field, then say **“Hey Max, type I’ll be there soon”**. You can also say “Hey Max” and give the command in the next few seconds. Max replaces only the focused field's text and does not read the field, select a contact, tap Send, or send messages. Review and send in the destination app yourself. Do not dictate into password fields.

Android displays a system warning when an Accessibility service is enabled because the permission can access the active app's UI. Max uses it only to set text in the focused, non-password field. Keep the wake-phrase notification visible while listening and stop listening from the notification or app. Availability and activity-launch behavior vary by Android version and device; if an app does not open in the background, tap the notification to continue. Sideloaded testing is supported; distribution through Google Play requires a separate policy review for Accessibility-service use.

## Platform limits

Speech recognition availability and language coverage depend on the speech service installed on the device. Wake phrase listening is optional, uses a persistent foreground-service notification, and can be stopped from the notification or app. It prefers on-device recognition where available; otherwise Android may use the device's configured recognition service, which can process audio remotely. Wake phrase detection is best-effort and may vary by device and language. Android does not allow an ordinary app to silently type into another app's private chat UI or send messages without user confirmation. WhatsApp sharing opens its handoff flow; the user must select a conversation and send.

The browser app cannot get Android Accessibility access or inspect device contacts. A browser request such as “say good morning to Haris” prepares the text and asks you to pick the chat in WhatsApp; use the Android app for voice contact lookup. The Android app's focused-text feature is limited to the currently focused field and never sends messages on your behalf.
