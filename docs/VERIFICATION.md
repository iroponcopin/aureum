# Aureum v1.0.0 — 検証記録

規約: **すべての検証は独立に 2 回**走らせ、両方の結果を記録する。
すべての正当性テストに**負の対照**(わざと壊して赤くなることの確認)を付ける。
判定は必ずテスト自身の出力(レポート XML・ログ行)で行い、exit code では行わない。

## 1. 正当性テスト

### JUnit(素の JVM、Minecraft 抜き)— 15 テスト

| クラス | 内容 |
|---|---|
| RaceDetectorAlgorithmTest (4) | slimThreadingDetector の状態機械仕様: 単一スレッド静音・2 スレッド競合で両者同一例外+敗者解放・最後の敗者名で報告・未 lock unlock 無害 |
| ClientTuningLogicTest (2) | particleLimit / blockEntityRenderDistanceCap の境界 |
| AureumConfigTest (4) | validate の矯正(負値→0、TTL 60 秒床)・利用者値の保全・既定値 |
| StaleDefaultRepairTest (5) | 化石既定値修復機構(合成表): 置換・保全・版スキップ・入れ子と許容差・壊れた版値 |

- **RUN 1** (2026-08-28 21:35): `tests=15 failures+errors=0 skipped=0` — 全 PASSED(個別名の一覧はセッションログ)
- **RUN 2** (2026-08-28 21:37, 負の対照の復旧後): `tests=15 failures+errors=0 skipped=0`

### GameTest(専用サーバー、変換済みの実物クラス)— 7 テスト

| テスト | 内容 |
|---|---|
| state_cache_dedup_tests_every_state_answers_like_vanilla | 全 32,366 ブロック状態 × 6 方向 × 3 支持タイプの isFaceSturdy、衝突形状、hasLargeCollisionShape、遮蔽 6 面 — 「キャッシュ経由」対「キャッシュ非経由の再計算」全数照合 |
| state_cache_dedup_tests_dedup_actually_happened | 非空虚性の門: 共有が実際に起きた数字を要求(seen≥20,000・distinct が 1/4 未満・>500) |
| thread_guard_tests_slim_guard_is_actually_applied | 非空虚性の門: 変換後フィールド aureum$owner の存在 |
| thread_guard_tests_single_thread_cycles_are_silent | 実物検出器 10,000 周 + 実ブロック設置(PalettedContainer 書き込み経路) |
| thread_guard_tests_race_crashes_both_threads_like_vanilla | 実競合: 両スレッドが同一の ReportedException、バニラ文言「Accessing … from multiple threads」 |
| template_cache_tests_eviction_reloads_transparently | 追い出し(実際に ≥1 件)→ 透過的再読込・内容一致 |
| template_cache_tests_worldgen_path_templates_are_evictable | 【回帰】ワールド生成の経路(ディスク実在テンプレートへの getOrCreate)は追い出せること — ベンチが見つけた「全ピン留めで TTL が形骸化」バグの固定 |
| template_cache_tests_player_created_templates_are_pinned | getOrCreate の<b>新規作成枝</b>由来はピン留めされ TTL 0 でも生存、remove で帳簿も消える |

- **RUN 1** (2026-08-28 21:33, report mtime 21:33): 8/8 pass(vanilla always_pass 込み)、失敗 0
- **RUN 2** (2026-08-28 21:38, report mtime 21:38, 負の対照の復旧後): 8/8 pass、失敗 0
- どちらも実行前に `build/run-gametest` を掃除(蓄積ワールドが結果を変える事故の予防)。

## 2. 負の対照(わざと壊して赤)— 全 6 件、すべて赤を実測してから復旧

| # | 壊し方 | 期待した赤 | 実測 |
|---|---|---|---|
| NC1 | `-Daureum.debug.breakStateCacheDedup=true`(最初のキャッシュを全状態に配る) | dedup の 2 テスト | **赤**: `190860 mismatches across 32366 states; first: Block{minecraft:stone} isFaceSturdy(down,FULL) = false but recomputed true` / `32167 caches but 0 distinct`。他 5 テストは緑のまま(隔離確認) |
| NC2 | mixin の checkAndUnlock で例外を握り潰す(ソース編集) | race テスト | **赤**: `the legitimate thread did not crash with ReportedException; got null` |
| NC3 | evictStale のピン留め検査を外す(ソース編集) | pin テスト | **赤**: `the player-created template was evicted … pinning is broken` |
| NC4 | RaceDetectorAlgorithm.unlock の throw を外す | JUnit race | **赤**: `raceThrowsTheSameExceptionOnBothThreads FAILED (AssertionFailedError at :119)` |
| NC5 | shouldDrop の境界を `>=`→`>` | JUnit particle 境界 | **赤**: `particleBudgetBoundaries FAILED (:20)` |
| NC6 | validate の TTL 60 秒床を外す | JUnit TTL 床 | **赤**: `validateEnforcesTtlFloor FAILED (:29)` |
| NC7 | 元の実バグを再現(getOrCreate を無条件ピン留め) | 回帰テスト | **赤**: `a disk-backed template loaded through getOrCreate (the worldgen path) was NOT evictable — the pin heuristic has regressed to pin-everything and the TTL is inert (pinned=1)` |

