# Aureum

**Minecraft 26.2 (Fabric) 用の軽量化 MOD / A lightweight optimisation mod for Minecraft 26.2 (Fabric)**

- Mod id: `aureum` — バージョン 1.0.0(独立バージョニング)
- 前提: fabric-loader 0.19.3+ / fabric-api / Java 25+
- サーバー・クライアント両対応(サーバーだけに入れても効く)

## Aureum は何であって、何でないか / What Aureum is — and is not

**Aureum はゲームロジック・メモリ・サーバー tick コストを最適化する。**
これは Sodium が扱わない側の仕事であり、Aureum は**チャンク描画パイプラインに
一切触れない**。触れないので、Sodium と衝突しようがない。Sodium・Alpha パックと
並べて入れて使う設計である。

**Aureum は Sodium の代わりではない。** FPS を上げたければ Sodium を入れること。
Aureum が受け持つのはその反対側 — サーバーと、ゲームロジックの無駄 — である。

*Aureum optimises game logic, memory, and server tick cost — the side Sodium does
not touch. It deliberately stays out of the chunk-render pipeline, so it cannot
conflict with Sodium. It is designed to run alongside Sodium and the Alpha pack.
It is not a Sodium replacement: if you want higher FPS, install Sodium; Aureum
looks after the other side.*

## 中身(v1.0.0)/ What it does

計測値はすべてこのリポジトリの再現可能なベンチ(`tools/bench.sh`、詳細は
`docs/BENCHMARKS.md`)による実測。**実測していない効果はここに書かない。**

| 設定キー | 既定 | 内容 | 実測(2 回ずつの独立測定) |
|---|---|---|---|
| `dedupeBlockStateCaches` | on | ブロック状態ごとの形状キャッシュと遮蔽面配列を、内容が同一なら 1 個に共有(32,167 個 → 6,066 個 / 遮蔽面配列 32,366 → 5,504。数値は起動ログと `/aureum` で確認できる) | ↓ と合算で **−6.3 MB**(2,209 チャンク読み込み時、185.7→179.4 MB。両ラン一致) |
| `slimThreadingDetector` | on | チャンクセクションごとのスレッド競合検出器(バニラは 1 個につき Semaphore+ReentrantLock 計 4 個の付随オブジェクトを抱える)を、同じ検出・同じクラッシュレポートの軽量実装に置換 | ↑ の −6.3 MB の内訳では検出器側が −5.2 MB(クラス別 histogram) |
| `structureTemplateCacheTtlSeconds` | 300 | バニラが永遠に保持する構造物テンプレートキャッシュに保持期限を導入。期限切れは次アクセスで透過的に再読込。structure block で作成中(未保存)のテンプレートは**絶対に**捨てない | **−17.2 MB**(同規模の土地を新規生成後、250.0/250.4→232.7/233.4 MB。両ラン 0.6 MB 以内で一致) |
| `particleLimit` | 0 (off) | 【クライアント】同時パーティクル数の上限 | **未計測**(この開発環境にクライアントが無い)— だから opt-in |
| `blockEntityRenderDistanceCap` | 0 (off) | 【クライアント】チェスト等の描画距離上限(バニラより近づける方向にのみ働く) | **未計測** — 同上 |

**MSPT(tick 時間)は変化なし(ラン間ノイズの範囲内)** — v1.0.0 の最適化は
メモリが主対象であり、速くなったという主張はしない。測定条件・全数値・生データの
所在は `docs/BENCHMARKS.md`。

## 設定 / Configuration

`config/aureum.json`。各キーは 1 つずつ切れる — どれかが他 MOD と相性問題を
起こしたら、MOD ごと外さずそのキーだけ off にできる。off は「Aureum がバニラの
バイトコードに触らない」を意味する(mixin 自体を適用しない)ので、切り分けとして
最も強い保証になる。**変更は再起動で反映。**

古いバージョンの既定値がファイルに残っている場合は自動で現行値に更新される
(自分で変えた値には絶対に触れない。詳細は起動ログに 1 件ずつ出る)。

`/aureum`(要権限)でいま何が有効か・何件共有されているかを確認できる。

## 正直な注意書き / Honest notes

- クライアント側の 2 機能は、この MOD の開発環境(ヘッドレス、GPU なし)では
  **実行検証も FPS 計測もできない**。ロジックの単体テストのみ通してある。
  だから既定 off で、効果を約束しない。
- ヒープ削減の数値は「このベンチのワールド・チャンク数」での実測であり、
  読み込みチャンク数にほぼ比例してスケールする(詳細は docs/BENCHMARKS.md)。
- 検証記録(全テスト × 2 回、負の対照 6 件の赤、Alpha 2.5.1 全 11 MOD との
  同時起動試験 × 2 回)は `docs/VERIFICATION.md`。

## ライセンス / Licence

MIT
