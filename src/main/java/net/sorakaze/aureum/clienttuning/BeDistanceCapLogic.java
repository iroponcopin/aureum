package net.sorakaze.aureum.clienttuning;

/**
 * blockEntityRenderDistanceCap の判定だけを切り出した純関数。
 * {@link ParticleBudgetLogic} と同じ理由でここにある。
 */
public final class BeDistanceCapLogic {

	private BeDistanceCapLogic() {
	}

	/**
	 * @param distanceSqr ブロックエンティティ中心とカメラの距離の 2 乗
	 * @param capBlocks   config の blockEntityRenderDistanceCap(正の値のときだけ呼ばれる)
	 * @return true なら描画をスキップ(バニラの shouldRender が false のときと同じ扱い)。
	 *     バニラ自身の距離判定はこの<b>後</b>も走るので、この関数が false でも
	 *     バニラが遠いと判断すれば描かれない — つまり「近づける方向にしか働かない」。
	 */
	public static boolean shouldSkip(final double distanceSqr, final int capBlocks) {
		double cap = capBlocks;
		return distanceSqr > cap * cap;
	}
}
