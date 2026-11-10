# AppInside

A mini-OS environment for your phone. Mini-apps: **P&L** (daily net profit/loss, 30% tax, date filters), **Music** (your own songs, keeps playing across mini-apps), **Data Lab** (store, analyse, display) and **Voice Notes** (record → analyse → review → save).

## Get the Android APK (no computer needed)
1. Push this folder to a GitHub repository (branch `main`).
2. GitHub builds the APK automatically (tab **Actions**, about 5–10 minutes).
3. Open the repository's **Releases → latest** on your phone and download `AppInside.apk`.
4. Open it and allow "Install unknown apps" for your browser when Android asks.
5. On first use of Voice Notes, allow microphone access.

## Build on a computer instead
Needs Node 20+, Android Studio (Ladybug 2024.2.1 or newer, Java 21).
```
npm install
npx cap add android
npx cap sync android
npx cap open android      # then Build ▸ Build APK(s)
```
Add to `android/app/src/main/AndroidManifest.xml` before `</manifest>`:
```xml
<uses-permission android:name="android.permission.RECORD_AUDIO" />
<queries><intent><action android:name="android.speech.RecognitionService" /></intent></queries>
```

## iPhone
Needs a Mac with Xcode 16+: `npx cap add ios`, then in Xcode add `NSMicrophoneUsageDescription` and `NSSpeechRecognitionUsageDescription` to Info.plist, and run on your phone (free Apple ID works for 7-day installs; App Store/TestFlight needs the $99/year Apple Developer Program).

## Editing the app
All app code is in `www/index.html`. Every change pushed to `main` produces a new APK.

## Music: playing in the background (next step)
Inside AppInside, music keeps playing while you switch mini-apps. Android pauses a WebView once you leave the app or lock the screen, so playback outside the app needs a native audio plugin with a media-playback foreground service (e.g. `@capgo/native-audio` + `@capgo/capacitor-media-session`).

## Size budget
AppInside must stay under 100 MB. The app code is a single ~70 KB file; the music library is capped at 70 MB (meter shown in Music), and the build fails if the APK exceeds 100 MB.

## Master & sync (Realme Pad X as master)
1. Install the same APK on the tablet and the Android phones.
2. Tablet: Settings → **Master**. It shows its Wi-Fi address (e.g. `192.168.1.7:8787`). Tap **Create pairing code**.
3. Phone: Settings → **Connect**, enter the address and the 6-digit code.
4. From then on P&L, Voice Notes and Data Lab sync whenever both are on the same Wi-Fi and AppInside is open on the tablet. Music is not synced (it stays on each device).
5. iPhone (no laptop needed): open `http://<tablet address>` in Safari → Share → Add to Home Screen, then pair in Settings. Works while on the home Wi-Fi.

## Updates spreading from the master
- The master checks the online address (GitHub release `latest`: `version.json` + `bundle.zip`) when you tap **Check online now**.
- Phones download a newer version from the master over Wi-Fi and switch to it the next time they open.
- Changes to native parts (new permissions/plugins) still need a new APK installed by tapping Install.
