package net.sorakaze.aureum.template;

/**
 * {@code StructureTemplateManagerMixin} が実装する追い出し口。
 * Aureum のサーバー tick フックが 20 秒ごとに呼ぶ。GameTest もここから叩く。
 */
public interface TemplateCacheAccess {

	/**
	 * 最終アクセスから {@code maxAgeMillis} より長く経った<b>読み込み由来の</b>
	 * キャッシュ項目を捨てる。プレイヤー作成(structure block の getOrCreate)由来の
	 * 項目は<b>絶対に捨てない</b> — 保存前に捨てると作業内容が消えるため。
	 *
	 * @return 捨てた項目数。
	 */
	int aureum$evictStale(long maxAgeMillis);

	/** いまキャッシュにある項目数(テストの非空虚性の門が読む)。 */
	int aureum$cachedCount();

	/** ピン留め(= 絶対に捨てない)されている項目数。 */
	int aureum$pinnedCount();
}
