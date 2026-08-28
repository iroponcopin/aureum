#!/bin/zsh
# Aureum 再現可能ベンチ。専用サーバー(dev マッピング)上で MSPT / ヒープ /
# アロケーション(JFR)を測る。
#
# 使い方:
#   tools/bench.sh prep                 # ワールド雛形を生成(初回に 1 度)
#   tools/bench.sh run <label>          # 1 回計測(結果は bench-results/<label>-<ts>/)
#   tools/bench.sh run <label> <cfgjson># aureum config を差し替えて計測
#
# 判定規約: exit code では判定しない。呼び出し側は
#   bench-results/<...>/latest.log の "AUREUM-BENCH RESULT ok" 行を数え、
#   report.json の mtime が実行後であることを確認すること
# (mods-src の実測: server.halt(false) のため rc=0 は何も証明しない)。
set -u
cd "$(dirname "$0")/.."
ROOT="$(pwd)"
RUN="$ROOT/build/run-bench"
TEMPLATE="$ROOT/build/bench-world-template"
EULA_SRC="/Volumes/ORICO/Minecraft Sorakazekarasu Server developer/mods-src/sorakaze-guns/run/eula.txt"
export JAVA_HOME=/Library/Java/JavaVirtualMachines/temurin-25.jdk/Contents/Home

prepare_dir() {
  mkdir -p "$RUN"
  # EULA: 所有者が同意済みのファイルを複写する(2026-08-23 の所有者決定 #3 で
  # 新しい証明ディレクトリへの複写が承認されている)。値まで確認する — 存在だけの
  # 確認は vanilla が書く eula=false で永遠に満たされてしまう。
  if ! grep -q '^eula=true' "$EULA_SRC"; then
    echo "REFUSING: $EULA_SRC does not contain eula=true — only the owner may consent." >&2
    exit 78
  fi
  cp "$EULA_SRC" "$RUN/eula.txt"
  cat > "$RUN/server.properties" <<'EOF'
level-seed=aureumbench1
gamemode=creative
difficulty=normal
spawn-protection=0
view-distance=10
simulation-distance=10
max-tick-time=-1
pause-when-empty-seconds=-1
sync-chunk-writes=false
online-mode=false
white-list=false
motd=aureum-bench
level-name=world
server-port=25695
EOF
}

run_server() {
  local warmup="$1" phase="$2" idle="${3:-0}"
  ( cd "$ROOT" && ./gradlew runBench --no-daemon -q -PbenchWarmup="$warmup" -PbenchPhase="$phase" -PbenchIdle="$idle" )
}

collect() {
  local label="$1" cfg="$2"
  TS="$(date +%Y%m%d-%H%M%S)"
  OUT="$ROOT/bench-results/$label-$TS"
  mkdir -p "$OUT"
  cp -R "$RUN/aureum-bench/." "$OUT/" 2>/dev/null
  cp "$RUN/logs/latest.log" "$OUT/latest.log" 2>/dev/null
  [ -n "$cfg" ] && cp "$cfg" "$OUT/aureum-config-used.json"
  RESULT_LINES=$(grep -c 'AUREUM-BENCH RESULT ok' "$OUT/latest.log" 2>/dev/null || echo 0)
  echo "== bench run '$label' finished: RESULT-ok lines = $RESULT_LINES, output: $OUT"
}

case "${1:-}" in
  prep)
    prepare_dir
    rm -rf "$RUN/world" "$RUN/aureum-bench"
    echo "== prep: generating the bench world (long warmup, no measurement)"
    run_server 6000 10
    # 26.2 のセーブ形式は world/region ではなく world/dimensions/ 配下。
    if [ ! -f "$RUN/world/level.dat" ] || [ -z "$(ls "$RUN/world/dimensions" 2>/dev/null)" ]; then
      echo "PREP FAILED: no world data was written" >&2
      exit 1
    fi
    rm -rf "$TEMPLATE"
    cp -R "$RUN/world" "$TEMPLATE"
    echo "== prep done: template at $TEMPLATE"
    ;;
  run)
    LABEL="${2:?usage: bench.sh run <label> [aureum-config.json]}"
    CFG="${3:-}"
    if [ ! -d "$TEMPLATE" ]; then
      echo "no world template — run 'tools/bench.sh prep' first" >&2
      exit 1
    fi
    prepare_dir
    rm -rf "$RUN/world" "$RUN/aureum-bench" "$RUN/logs"
    cp -R "$TEMPLATE" "$RUN/world"
    mkdir -p "$RUN/config"
    if [ -n "$CFG" ]; then
      cp "$CFG" "$RUN/config/aureum.json"
    else
      rm -f "$RUN/config/aureum.json"
    fi
    echo "== bench run '$LABEL' starting: $(date)"
    run_server "${BENCH_WARMUP:-2400}" "${BENCH_PHASE:-4800}" "${BENCH_IDLE:-0}"
    collect "$LABEL" "$CFG"
    ;;
  genrun)
    # ワールドを毎回ゼロから生成し、TTL+掃除間隔ぶん待ってからヒープを測る。
    # 構造物テンプレートキャッシュ TTL の効果はこのモードでしか見えない
    # (雛形ワールドを読むだけの run モードではテンプレートが 1 つも読まれない)。
    LABEL="${2:?usage: bench.sh genrun <label> [aureum-config.json]}"
    CFG="${3:-}"
    prepare_dir
    rm -rf "$RUN/world" "$RUN/aureum-bench" "$RUN/logs"
    mkdir -p "$RUN/config"
    if [ -n "$CFG" ]; then
      cp "$CFG" "$RUN/config/aureum.json"
    else
      rm -f "$RUN/config/aureum.json"
    fi
    echo "== bench genrun '$LABEL' starting (fresh world): $(date)"
    run_server "${BENCH_WARMUP:-6000}" "${BENCH_PHASE:-40}" "${BENCH_IDLE:-9000}"
    collect "$LABEL" "$CFG"
    ;;
  *)
    echo "usage: bench.sh prep | bench.sh run <label> [cfg.json] | bench.sh genrun <label> [cfg.json]" >&2
    exit 64
    ;;
esac
