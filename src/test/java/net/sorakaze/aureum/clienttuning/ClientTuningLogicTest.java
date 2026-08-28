package net.sorakaze.aureum.clienttuning;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * クライアント側トグルの判定関数を固定する。mixin の実行はこの環境では
 * 検証できない(クライアント無し)ので、判定だけでも機械で固定しておく。
 */
class ClientTuningLogicTest {

	@Test
	void particleBudgetBoundaries() {
		// 上限未満は通す。
		assertFalse(ParticleBudgetLogic.shouldDrop(0, 3000));
		assertFalse(ParticleBudgetLogic.shouldDrop(2999, 3000));
		// 上限ちょうどで捨て始める(= 同時存在数が limit を超えない)。
		assertTrue(ParticleBudgetLogic.shouldDrop(3000, 3000));
		assertTrue(ParticleBudgetLogic.shouldDrop(50_000, 3000));
	}

	@Test
	void beDistanceCapBoundaries() {
		// 上限ちょうどは描く(バニラの closerThan と同じ「以内なら描く」向き)。
		assertFalse(BeDistanceCapLogic.shouldSkip(48.0 * 48.0, 48));
		// 1 ブロックでも超えたらスキップ。
		assertTrue(BeDistanceCapLogic.shouldSkip(49.0 * 49.0, 48));
		assertFalse(BeDistanceCapLogic.shouldSkip(0.0, 48));
	}
}
