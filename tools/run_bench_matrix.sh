#!/bin/zsh
# 計測マトリクス一式を直列で走らせる(並列にすると MSPT が汚れる)。
# resident: all-off ×2 / all-on ×2(MSPT + 常駐ヒープ + histogram)
# genrun:   ttl-off ×2 / ttl-on ×2(ワールド生成後のヒープ — テンプレート TTL の実測)
set -u
cd "$(dirname "$0")/.."
echo "== matrix starts $(date)"
./tools/bench.sh run resident-off-1 tools/bench-configs/all-off.json
./tools/bench.sh run resident-on-1  tools/bench-configs/all-on.json
./tools/bench.sh run resident-off-2 tools/bench-configs/all-off.json
./tools/bench.sh run resident-on-2  tools/bench-configs/all-on.json
BENCH_WARMUP=6000 BENCH_PHASE=40 BENCH_IDLE=9000 ./tools/bench.sh genrun gen-ttloff-1 tools/bench-configs/gen-ttl-off.json
BENCH_WARMUP=6000 BENCH_PHASE=40 BENCH_IDLE=9000 ./tools/bench.sh genrun gen-ttlon-1  tools/bench-configs/gen-ttl-on.json
BENCH_WARMUP=6000 BENCH_PHASE=40 BENCH_IDLE=9000 ./tools/bench.sh genrun gen-ttloff-2 tools/bench-configs/gen-ttl-off.json
BENCH_WARMUP=6000 BENCH_PHASE=40 BENCH_IDLE=9000 ./tools/bench.sh genrun gen-ttlon-2  tools/bench-configs/gen-ttl-on.json
echo "== matrix done $(date)"
