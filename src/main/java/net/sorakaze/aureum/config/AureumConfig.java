package net.sorakaze.aureum.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.fabricmc.loader.api.FabricLoader;
import net.sorakaze.aureum.Aureum;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Aureum の設定。<b>最適化は 1 つずつ個別に切れる</b> — どれかが他 MOD と
 * 相性問題を起こしたとき、MOD ごと外さずにその 1 つだけを止められるように。
 *
 * <p>方式は Alpha パックと同じ Gson 直読み(欠落キーはフィールド初期値のまま =
 * 旧 config からの移行が安全)+ {@link #validate()} で読み込み時に範囲を矯正 +
 * {@link StaleDefaultRepair} で「昔の既定値の化石」を自動更新。
 *
 * <p><b>読み込み時期の注意:</b> ブロック状態キャッシュの共有化は Blocks の
 * ブートストラップ(= MOD 初期化より前)から参照されるので、このクラスは
 * <b>最初に触られた時点で lazy に読む</b>({@link #get()})。MOD 初期化を待たない。
 */
public final class AureumConfig {

	/** この MOD の config を読み書きする唯一のシリアライザ。 */
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

	// ---- サーバー/共通(起動時) ------------------------------------------------------------

	/**
	 * <b>ブロック状態キャッシュの共有化(重複排除)。</b>
	 *
	 * <p>バニラ 26.2 は全ブロック状態(バニラだけで約 3 万)に 1 個ずつ
	 * 「形状キャッシュ」(衝突形状への参照+面の頑丈さの表 boolean[18])と、
	 * 不透明ブロックの一部に「面ごとの遮蔽形状」配列 VoxelShape[6] を作る。
	 * 内容が完全に同じもの(石とすべての土と…は全部同じ満方体キャッシュ)が
	 * 何千個も別インスタンスで並ぶので、<b>内容が同じなら同じ 1 個を全員で共有する</b>。
	 *
	 * <p>共有するのは<b>不変で同一性に依存しないオブジェクトだけ</b>なので観測可能な
	 * 挙動は変わらない(正当性は GameTest {@code StateCacheDedupTests} が全ブロック状態
	 * × 全方向 × 全支持タイプで突き合わせる)。効果の実測値は README とベンチ結果を参照。
	 *
	 * <p>切ると次回起動からバニラどおり全状態が自前のキャッシュを持つ。
	 */
	public boolean dedupeBlockStateCaches = true;

	/**
	 * <b>スレッド競合検出器の軽量化。</b>
	 *
	 * <p>バニラはチャンクセクションの器(PalettedContainer)1 個につき 1 個、
	 * 「複数スレッドから同時に触られたらクラッシュレポートを出して落とす」ための
	 * 検出器を持ち、その中身が Semaphore + ReentrantLock(計約 128 バイト)。
	 * 2,209 チャンク読み込みの実測で 136,032 個 / 約 17.4 MB がこれだった。
	 * Aureum は同じ検出(同じ場面で同じクラッシュレポート)をフィールド 3 本で行う
	 * 実装に置き換える。検出を<b>外すのではない</b> — 中身を軽くするだけ。
	 *
	 * <p>正当性は JUnit({@code RaceDetectorAlgorithmTest})と GameTest
	 * ({@code ThreadGuardTests} — 実際に 2 スレッドで競合させて両方が
	 * バニラと同じ例外で落ちることを確認)が固定。効果の実測値は README 参照。
	 *
	 * <p>切り替えは<b>再起動で反映</b>(mixin の適用可否なので)。
	 */
	public boolean slimThreadingDetector = true;

	/**
	 * <b>構造物テンプレートキャッシュの追い出し(秒)。0 = off。</b>
	 *
	 * <p>バニラは一度読んだ構造物テンプレート(村・遺跡等の .nbt)を
	 * <b>永遠に</b>メモリへ保持する。ワールド生成が進むほど増える一方で、
	 * 生成が済んだ土地のぶんは二度と読まれない。この秒数より長く触られていない
	 * 項目をキャッシュから外す(次に必要になれば、初回と同じ経路で
	 * ディスクから透過的に読み直される)。
	 *
	 * <p><b>structure block でプレイヤーが作成中のテンプレートは絶対に捨てない</b>
	 * (保存前の作業が消えるため — GameTest {@code TemplateCacheTests} が固定)。
	 *
	 * <p>下限 60 秒(0 は off)。生成中の土地で読み直しが頻発しない安全側の既定 300。
	 * 切り替えは<b>再起動で反映</b>。
	 */
	public int structureTemplateCacheTtlSeconds = 300;

	// ---- クライアント側(このマシンでは計測不能 — 既定 off の opt-in) ------------------------

	/**
	 * <b>画面に同時に存在できるパーティクルの上限。0 = 無効(バニラのまま)。</b>
	 *
	 * <p>正の値にすると、その数を超えるパーティクルの<b>新規追加</b>が捨てられる
	 * (グループが満杯のときバニラ自身がやるのと同じ「新しい方を捨てる」)。
	 * 大規模な爆発・ボス戦での描画負荷を抑えたい人向け。
	 *
	 * <p><b>この環境にはクライアントが無いため FPS への効果は未計測。</b>
	 * 見た目が変わる(パーティクルが減る)機能なので既定は off。
	 * 目安: 2000〜4000。バニラはグループごとの上限しか持たない。
	 */
	public int particleLimit = 0;

	/**
	 * <b>ブロックエンティティ(チェスト・看板等)の描画距離の上限(ブロック)。
	 * 0 = 無効(バニラのまま)。</b>
	 *
	 * <p>バニラはレンダラーごとに既定 64 ブロック(ビーコン等は例外で遠くまで)。
	 * 正の値にすると、<b>その距離より遠い</b>ブロックエンティティの描画をスキップする。
	 * バニラ自身の距離より近くにしか働かない(遠くまで描かせる方向には働かない)。
	 * 画面外専用レンダラー(ビーコンの光柱など)には触れない。
	 *
	 * <p><b>この環境にはクライアントが無いため FPS への効果は未計測。</b>既定は off。
	 * 目安: 32〜48。
	 */
	public int blockEntityRenderDistanceCap = 0;

	// ---- 内部 ------------------------------------------------------------------------------

	/** config スキーマ版。{@link StaleDefaultRepair} が使う。手で編集する値ではない。 */
	public int configVersion = StaleDefaultRepair.CONFIG_VERSION;

	/**
	 * 過去に出荷した既定値の表。<b>v1.0.0 が初版なのでまだ空</b>(空であることに意味がある:
	 * 1 つでも既定値を変えたらここに行を足し、{@link StaleDefaultRepair#CONFIG_VERSION} を
	 * +1 する — それを忘れると利用者のファイルで古い既定値が居座る)。
	 */
	static final StaleDefaultRepair.Entry[] HISTORICAL_DEFAULTS = {};

	private static volatile AureumConfig instance;

	/** 最初のアクセスで読み込む。ブートストラップ(MOD 初期化前)からも安全に呼べる。 */
	public static AureumConfig get() {
		AureumConfig local = instance;
		if (local == null) {
			synchronized (AureumConfig.class) {
				local = instance;
				if (local == null) {
					instance = local = load();
				}
			}
		}
		return local;
	}

	/** 読み込み(ファイルが無ければ既定値で作る)。 */
	private static AureumConfig load() {
		Path path = FabricLoader.getInstance().getConfigDir().resolve(Aureum.MOD_ID + ".json");
		AureumConfig config;
		boolean needsWrite = false;
		if (Files.isRegularFile(path)) {
			try {
				String json = Files.readString(path);
				config = readAndRepair(json);
				needsWrite = true; // 修復や新キーの追記を反映するため常に書き戻す。
			} catch (IOException | RuntimeException e) {
				Aureum.LOGGER.warn("aureum: could not read {} — using defaults for this run"
					+ " (設定ファイルが読めないので今回は既定値で動きます): {}", path, e.toString());
				config = new AureumConfig();
			}
		} else {
			config = new AureumConfig();
			needsWrite = true;
		}
		config.validate();
		if (needsWrite) {
			config.write(path);
		}
		return config;
	}

	/** JSON 文字列から読み、化石既定値を修復してから解釈する。テストから直接呼べる。 */
	public static AureumConfig readAndRepair(final String json) {
		JsonObject object = JsonParser.parseString(json).getAsJsonObject();
		List<String> repaired = StaleDefaultRepair.repair(object, HISTORICAL_DEFAULTS,
			StaleDefaultRepair.storedVersion(object), Aureum.LOGGER, Aureum.MOD_ID);
		if (!repaired.isEmpty()) {
			Aureum.LOGGER.info("aureum: {} stale default(s) repaired", repaired.size());
		}
		AureumConfig config = GSON.fromJson(object, AureumConfig.class);
		if (config == null) {
			config = new AureumConfig();
		}
		config.configVersion = StaleDefaultRepair.CONFIG_VERSION;
		return config;
	}

	/**
	 * 読み込み時の範囲矯正。<b>壊れた値で黙って異常動作しない</b>ための門で、
	 * 矯正したら 1 件ずつログに出す。
	 */
	public void validate() {
		if (structureTemplateCacheTtlSeconds < 0) {
			Aureum.LOGGER.info("aureum: structureTemplateCacheTtlSeconds {} is negative — treating as 0 (off)",
				structureTemplateCacheTtlSeconds);
			structureTemplateCacheTtlSeconds = 0;
		} else if (structureTemplateCacheTtlSeconds > 0 && structureTemplateCacheTtlSeconds < 60) {
			Aureum.LOGGER.info("aureum: structureTemplateCacheTtlSeconds {} is below the 60 s floor —"
				+ " using 60 (a shorter TTL would make active world generation reload templates)",
				structureTemplateCacheTtlSeconds);
			structureTemplateCacheTtlSeconds = 60;
		}
		if (particleLimit < 0) {
			Aureum.LOGGER.info("aureum: particleLimit {} is negative — treating as 0 (off)", particleLimit);
			particleLimit = 0;
		}
		if (blockEntityRenderDistanceCap < 0) {
			Aureum.LOGGER.info("aureum: blockEntityRenderDistanceCap {} is negative — treating as 0 (off)",
				blockEntityRenderDistanceCap);
			blockEntityRenderDistanceCap = 0;
		}
	}

	private void write(final Path path) {
		try {
			Files.createDirectories(path.getParent());
			Files.writeString(path, GSON.toJson(this));
		} catch (IOException e) {
			Aureum.LOGGER.warn("aureum: could not write {}: {}", path, e.toString());
		}
	}
}
