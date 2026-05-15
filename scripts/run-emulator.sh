#!/usr/bin/env bash
set -euo pipefail

SDK="${ANDROID_HOME:-$HOME/Android/Sdk}"
AVD_HOME="${ANDROID_AVD_HOME:-$HOME/.android/avd}"
AVD_NAME="${HERMES_ANDROID_AVD:-hermes_agent_api36}"
APK="${HERMES_ANDROID_APK:-$(pwd)/app/build/outputs/apk/debug/app-debug.apk}"
GPU_MODE="${HERMES_ANDROID_GPU:-host}"
RAM_MB="${HERMES_ANDROID_RAM_MB:-4096}"
CPU_CORES="${HERMES_ANDROID_CPU_CORES:-6}"
HEAP_MB="${HERMES_ANDROID_HEAP_MB:-512}"
DATA_SIZE="${HERMES_ANDROID_DATA_SIZE:-4096M}"

ADB="$SDK/platform-tools/adb"
EMULATOR="$SDK/emulator/emulator"
AVDMANAGER="$SDK/cmdline-tools/latest/bin/avdmanager"
SYSTEM_IMAGE="${HERMES_ANDROID_SYSTEM_IMAGE:-system-images;android-36;google_apis;x86_64}"
SYSTEM_API="${HERMES_ANDROID_SYSTEM_API:-android-36}"

if [[ ! -x "$ADB" ]]; then
  echo "adb not found: $ADB" >&2
  exit 1
fi

if [[ ! -x "$EMULATOR" ]]; then
  echo "emulator not found: $EMULATOR" >&2
  exit 1
fi

mkdir -p "$AVD_HOME"

if ! ANDROID_AVD_HOME="$AVD_HOME" "$EMULATOR" -list-avds | grep -qx "$AVD_NAME"; then
  if [[ ! -x "$AVDMANAGER" ]]; then
    echo "AVD '$AVD_NAME' does not exist and avdmanager was not found: $AVDMANAGER" >&2
    exit 1
  fi
  printf "no\n" | "$AVDMANAGER" create avd \
    -n "$AVD_NAME" \
    -k "$SYSTEM_IMAGE" \
    --device pixel_6 \
    --path "$AVD_HOME/$AVD_NAME.avd" \
    --force >/dev/null
  cat > "$AVD_HOME/$AVD_NAME.ini" <<EOF
avd.ini.encoding=UTF-8
path=$AVD_HOME/$AVD_NAME.avd
path.rel=avd/$AVD_NAME.avd
target=$SYSTEM_API
EOF
fi

CONFIG="$AVD_HOME/$AVD_NAME.avd/config.ini"
if [[ -f "$CONFIG" ]]; then
  set_config() {
    local key="$1"
    local value="$2"
    if grep -q "^$key=" "$CONFIG"; then
      sed -i "s|^$key=.*|$key=$value|" "$CONFIG"
    else
      printf '%s=%s\n' "$key" "$value" >> "$CONFIG"
    fi
  }

  set_config "hw.ramSize" "$RAM_MB"
  set_config "hw.cpu.ncore" "$CPU_CORES"
  set_config "vm.heapSize" "$HEAP_MB"
  set_config "disk.dataPartition.size" "$DATA_SIZE"
  set_config "hw.gpu.enabled" "yes"
  set_config "hw.gpu.mode" "$GPU_MODE"
  set_config "fastboot.forceColdBoot" "yes"
fi

running_sdk="$("$ADB" shell getprop ro.build.version.sdk 2>/dev/null | tr -d '\r' || true)"
if [[ -n "$running_sdk" && "$running_sdk" != "36" ]]; then
  echo "Running emulator API is $running_sdk, expected 36. Stopping it..."
  "$ADB" emu kill >/dev/null 2>&1 || true
  sleep 5
fi

if ! "$ADB" devices | grep -qE "emulator-[0-9]+[[:space:]]+device"; then
  nohup setsid env ANDROID_AVD_HOME="$AVD_HOME" "$EMULATOR" \
    -avd "$AVD_NAME" \
    -no-audio \
    -gpu "$GPU_MODE" \
    ${HERMES_ANDROID_HEADLESS:+-no-window -no-boot-anim} \
    >/tmp/hermes-agent-emulator.log 2>&1 &
fi

"$ADB" wait-for-device

running_sdk="$("$ADB" shell getprop ro.build.version.sdk 2>/dev/null | tr -d '\r' || true)"
if [[ "$running_sdk" != "36" ]]; then
  echo "Connected emulator API is ${running_sdk:-unknown}, expected 36." >&2
  exit 1
fi

until [[ "$("$ADB" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" == "1" ]]; do
  sleep 2
done

if [[ ! -f "$APK" ]]; then
  echo "APK not found: $APK" >&2
  echo "Build it with: JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64 ./gradlew assembleDebug" >&2
  exit 1
fi

"$ADB" install -r "$APK"
"$ADB" shell am start -n dev.hermes.mobile/.MainActivity

echo "Launched Hermes Agent Mobile on emulator."
echo "Emulator log: /tmp/hermes-agent-emulator.log"
