# Play Elphis on AEmulator

Elphis (Com2uS, 2014) is a 32-bit game for Android 2.1 and up. It runs well on a Google Nexus 4 firmware
(Android 4.4.4) inside AEmulator, with sound and touch.

## 1. Get the Nexus 4 firmware

Download the Nexus 4 ("occam") factory image for **Android 4.4.4 (KTU84P)** from Google's official page:

https://developers.google.com/android/images#occam

You accept Google's terms on that page. The file is a `.tgz`; keep it as it is.

## 2. Import it

Open AEmulator → **Add firmware** → pick the `.tgz`. Import takes a few minutes.

AEmulator sets up a Nexus 4 the way Elphis was tested, so nothing needs changing:

| Setting | Value |
|---|---|
| Guest screen | 540×960, 240 DPI |
| Phone GPU | off |
| App hardware acceleration (hwui) | on |
| Dalvik JIT | on |
| Hardware 32-bit execution | off |
| Guest RAM | 2048 MB (what the Nexus 4 had; less makes the game freeze and lose its place) |
| Modern TLS proxy, radio module | on |
| Button bar | Back, Home, Recents, Menu; compact |
| Keep screen on, notification light | on; single touch off |

## 3. Start it and install Elphis

Press **Start** and wait for the home screen (the first boot is slower).

Get Elphis from its official Google Play page, if it is still listed in your country:

https://play.google.com/store/apps/details?id=com.com2us.elpise.normal.freefull.google.global.android.common

If you have the game's APK yourself, install it from the **⋮** menu → **Install APK**.

## Save your progress

On the main screen, open **⋮** on the machine → **Backups** → **Back up now** (stop the virtual phone first). Each
backup is a `.aemu` file in `Internal storage/AEmulator/Backups`, with the whole phone: Elphis, its saves and the
memory card. Make as many as you like; **Restore** adds one back as a new machine. Copy them to a computer to keep
them safe, even across an uninstall, and bring one back with **Import backup file**.

While playing, **⋮ → Quick save** saves your progress without stopping: the phone pauses for a moment, then goes
on. To go back to a quick save, stop the phone, then **⋮** on the machine → **Backups** → **Restore** on that save.

## If something goes wrong

- Sounds stop after a while: update to a build with the sound-track fix (see the CHANGELOG) and restart the virtual
  phone once.
- Anything else: open **⋮** → log, export both **Host** and **Guest (logcat)**, and share them on the forum.
