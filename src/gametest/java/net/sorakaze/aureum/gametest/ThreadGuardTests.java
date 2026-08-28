package net.sorakaze.aureum.gametest;

import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.ReportedException;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.util.ThreadingDetector;
import net.minecraft.world.level.block.Blocks;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * <b>slimThreadingDetector を、変換済みの実物クラスで検証する。</b>
 * (アルゴリズム自体は素の JUnit {@code RaceDetectorAlgorithmTest} が固定している。
 * ここで見るのは「mixin が本当に適用され、実物が同じ振る舞いをすること」。)
 *
 * <p>負の対照: {@code slimThreadingDetector=false} の config で走らせると
 * {@link #slimGuardIsActuallyApplied} が赤くなる(mixin 不適用の検出)。
 * さらに意味論の負の対照として、mixin の unlock から throw を一時的に外した
 * ビルドで {@link #raceCrashesBothThreadsLikeVanilla} が赤くなることを確認済み
 * (実測記録は docs/VERIFICATION.md)。
 */
public class ThreadGuardTests {

	/**
	 * <b>非空虚性の門</b>: 実物の ThreadingDetector に置き換え後のフィールドが
	 * 存在すること。mixin が適用されていなければ以降の緑は全部無意味なので、
	 * まずそれ自体を検査する。
	 */
	@GameTest(maxTicks = 100)
	public void slimGuardIsActuallyApplied(final GameTestHelper helper) {
		try {
			ThreadingDetector.class.getDeclaredField("aureum$owner");
		} catch (NoSuchFieldException e) {
			helper.fail("ThreadingDetector has no aureum$owner field — the slim-guard mixin did not apply;"
				+ " every other green in this class would prove nothing");
			return;
		}
		helper.succeed();
	}

	/** 単一スレッドの大量 lock/unlock と、実際のブロック設置(セクション書き込み経路)。 */
	@GameTest(maxTicks = 200)
	public void singleThreadCyclesAreSilent(final GameTestHelper helper) {
		ThreadingDetector detector = new ThreadingDetector("aureum-gametest-cycles");
		for (int i = 0; i < 10_000; i++) {
			detector.checkAndLock();
			detector.checkAndUnlock();
		}
		// 実物の PalettedContainer 書き込み経路(acquire/release)を大量に通す。
		for (int i = 0; i < 6; i++) {
			for (int j = 0; j < 6; j++) {
				helper.setBlock(new BlockPos(1 + i % 6, 1 + j % 3, 1 + (i + j) % 6),
					(i + j) % 2 == 0 ? Blocks.STONE : Blocks.GLASS);
			}
		}
		helper.succeed();
	}

	/**
	 * 2 スレッド競合: 正規側(サーバースレッド)も敗者側も、<b>同一の</b>
	 * ReportedException で落ち、レポートはバニラの文言
	 * 「Accessing ... from multiple threads」を持つこと。
	 */
	@GameTest(maxTicks = 400)
	public void raceCrashesBothThreadsLikeVanilla(final GameTestHelper helper) {
		ThreadingDetector detector = new ThreadingDetector("aureum-gametest-probe");
		detector.checkAndLock(); // サーバースレッドが正規の保持者。

		AtomicReference<Throwable> loserCaught = new AtomicReference<>();
		CountDownLatch loserDone = new CountDownLatch(1);
		Thread loser = new Thread(() -> {
			try {
				detector.checkAndLock();
			} catch (Throwable t) {
				loserCaught.set(t);
			} finally {
				loserDone.countDown();
			}
		}, "aureum-gametest-loser");
		loser.start();

		try {
			long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
			while (loser.getState() != Thread.State.WAITING) {
				if (System.nanoTime() > deadline) {
					helper.fail("the loser thread never blocked on the detector; state=" + loser.getState());
					return;
				}
				Thread.onSpinWait();
			}

			Throwable winnerCaught = null;
			try {
				detector.checkAndUnlock();
			} catch (Throwable t) {
				winnerCaught = t;
			}
			if (!loserDone.await(5, TimeUnit.SECONDS)) {
				helper.fail("the loser thread was never released");
				return;
			}
			if (!(winnerCaught instanceof ReportedException reported)) {
				helper.fail("the legitimate thread did not crash with ReportedException; got " + winnerCaught);
				return;
			}
			if (winnerCaught != loserCaught.get()) {
				helper.fail("vanilla publishes ONE exception instance to both threads; winner="
					+ winnerCaught + " loser=" + loserCaught.get());
				return;
			}
			Throwable cause = reported.getCause();
			String expected = "Accessing aureum-gametest-probe from multiple threads";
			if (cause == null || !expected.equals(cause.getMessage())) {
				helper.fail("crash report text differs from vanilla's; got: "
					+ (cause == null ? "null" : cause.getMessage()));
				return;
			}
			helper.succeed();
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			helper.fail("interrupted while choreographing the race");
		}
	}
}
