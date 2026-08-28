#!/bin/zsh
# Alpha 2.5.1(全 11 MOD)+ fabric-api + Aureum を**本番形式の専用サーバー**
# (Fabric launcher、本番リマップ済み jar)で起動し、きれいに起動・停止することを確かめる。
#
# 使い方: tools/compat_boot_test.sh <run-label>
#
# 判定はこのスクリプトの exit code ではなく、呼び出し側が
# compat/results/<label>/latest.log を数えて行う(Done 行・mixin エラー 0・
# aureum trace 行・Stopping server 行)。
set -u
cd "$(dirname "$0")/.."
ROOT="$(pwd)"
C="$ROOT/compat"
LABEL="${1:?usage: compat_boot_test.sh <run-label>}"
EULA_SRC="/Volumes/ORICO/Minecraft Sorakazekarasu Server developer/mods-src/sorakaze-guns/run/eula.txt"
ALPHA="/Volumes/ORICO/Minecraft Sorakazekarasu Server developer/mods-src"
JAVA=/Library/Java/JavaVirtualMachines/temurin-25.jdk/Contents/Home/bin/java

mkdir -p "$C/mods" "$C/results/$LABEL"

# EULA: 所有者同意済みファイルの複写(2026-08-23 所有者決定 #3)。値まで確認する。
if ! grep -q '^eula=true' "$EULA_SRC"; then
  echo "REFUSING: $EULA_SRC does not contain eula=true — only the owner may consent." >&2
  exit 78
fi
cp "$EULA_SRC" "$C/eula.txt"

cat > "$C/server.properties" <<'EOF'
server-port=25696
level-seed=aureumcompat1
gamemode=survival
difficulty=normal
spawn-protection=16
view-distance=8
simulation-distance=8
max-tick-time=-1
pause-when-empty-seconds=-1
sync-chunk-writes=false
online-mode=false
motd=aureum-compat-boot
level-name=world
EOF

# mods: Alpha 11 本 + fabric-api + aureum(毎回コピーし直して取り違えを防ぐ)
rm -f "$C"/mods/alpha-*.jar "$C"/mods/aureum-*.jar
for m in backrooms boss deco guns planarcadia power rail sapporo sky survival vehicles; do
  cp "$ALPHA/sorakaze-$m/build/libs/alpha-$m-2.5.1.jar" "$C/mods/"
done
cp "$ROOT/build/libs/aureum-1.0.0.jar" "$C/mods/"
MODCOUNT=$(ls "$C"/mods/*.jar | wc -l | tr -d ' ')
echo "== mods in place: $MODCOUNT jars (expect 13 = 11 alpha + fabric-api + aureum)"

rm -rf "$C/logs"
cd "$C"
# 起動 → Done を待つ → /aureum を打つ → ヒープ計測 → stop。
# stdin をパイプで繋ぎ、こちらのペースでコマンドを流す。
mkfifo "$C/console.pipe" 2>/dev/null || true
( exec 3>"$C/console.pipe"; # キープオープン
  DEADLINE=$((SECONDS+600))
  while [ $SECONDS -lt $DEADLINE ]; do
    if grep -q 'Done (' "$C/logs/latest.log" 2>/dev/null; then break; fi
    sleep 2
  done
  sleep 3
  echo "aureum" >&3
  sleep 3
  PID=$(pgrep -f "fabric-server-mc26.2.*$C" | head -1)
  if [ -z "${PID:-}" ]; then PID=$(pgrep -f "fabric-server-mc26.2" | head -1); fi
  if [ -n "${PID:-}" ]; then
    for i in 1 2 3; do /Library/Java/JavaVirtualMachines/temurin-25.jdk/Contents/Home/bin/jcmd "$PID" GC.run >/dev/null 2>&1; sleep 2; done
    /Library/Java/JavaVirtualMachines/temurin-25.jdk/Contents/Home/bin/jcmd "$PID" GC.heap_info > "$C/results/$LABEL/heap_info.txt" 2>&1
  fi
  sleep 2
  echo "stop" >&3
  exec 3>&-
) &
FEEDER=$!
"$JAVA" -Xms2G -Xmx2G -XX:+UseG1GC -jar fabric-server-mc26.2-loader0.19.3-launcher1.1.2.jar nogui < "$C/console.pipe" > "$C/results/$LABEL/stdout.log" 2>&1
RC=$?
wait $FEEDER 2>/dev/null
cp "$C/logs/latest.log" "$C/results/$LABEL/latest.log" 2>/dev/null
rm -f "$C/console.pipe"
echo "== server process exited rc=$RC (informational only — judge by the log)"
echo "== results: $C/results/$LABEL/"
