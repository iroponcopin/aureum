package net.sorakaze.aureum.guard;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link RaceDetectorAlgorithm} の状態機械を Minecraft 抜きで固定する。
 * ここで固定した仕様が ThreadingDetectorMixin(@Overwrite 側)の根拠になる —
 * mixin 本体は委譲しかしないので、ここが仕様書を兼ねる。
 *
 * <p>バニラ ThreadingDetector の観測可能な挙動との対応は
 * {@link RaceDetectorAlgorithm} の javadoc の番号に合わせてある。
 */
class RaceDetectorAlgorithmTest {

	/** 素の State 実装(mixin の @Unique フィールドと同じ形)。 */
	private static final class PlainState implements RaceDetectorAlgorithm.State {
		private Thread owner;
		private Thread failed;
		private RuntimeException published;

		@Override
		public Object aureum$monitor() {
			return this;
		}

		@Override
		public Thread aureum$getOwner() {
			return owner;
		}

		@Override
		public void aureum$setOwner(final Thread thread) {
			owner = thread;
		}

		@Override
		public Thread aureum$getFailedThread() {
			return failed;
		}

		@Override
		public void aureum$setFailedThread(final Thread thread) {
			failed = thread;
		}

		@Override
		public RuntimeException aureum$getPublished() {
			return published;
		}

		@Override
		public void aureum$setPublished(final RuntimeException exception) {
			published = exception;
		}
	}

	private static final Function<Thread, RuntimeException> FACTORY =
		failed -> new IllegalStateException("race detected, loser=" + failed.getName());

	/** 仕様 1: 単一スレッドの lock → unlock は何度でも素通り。 */
	@Test
	void singleThreadCyclesAreSilent() {
		PlainState state = new PlainState();
		for (int i = 0; i < 10_000; i++) {
			RaceDetectorAlgorithm.lock(state);
			RaceDetectorAlgorithm.unlock(state, FACTORY);
		}
		assertNull(state.aureum$getOwner(), "owner must be released after the last unlock");
		assertNull(state.aureum$getPublished(), "no exception may ever be published without a race");
	}

	/** 仕様 2: 2 スレッド競合 — 正規側も敗者側も、同一の例外インスタンスで落ちる。 */
	@Test
	@Timeout(value = 10, unit = TimeUnit.SECONDS)
	void raceThrowsTheSameExceptionOnBothThreads() throws Exception {
		PlainState state = new PlainState();
		RaceDetectorAlgorithm.lock(state); // 正規スレッド(このテストスレッド)が保持。

		AtomicReference<Thread> factorySawLoser = new AtomicReference<>();
		Function<Thread, RuntimeException> trackingFactory = failed -> {
			factorySawLoser.set(failed);
			return new IllegalStateException("race detected");
		};

		AtomicReference<RuntimeException> loserCaught = new AtomicReference<>();
		CountDownLatch loserDone = new CountDownLatch(1);
		Thread loser = new Thread(() -> {
			try {
				RaceDetectorAlgorithm.lock(state);
			} catch (RuntimeException e) {
				loserCaught.set(e);
			} finally {
				loserDone.countDown();
			}
		}, "loser-thread");
		loser.start();

		waitUntilWaiting(loser);

		RuntimeException winnerCaught = null;
		try {
			RaceDetectorAlgorithm.unlock(state, trackingFactory);
		} catch (RuntimeException e) {
			winnerCaught = e;
		}

		assertTrue(loserDone.await(5, TimeUnit.SECONDS), "the loser thread must be released, not parked forever");
		assertSame(winnerCaught, loserCaught.get(),
			"vanilla publishes ONE exception instance to both threads — so must we");
		assertSame(loser, factorySawLoser.get(), "the crash report must name the thread that failed to acquire");
		assertEquals("race detected", winnerCaught.getMessage());
	}

	/** 仕様 2 の細部: 敗者が複数なら、バニラ同様「最後に失敗したスレッド」の名前で報告する。 */
	@Test
	@Timeout(value = 10, unit = TimeUnit.SECONDS)
	void lastFailedThreadIsNamedAndAllLosersAreReleased() throws Exception {
		PlainState state = new PlainState();
		RaceDetectorAlgorithm.lock(state);

		AtomicReference<Thread> factorySawLoser = new AtomicReference<>();
		Function<Thread, RuntimeException> trackingFactory = failed -> {
			factorySawLoser.set(failed);
			return new IllegalStateException("race detected");
		};

		CountDownLatch done = new CountDownLatch(2);
		Thread loserB = raceLoser(state, done, "loser-b");
		waitUntilWaiting(loserB);
		Thread loserC = raceLoser(state, done, "loser-c");
		waitUntilWaiting(loserC);

		try {
			RaceDetectorAlgorithm.unlock(state, trackingFactory);
		} catch (RuntimeException expected) {
			// 正規側の throw はここでは主題ではない。
		}
		assertTrue(done.await(5, TimeUnit.SECONDS),
			"every loser must be released (deliberate improvement over vanilla, documented)");
		assertSame(loserC, factorySawLoser.get(), "vanilla names the LAST thread that failed — so must we");
	}

	/** 仕様 3 の隣: lock していないのに unlock — 何も投げず、以後の検出は正常のまま。 */
	@Test
	void unlockWithoutLockIsHarmless() {
		PlainState state = new PlainState();
		RaceDetectorAlgorithm.unlock(state, FACTORY);
		assertNull(state.aureum$getOwner());
		// その後の通常サイクルが正しく動く。
		RaceDetectorAlgorithm.lock(state);
		RaceDetectorAlgorithm.unlock(state, FACTORY);
		assertNull(state.aureum$getOwner());
	}

	private static Thread raceLoser(final PlainState state, final CountDownLatch done, final String name) {
		Thread thread = new Thread(() -> {
			try {
				RaceDetectorAlgorithm.lock(state);
			} catch (RuntimeException expected) {
				// 期待どおり。
			} finally {
				done.countDown();
			}
		}, name);
		thread.start();
		return thread;
	}

	private static void waitUntilWaiting(final Thread thread) throws InterruptedException {
		long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
		while (thread.getState() != Thread.State.WAITING) {
			if (System.nanoTime() > deadline) {
				throw new AssertionError("thread " + thread.getName() + " never reached WAITING; state="
					+ thread.getState());
			}
			Thread.sleep(1);
		}
	}
}
