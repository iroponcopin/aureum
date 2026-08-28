package net.sorakaze.aureum.guard;

import java.util.function.Function;

/**
 * <b>ThreadingDetector の状態機械を、ロックオブジェクトを持たずに再実装したもの。</b>
 *
 * <p>バニラ 26.2 の {@code net.minecraft.util.ThreadingDetector} は 1 個につき
 * {@code Semaphore}(+ NonfairSync)と {@code ReentrantLock}(+ NonfairSync)を抱える。
 * これがチャンクセクションの {@code PalettedContainer} 1 個につき 1 個
 * (= セクションあたり 2 個)作られ、実測(2,209 チャンク読み込み時の
 * class histogram)で <b>136,032 個 / 付随オブジェクト込み約 17.4 MB</b> を占めていた。
 * この再実装は同じ検出をフィールド 3 本(owner / failedThread / published)+
 * モニタ同期だけで行う。ロジックはこのクラスに置き、mixin は委譲するだけにする —
 * <b>状態機械そのものを素の JUnit で(Minecraft 抜きで)検証できるようにするため。</b>
 *
 * <h2>保存しているバニラの観測可能な挙動</h2>
 * <ol>
 *   <li><b>単一スレッドの lock → unlock の繰り返し</b>: 何も起きない(高速経路)。</li>
 *   <li><b>2 スレッドの競合</b>: 後から来たスレッドは記録されてブロックし、
 *       正規のスレッドが unlock した瞬間に<b>両方のスレッドが同一の例外</b>
 *       (unlock 側が factory で作って publish したもの)を投げる。
 *       バニラも全く同じ順序で同じ例外を両方に投げる。</li>
 *   <li><b>同一スレッドの入れ子 lock</b>: バニラはセマフォが非再入なので
 *       自分自身を永遠に待つ(ハング)。この実装も同じ待ちに入る
 *       (owner が自分でも「取得失敗」として扱う)。挙動一致。
 *       — なお PalettedContainer は入れ子 acquire を行わないので、
 *       この経路は実運用では発生しない。</li>
 * </ol>
 *
 * <h2>意図的に一致させていない病的経路(バニラが自分では踏まない経路)</h2>
 * <ul>
 *   <li><b>3 スレッド以上の競合クラッシュ時</b>: バニラはセマフォの permit が 1 つしか
 *       返らないため、敗者のうち 1 スレッドが永遠に停止したまま JVM ごと落ちる。
 *       この実装は publish 時に<b>全</b>敗者を起こして同じ例外を投げさせる。
 *       クラッシュレポートの内容・正規スレッドの挙動は同一。</li>
 *   <li><b>lock していないのに unlock</b>: バニラは permit が 2 になり以後の検出が
 *       静かに壊れる。この実装は owner を null にするだけで、以後も検出は正しく働く。</li>
 * </ul>
 * どちらも「壊れ方が同じかそれより安全」側にしか違わない。
 *
 * <h2>割り込み</h2>
 * バニラは待機中に割り込まれると割り込みフラグを立て直して<b>その時点の</b>
 * fullException を投げる(publish 前なら null なので NPE になる — バニラのまま)。
 * この実装も同じ: 割り込みで待機を打ち切り、その時点の published を投げる。
 */
public final class RaceDetectorAlgorithm {

	private RaceDetectorAlgorithm() {
	}

	/**
	 * 検出器 1 個ぶんの状態。mixin(実物の ThreadingDetector)と
	 * JUnit(素の実装)の両方がこれを実装する。
	 *
	 * <p>すべてのメソッドは {@link #aureum$monitor()} の同期下でだけ呼ばれる
	 * (published の読みだけは待機ループの再確認でも使うが、それも同期下)。
	 */
	public interface State {
		/** 同期と wait/notify に使うモニタ。検出器自身を返すのが自然。 */
		Object aureum$monitor();

		Thread aureum$getOwner();

		void aureum$setOwner(Thread thread);

		Thread aureum$getFailedThread();

		void aureum$setFailedThread(Thread thread);

		RuntimeException aureum$getPublished();

		void aureum$setPublished(RuntimeException exception);
	}

	/**
	 * バニラ {@code checkAndLock()} に相当。
	 *
	 * <p>取得できたら黙って返る。他スレッド(または入れ子の自分)が保持中なら、
	 * 自分を「取得に失敗したスレッド」として記録して待機し、unlock 側が
	 * publish した例外を投げる。
	 */
	public static void lock(final State state) {
		Object monitor = state.aureum$monitor();
		synchronized (monitor) {
			if (state.aureum$getOwner() == null) {
				state.aureum$setOwner(Thread.currentThread());
				return;
			}
			// 競合(または入れ子)。バニラの threadThatFailedToAcquire と同じく
			// 「最後に失敗したスレッド」を記録する(上書きもバニラと同じ)。
			state.aureum$setFailedThread(Thread.currentThread());
			boolean interrupted = false;
			while (state.aureum$getPublished() == null && !interrupted) {
				try {
					monitor.wait();
				} catch (InterruptedException e) {
					Thread.currentThread().interrupt();
					interrupted = true;
				}
			}
			// バニラと同じく「その時点の」例外を投げる。割り込みで publish 前に
			// 抜けた場合は null を投げようとして NPE になる — それもバニラと同じ。
			throw state.aureum$getPublished();
		}
	}

	/**
	 * バニラ {@code checkAndUnlock()} に相当。
	 *
	 * @param exceptionFactory 失敗スレッドを引数に、公開する例外を作る。
	 *     実運用では {@code ThreadingDetector.makeThreadingException(name, failed)}
	 *     (= バニラの実物)を渡す。クラッシュレポートの内容はバニラが作る。
	 */
	public static void unlock(final State state, final Function<Thread, RuntimeException> exceptionFactory) {
		Object monitor = state.aureum$monitor();
		synchronized (monitor) {
			Thread failed = state.aureum$getFailedThread();
			if (failed != null) {
				RuntimeException exception = exceptionFactory.apply(failed);
				state.aureum$setPublished(exception);
				state.aureum$setOwner(null);
				monitor.notifyAll();
				// バニラと同じく、正規のスレッド側も同じ例外で落ちる。
				throw exception;
			}
			state.aureum$setOwner(null);
		}
	}
}
