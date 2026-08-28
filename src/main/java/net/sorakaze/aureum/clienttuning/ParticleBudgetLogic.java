package net.sorakaze.aureum.clienttuning;

/**
 * particleLimit の判定だけを切り出した純関数。mixin はこれを呼ぶだけにして、
 * 判定そのものはクライアント無しの素の JUnit で固定する
 * (クライアント側の mixin はこの環境では実行検証できないため、
 * せめて判定は機械で固定する — 検証できない残りは README の register に明記)。
 */
public final class ParticleBudgetLogic {

	private ParticleBudgetLogic() {
	}

	/**
	 * @param liveAndQueued いま画面に居るパーティクル数 + 今 tick 追加待ちの数
	 * @param limit         config の particleLimit(正の値のときだけ呼ばれる)
	 * @return true なら新規追加を捨てる(グループ満杯時にバニラ自身がやる
	 *     「新しい方を捨てる」と同じ向きの間引き)
	 */
	public static boolean shouldDrop(final int liveAndQueued, final int limit) {
		return liveAndQueued >= limit;
	}
}
