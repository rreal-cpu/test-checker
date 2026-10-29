# Getting an installable APK

## Option A: Android Studio (on a computer)
1. Install Android Studio (Koala or newer). Open this folder; accept Gradle 8.7+ if asked. Wait for sync.
2. Build > Build Bundle(s) / APK(s) > Build APK(s).
3. Click "locate" in the popup: app/build/outputs/apk/debug/app-debug.apk
4. Copy it to the phone (USB, Drive, Telegram) and tap it.

## Option B: GitHub (no Android Studio, works from a browser)
1. Create a free GitHub account and a new empty repository.
2. Upload everything in this folder (including the hidden .github folder) to it.
3. Open the Actions tab > "Build APK" > wait ~5-10 minutes for the green check.
   (If it didn't start, click "Run workflow".)
4. Open the finished run, download the "TestChecker-debug-apk" artifact (a zip), unzip it, and you have app-debug.apk.

## Installing on the phone
Open the APK. Android will ask to allow installs from that source (Files, Chrome, etc.): allow it for this install.
This is a debug-signed build for personal use, not for the Play Store.

If the build fails, copy the red error lines and send them to me.