各対照の復旧後に緑へ戻ることを確認済み(NC1–6 復旧後: JUnit 15/15・GameTest 8/8。
NC7 復旧後: GameTest 9/9 — 回帰テスト追加後の総数)。

### GameTest 追補(TTL 修正後の最終コード)

- ピン留め修正 + 回帰テスト追加後: **9/9 pass**(2026-08-28 23:09、report mtime 同時刻)
- NC7 の赤 → 復旧 → 9/9 pass(23:22)
- 最終確認の 2 回目は下の「最終ゲート」参照。

## 3. 実測(ベンチ)

→ `docs/BENCHMARKS.md`(数値・再現手順・生データの所在)。

## 3.5 最終ゲート(最終コード d687626 以降で実施、すべて 2 回)

| ゲート | 1 回目 | 2 回目 |
|---|---|---|
| `./gradlew clean build` / `build --rerun-tasks` | BUILD SUCCESSFUL(23:40) | BUILD SUCCESSFUL(23:41)— jar の SHA-1 が両ビルドで一致(5d1f7041…) |
| JUnit(--rerun-tasks) | 15/15、失敗 0 | 15/15、失敗 0 |
| GameTest(run dir 掃除つき) | 9/9、失敗 0(23:22) | 9/9、失敗 0(report mtime 23:46:32) |
| Alpha 2.5.1 互換起動(本番形式) | 下記 run1 | 下記 run2 |

### Alpha 互換起動試験(本番形式の専用サーバー、fabric launcher + 本番リマップ jar)

構成: alpha-{backrooms,boss,deco,guns,planarcadia,power,rail,sapporo,sky,survival,vehicles}-2.5.1.jar(11)
+ fabric-api-0.154.2+26.2(Modrinth、SHA-1 照合済み)+ aureum-1.0.0.jar。
判定はログ行の数え上げ(exit code は使わない)。

| 項目 | run1(新規ワールド) | run2(ワールド再読込、sapporo の .spro 導入後) |
|---|---|---|
| Done 行 | **Done (16.963s)** | **Done (3.732s)** |
| MOD 読み込み(sorakaze_* 11 + aureum) | 12/12 | 12/12 |
| aureum trace 行 | 2(dedup: **41,772 → 9,913**・TTL armed) | 2 |
| コンソール `/aureum` の応答 | 全 5 行出力 | 全 5 行出力 |
| mixin エラー | 0 | 0 |
| ERROR 行 | 5 — 全て Alpha 側の既存条件(sapporo の .spro 未配置の意図的な大声 ×3、backrooms のレジストリ空 ×2)。aureum への言及なし | 2 — backrooms の同じ 2 行のみ(.spro を配置したので sapporo は解消) |
| きれいな停止(Stopping + 全次元保存) | ✓ | ✓ |
| GC.run ×3 後の使用ヒープ(jcmd) | 168,687K | 178,527K |

本番環境でも dedup の実効が Alpha の追加ブロックぶん**大きくなる**ことが数字で出ている
(バニラのみ 32,167 → パック込み 41,772 caches seen)。

#### 警告の内訳(両ラン、全数)

Aureum に起因する警告は **0 件**。出たものは以下だけで、すべて環境か Alpha 側の既存事情:

| 警告 | 出所 | 判断 |
|---|---|---|
| `Unable to parse version 27.0 to a codename` | oshi(macOS 27 の版名を知らない) | 環境。無害 |
| `SERVER IS RUNNING IN OFFLINE/INSECURE MODE` 他 3 行 | 試験用 server.properties の `online-mode=false` | 試験設定。意図どおり |
| `Mod me_friwi_{jcef-api,jogl-all,gluegen-rt} uses the version … isn't compatible with … SemVer` | Alpha が同梱する JCEF/JOGL ライブラリの版表記 | Alpha 側の既存事項。Aureum 無関係 |
| `Can't keep up! … 2070ms or 41 ticks behind`(**run2 のみ 1 回**) | Done の 3 秒後、Alpha 各モジュールの初期化中。run1 には無く、run1→run2 の間に**こちらが置いた 12 MB の sapporo 都市データ**の読み込みと一致 | 起動時の一過性。定常状態では再発せず、Aureum 起因ではない |

ERROR 行も同様に全て Alpha 側の既存条件(run1 の 5 行 = sapporo の .spro 未配置を
意図的に大声で言う 3 行 + backrooms のレジストリ空 2 行。.spro を置いた run2 では
backrooms の 2 行のみ)。**どの ERROR/WARN 行にも aureum への言及は無い。**

## 4. この環境で検証できないもの(所有者の実機確認 register)

1. **particleLimit の実効果**(FPS・見た目) — クライアント/GPU なし。判定関数のみ JUnit で固定。
   確認手順: config で `particleLimit: 3000` にして大型花火や爆発の負荷時にパーティクルが
   打ち切られること・通常プレイで違和感がないこと。
2. **blockEntityRenderDistanceCap の実効果** — 同上。`32` に設定して遠くのチェスト類が
   描画されなくなり、近づけば描画されること。ビーコン光柱が距離に関係なく見えること
   (対象外設計の確認)。
3. **クライアント側での /aureum 表示**(翻訳キーの解決、ja_jp 表示)。
4. Sodium との同時起動(このマシンに Sodium/クライアントが無い。設計上チャンク描画系
   ミックスインはゼロだが、実機での同時起動確認は所有者側)。
