# Aureum v1.0.0 — ベンチマーク(実測)

## 再現手順

すべてこのリポジトリ内で完結する。判定は exit code ではなく
`AUREUM-BENCH RESULT ok` ログ行とレポート JSON の存在+mtime で行う。

```
tools/bench.sh prep                       # ベンチ用ワールド雛形の生成(初回のみ)
tools/run_bench_matrix.sh                 # 下記マトリクス一式(直列 ~100 分)
python3 tools/summarize_bench.py          # bench-results/ の要約表
python3 tools/jfr_top_allocs.py <jfr>     # アロケーション上位サイト
```

### 計測環境

- Apple Silicon (arm64) / macOS / Temurin JDK 25.0.3
- 専用サーバー(loom dev 実行、mojang 公式名)。JVM 固定: `-Xms2G -Xmx2G -XX:+UseG1GC`
- ワールド: 種 `aureumbench1`、雛形を毎回複製(全ランが同一の初期状態から開始)
- 疑似プレイヤー 4 人(本物の placeNewPlayer 経路、KeepAlive/チャンク束応答つき)を
  (±384, ±384) の 4 地点へ配置 — 読み込み約 2,200 チャンク
- 注意: **dev 実行なので絶対値は本番と少し異なる。**A/B(off/on)は同一環境の
  相対比較として有効。本番形式での確認は compat 起動試験(VERIFICATION.md)側。

### resident ラン(常駐ヒープ + MSPT)

warmup 2,400 tick → HEAP(GC→待ち→標本 ×7 の中央値 + `jcmd GC.class_histogram`)
→ STEADY(永続 mob 200 体、自然湧き無し、4,800 tick 全数記録)
→ SPAWN(mob 全消去後、自然湧き有効、4,800 tick 全数記録)。
config: `tools/bench-configs/all-off.json` / `all-on.json` を各 2 回。

### genrun ラン(ワールド生成後ヒープ — テンプレート TTL 用)

毎回まっさらなワールドを生成(warmup 6,000 tick)→ 9,000 tick 待機
(TTL 60 秒 + 掃除間隔 20 秒を確実に跨ぐ)→ HEAP。
config: `gen-ttl-off.json`(TTL 0)/ `gen-ttl-on.json`(TTL 60)を各 2 回。
TTL 以外のキーは両側 on(TTL の寄与だけを分離)。

## 結果

(tools/summarize_bench.py の出力の転記 — 生データは bench-results/ の各ランに
report.json / class-histogram.txt / *-ticks-ns.csv / steady-spawn.jfr として保存)

<!-- RESULTS GO HERE -->
