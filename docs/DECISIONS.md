# Aureum — 設計判断の記録

方針: **実装は「計測できて、安全に、独自実装で書けるもの」だけ**。
チャンク描画パイプラインには触れない(Sodium の領分。触れなければ衝突しようがない)。
性能の主張は必ず実測とセットで行い、この環境(クライアント無し・GPU 無し)で
測れないものは「未計測」と明記する。

## v1.0.0 で採用した最適化(それぞれの根拠)

| # | 最適化 | 根拠(採用時の実測) |
|---|--------|----------------------|
| 1 | slimThreadingDetector | 2,209 チャンク読込の class histogram で ThreadingDetector + 付随 Semaphore/ReentrantLock が 136,032 組 / 約 17.4 MB(セクションの PalettedContainer 1 個につき 1 組)。検出セマンティクスはフィールド 3 本で完全再現できる。 |
| 2 | structureTemplateCacheTtlSeconds | 同 histogram で StructureBlockInfo 302,650 個 / 7.26 MB + 付随 BlockPos 等。バニラはテンプレートをデータパック再読込まで永遠に保持する。初版はワールド生成経路(getOrCreate)を誤って全ピン留めし TTL が形骸化 — **ベンチの histogram 比較が検出**し、修正+回帰テストを追加(BENCHMARKS.md「測定が捕まえた実バグ」)。 |
| 3 | dedupeBlockStateCaches | 実機トレースで 32,167 個の状態キャッシュが内容 6,066 通り、遮蔽面配列 32,366 個が 5,504 通り — 8 割超が重複。 |
| 4 | particleLimit(クライアント/既定 off) | 発注メニュー由来。この環境で FPS は測れないため opt-in +「未計測」明記で出荷。判定関数だけ JUnit で固定。 |
| 5 | blockEntityRenderDistanceCap(クライアント/既定 off) | 同上。バニラより遠くへは絶対に描かない構造(近づける方向にしか働かない)。 |

## 検討して**却下**した最適化(理由つき)

| 候補 | 却下理由(すべて 26.2 の逆コンパイル/実測で確認) |
|------|--------------------------------------------------|
| ブロック状態の隣接表/プロパティ表の共有(FerriteCore 技法の本丸) | **バニラ 26.2 が既に吸収済み。** StateHolder はフラット配列(propertyKeys/propertyValues/S[][] neighbors)になっており、StateDefinition$StateCollection の statesByPivotCache が行配列をファイバー単位で共有している。残りは状態ごとの外側配列と値配列のみで、これは状態の同一性そのものなので共有不能。 |
| 流体拡散のアロケーション削減 | 26.2 の FlowingFluid は ThreadLocal の遮蔽キャッシュ・MutableBlockPos 再利用・SpreadContext(short キーの状態/穴キャッシュ)まで実装済み — Lithium 相当が本体に入っている。 |
| ランダムティックのセクション枝刈り | LevelChunkSection.isRandomlyTicking() による枝刈りが既にバニラにある。 |
| ランダムティック座標 BlockPos の再利用 | randomTick の受け手は pos を**保持してよい**契約(予約チケットや scheduleTick が保持する)。Mutable 再利用は正しさを壊す。却下。 |
| Direction.values() のクローン除去 | 26.2 は内部で VALUES をキャッシュ済み。残る値クローンは JFR のアロケーション上位に出てこない。 |
| 湧き走査(createState / mob カウント)の増分化 | 挙動同値の証明が現実的でない(順序依存の乱数消費が観測可能に変わる)。Lithium 級の書き換えは v1.0.0 の安全基準を超える。 |
| ホッパー/エンティティ衝突/ライティングの書き換え | 侵襲度が高く、この環境で十分な検証ができない。 |
| PalettedContainer のロック除去(Lithium 方式) | 競合検出そのものを消すのは挙動変更(静かな破損 vs クラッシュレポート)。Aureum は検出を**残したまま**軽くする道(#1)を選んだ。 |
| チャンクメッシング/描画系全般 | 禁止事項(Sodium の領分)。 |

## ベンチ設計の要点

- 判定は exit code ではなく **AUREUM-BENCH RESULT 行とレポート JSON**(mods-src の
  実測: `server.halt(false)` のため rc=0 は何も証明しない)。
- ヒープ標本は **GC → 待ち → 標本の対**を 7 回(先に GC を全部かけてから並べて標本すると
  取り込み中のサーバーではただの単調増加列になる — prep 走行で実測 265→304 MB)。
- ワールドは雛形を複製して毎回同一状態から開始(蓄積したワールドが結果を変える事故は
  mods-src で実測済み)。
- 疑似プレイヤーは placeNewPlayer + EmbeddedChannel の本物経路(backrooms の
  IsolationBootProof で実証済みの方式)。KeepAlive とチャンク束応答を返し続ける。
