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

## 結果(2026-08-28 実測)

生データは bench-results/ の各ランに report.json / class-histogram.txt /
*-ticks-ns.csv / steady-spawn.jfr として保存。以下はその転記。

### resident(dedupeBlockStateCaches + slimThreadingDetector の分離測定)

| ラン | 使用ヒープ中央値 | STEADY 中央値 | SPAWN 中央値 |
|---|---|---|---|
| off-1 | 185.7 MB | 1.784 ms | 1.602 ms |
| off-2 | 185.7 MB | 1.730 ms | 0.991 ms |
| on-1 | 179.4 MB | 1.766 ms | 1.182 ms |
| on-2 | 179.4 MB | 1.588 ms | 1.504 ms |

- **ヒープ: −6.3 MB(−3.4%)、両ペアで一致**(off 185.66/185.73 → on 179.39/179.39)。
- クラス別内訳(histogram、off-1 → on-1):
  - 検出器一式(ThreadingDetector+Semaphore+ReentrantLock+両 NonfairSync):
    64,992 組 8.32 MB → 3.12 MB(**−5.20 MB**。TD 自体は 32→48 B/個に増え、
    付随ロック 4 種 96 B/個が消える)
  - BlockStateBase$Cache: 32,167 個 0.77 MB → **6,066 個 0.15 MB**(−0.63 MB)
  - boolean[](faceSturdy): 1.32 → 0.27 MB(−1.04 MB)
  - VoxelShape[](遮蔽面): 1.20 → 0.68 MB(−0.52 MB)
  - Aureum 自身の帳簿(CacheKey プール): **+0.19 MB**(正直に計上)
- **MSPT: 変化なし(ラン間ノイズの範囲内)。** STEADY は off 1.730–1.784 / on 1.588–1.766
  で範囲が重なる。SPAWN は自然湧きの個体数がラン毎に違い off だけでも 0.99–1.60 ms
  振れる — どの差も主張しない。v1.0.0 の最適化はメモリが主対象であり、
  これは期待どおりの結果である(速くなったと言わないことを含めて)。
- JFR(各 9.5 分窓、約 2.4 GB 標本): **Aureum のコードはアロケーション上位に
  1 件も現れない**。上位は off/on 共通のバニラ経路(Level.getBlockRandomPos の
  BlockPos ~700 MB、エンティティ移動の Vec3、光エンジンの fastutil イテレータ等)。

### genrun(structureTemplateCacheTtlSeconds の分離測定 — TTL 以外は両側 on)

| ラン | 使用ヒープ中央値 | templateCache | StructureBlockInfo | 付随 BlockPos |
|---|---|---|---|---|
| ttl-off-1 | 250.0 MB | (バニラ挙動) | 302,650 / 7.26 MB | 315,723 / 7.58 MB |
| ttl-off-2 | 250.4 MB | (バニラ挙動) | 302,650 / 7.26 MB | 315,403 / 7.57 MB |
| ttl-on-1 | 232.7 MB | **0 件** | **20,114 / 0.48 MB** | 33,494 / 0.80 MB |
| ttl-on-2 | 233.4 MB | **0 件** | **20,114 / 0.48 MB** | 33,298 / 0.80 MB |

- **ヒープ: −17.2 MB(−6.9%)、両ペアで 0.6 MB 以内に一致**
  (250.03/250.35 → 232.74/233.36)。
- 残る 20,114 個の StructureBlockInfo は読み込み中チャンクの StructureStart が
  保持しているぶんで、キャッシュの持ち物ではない(追い出しの対象外が正しい)。
- この 4 ランの steady/spawn は n=40 の形骸で、MSPT の主張には使わない。

### 測定が捕まえた実バグ(記録)

TTL の初版はワールド生成テンプレートを全部ピン留めしてしまい、**追い出しが
1 件も起きていなかった**(TTL on/off で StructureBlockInfo 302,650 個が同数)。
GameTest は get() 経路しか見ておらず緑のままだった。ベンチの histogram 比較が
これを捕まえ、修正(新規作成枝だけピン留め)+回帰テスト
{@code worldgenPathTemplatesAreEvictable} を追加した。無効だった 2 ランは
bench-results/invalid/ に隔離してある(上の ttl-on 2 ランは修正後の再測定)。

### 規模感の注意(正直な但し書き)

- 数値は「このワールド・約 2,209 読み込みチャンク・4 プレイヤー」での実測。
  検出器・状態キャッシュの節約は読み込みチャンク数/ブロック状態総数に、
  テンプレートの節約は生成済み構造物の量にほぼ比例する。
- dev マッピング実行での測定。A/B の相対比較として有効。絶対値は本番と少し異なる。
- クライアント側 2 機能(particleLimit / blockEntityRenderDistanceCap)は
  **未計測**(この環境にクライアントが無い)。既定 off で出荷し、効果を約束しない。
