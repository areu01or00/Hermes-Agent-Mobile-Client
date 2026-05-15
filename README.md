# Hermes Agent Mobile Client (for Android, iOS - WIP)


![Hermes Agent Mobile demo](demo/hermes-agent-mobile-demo.gif)

[Download signed Android APK](https://github.com/areu01or00/Hermes-Agent-Mobile-Client/raw/main/apk/hermes-agent-mobile-client-release.apk)

This folder is an open-source snapshot of the current project state: a thin
Android WebView client that loads a running Hermes dashboard.

It is an honest Android-only port attempt, not an upstream Hermes PR.
iOS is not implemented in this repo.

## What this is

- Android app that discovers a reachable Hermes dashboard endpoint on network
  and opens it in WebView.
- Uses Hermes dashboard as the source of truth (sessions/chat/jobs/skills all
  come from Hermes server).
- Owns Android soft-keyboard adaptation in the APK so a stock Hermes dashboard
  can be used without patching each user's PC/VPS checkout.
- Includes a signed APK for quick sideload testing.

## What this is not

- Not a replacement for Hermes server.
- Not a full native Android rewrite of Hermes UI logic.
- Not production-hardened distribution.

## Repo layout

- `android/` - Android Studio / Gradle project
- `apk/hermes-agent-mobile-client-release.apk` - signed APK artifact
- `apk/hermes-agent-mobile-client-debug.apk` - debug APK artifact
- `demo/hermes-agent-mobile-demo.gif` - README-compatible inline demo
- `demo/hermes-agent-mobile-demo.mp4` - trimmed app demo recording
- `scripts/run-emulator.sh` - helper script for emulator startup
- `scripts/setup-vps-dashboard.sh` - one-shot VPS dashboard setup

## Download APK

Direct APK download:

`https://github.com/areu01or00/Hermes-Agent-Mobile-Client/raw/main/apk/hermes-agent-mobile-client-release.apk`

Android may ask you to allow "Install unknown apps" for the browser or file
manager used to open the APK.

## Requirements

1. A working Hermes setup on PC or VPS.
2. Hermes dashboard reachable from your Android device.
3. Hermes dashboard started with embedded chat:
   `hermes dashboard --host 0.0.0.0 --port 9119 --no-open --insecure --tui`
4. Open firewall/security-group port for dashboard (example: `9119`).
5. Android device or emulator with internet access to that host.
6. For best auto-discovery, phone and Hermes host should be on the same LAN.

## One-shot VPS setup

After cloning this repo on your VPS:

```bash
cd Hermes-Agent-Mobile-Client/scripts
chmod +x setup-vps-dashboard.sh
./setup-vps-dashboard.sh
```

It writes/starts a `systemd` service, checks health, opens `ufw` port `9119`
if available, and prints the mobile URL.

## Server-side check

Open in browser (replace host as needed):

`http://<your-host>:9119`

For chat tab support, dashboard must expose embedded chat flag (served HTML
includes `window.__HERMES_DASHBOARD_EMBEDDED_CHAT__=true` when `--tui` is on).

## Use the included APK

Install:

```bash
adb install -r apk/hermes-agent-mobile-client-release.apk
```

Launch app:

- Same Wi-Fi auto-discovery probes local Hermes dashboard candidates.
- VPS / Cloud accepts a manual dashboard base URL and opens `/chat` directly.
- Use Saved Endpoint reopens the last manually connected dashboard base URL.
- If dashboard navigation fails, the app shows the WebView network/HTTP error
  instead of leaving a blank screen.
- The app wraps WebView with an Android input bridge so soft-keyboard typing,
  Backspace, Enter, Delete, and arrow-key style operations are routed into the
  Hermes xterm chat page.

For VPS-only setups outside local LAN, you should still make the VPS dashboard
reachable from the phone network (`http://<vps-ip>:9119`) and connect once from
the same route; app will persist the last successful base URL.

## Troubleshooting

If login times out after entering a correct URL, verify the server first. A
timeout means the Android WebView is trying to reach the dashboard but the host
or port is not answering from the phone network.

From a PC:

```bash
curl -fsS http://<your-host>:9119/api/status
curl -fsS http://<your-host>:9119/chat
```

On a VPS:

```bash
sudo ss -ltnp | grep 9119
sudo systemctl status hermes-dashboard.service
sudo journalctl -u hermes-dashboard.service -n 100 --no-pager
```

For AWS Lightsail, also confirm the instance firewall has inbound TCP `9119`
open and the instance is reachable on SSH. If both SSH and `9119` time out while
Lightsail says ports are open, rebooting the instance can restore networking.

## Build APK

```bash
cd android
JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64 ./gradlew assembleDebug
```

Output:

`android/app/build/outputs/apk/debug/app-debug.apk`

The published APK in `apk/hermes-agent-mobile-client-release.apk` is signed
with a private release keystore. Keep that keystore private; future update APKs
must use the same key or Android will reject them as updates.

## Notes for contributors

- This repo currently supports Android only.
- If you want iOS support, port the same architecture (thin client + Hermes
  dashboard backend) on iOS.
- Do not require users to patch their Hermes Agent install for mobile keyboard
  behavior. Mobile input fixes belong in the Android client unless they are
  submitted as proper upstream Hermes dashboard changes.
- Terminal scroll on Android WebView is still an active compatibility area. The
  current client attempts wheel and Shift+Arrow event bridging, but contributors
  should verify behavior on real devices before treating it as complete.
