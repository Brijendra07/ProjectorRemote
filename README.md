# Projector Remote

A phone remote for the PIXPAQ LED projector (Android TV 14). It controls the projector over Wi-Fi using Android's built-in **Wireless debugging**, so it needs no Bluetooth and no root.

## Features
- D-pad + OK, Back, Home, Menu, Settings, Power
- Volume up/down, mute, play/pause, rewind, fast-forward
- Touchpad: swipe to move, tap = OK, double-tap = play/pause, hold = Back
- Type: send text into search boxes from your phone keyboard
- Apps: lists the projector's apps and opens them with one tap
- Remembers the projector and reconnects automatically. If the port changes after a reboot, it finds the projector on Wi-Fi.

## Build (no install on your computer)
1. Create a new GitHub repository and upload all files from this folder. Keep the `.github` folder; it holds the build script.
2. GitHub builds the app automatically. Open the **Actions** tab to watch it (about 5 minutes).
3. When it finishes, open **Releases** and download `ProjectorRemote-debug.apk` on your phone.

## First-time setup
1. On the projector, open **Developer options → Wireless debugging** and make sure it's **Enabled**.
2. Install the APK on your phone (allow "install unknown apps" if asked). The phone must be on the same Wi-Fi as the projector.
3. In the app, enter the **IP address** and **Port** shown under "IP address & Port" (e.g. `192.168.1.9` and `40923`).
4. On the projector, tap **Pair device with pairing code**. In the app, enter the **pairing port** from that pop-up and the **6-digit code**, then tap **Pair & connect**.

After that the app connects on its own every time you open it.

## Reopening Developer options on the projector
Activity Launcher → Settings (`com.android.tv.settings`) →
class `com.android.tv.settings.system.development.DevelopmentActivity` → Launch.

## Notes
- Wireless debugging can switch off when the projector restarts. If the app can't connect, turn it back on (the projector remembers the pairing).
- Anyone on your Wi-Fi who has paired with the projector can control it. Turn Wireless debugging off if you share the network with people you don't trust.
