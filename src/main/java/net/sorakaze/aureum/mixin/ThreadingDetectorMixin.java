package net.sorakaze.aureum.mixin;

import net.minecraft.ReportedException;
import net.minecraft.util.ThreadingDetector;
import net.sorakaze.aureum.guard.RaceDetectorAlgorithm;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.concurrent.Semaphore;
import java.util.concurrent.locks.Lock;

/**
 * <b>slimThreadingDetector</b>: スレッド競合検出器の中身を軽量化する。
 *
 * <h2>何を・なぜ</h2>
 * バニラの ThreadingDetector は 1 個につき Semaphore + ReentrantLock
 * (それぞれ内部 Sync 込み)= 約 96 バイトの付随オブジェクトを抱える。これが
 * <b>チャンクセクションの PalettedContainer 1 個につき 1 個</b>作られるため、
 * 2,209 チャンク読み込みの実測で 136,032 個 / 付随込み約 17.4 MB になっていた
 * (docs/bench の class histogram)。検出のセマンティクスはフィールド 3 本と
 * モニタ同期で完全に再現できるので、そうする。ロジック本体は
 * {@link RaceDetectorAlgorithm}(素の JUnit で検証済み)にあり、ここは委譲だけ。
 *
 * <h2>なぜ安全か(バニラのどの挙動が保存されるか)</h2>
 * <ul>
 *   <li>26.2 で ThreadingDetector を構築するのは PalettedContainer と
 *       LegacyRandomSource の 2 か所だけ(jar 全走査で確認)。どちらも
 *       lock → unlock を対で呼ぶだけの使い方。</li>
 *   <li>単一スレッドの lock/unlock、2 スレッド競合時の「両スレッドが同一の
 *       ReportedException で落ちる」、入れ子 lock のハング — すべて保存
 *       ({@link RaceDetectorAlgorithm} の javadoc と JUnit が仕様)。</li>
 *   <li>クラッシュレポートの内容はバニラの
 *       {@link ThreadingDetector#makeThreadingException} をそのまま呼んで作る。</li>
 *   <li>クラッシュ<b>後</b>の生存性だけがバニラと違う(バニラは敗者スレッドが
 *       永遠に停止し得る/この実装は全員に例外を投げる)。クラッシュ後の JVM は
 *       どのみち落ちる。</li>
 * </ul>
 *
 * <h2>バニラが変わったら何が壊れるか</h2>
 * checkAndLock / checkAndUnlock のシグネチャ変更や呼び出し規約の変更
 * (対で呼ばなくなる等)があれば @Overwrite が適用に失敗するか、
 * GameTest {@code ThreadGuardTests}(実物の変換済みクラスを競合させる)が赤くなる。
 * 新しい構築箇所が増えても、この置き換えは検出器の内部表現しか変えないので
 * そのまま正しく働く。
 *
 * <p>config {@code slimThreadingDetector}(既定 on)。off にすると
 * {@code AureumMixinPlugin} がこの mixin 自体を適用しない = 完全にバニラ。
 */
@Mixin(value = ThreadingDetector.class, priority = 900)
public abstract class ThreadingDetectorMixin implements RaceDetectorAlgorithm.State {

	@Shadow @Final private String name;

	// バニラのロック 2 本は使わない。ctor 完了直後に null へ落として
	// 参照ごと手放す(final なので @Mutable が要る)。フィールドスロット
	// 2 本(16 バイト)は残るが、付随オブジェクト約 96 バイトが消える。
	@Shadow @Final @Mutable private Semaphore lock;
	@Shadow @Final @Mutable private Lock stackTraceLock;

	@Unique private Thread aureum$owner;
	@Unique private Thread aureum$failedThread;
	@Unique private RuntimeException aureum$published;

	@Inject(method = "<init>", at = @At("RETURN"))
	private void aureum$dropLockObjects(final String name, final CallbackInfo ci) {
		this.lock = null;
		this.stackTraceLock = null;
	}

	/**
	 * @author IROHA (Aureum)
	 * @reason 検出器の内部表現をロックオブジェクト無しに置き換える(上記 javadoc)。
	 *     部分的な注入では Semaphore/ReentrantLock の確保自体を避けられないため
	 *     @Overwrite にしている。ロジックは RaceDetectorAlgorithm(JUnit 検証済み)。
	 */
	@Overwrite
	public void checkAndLock() {
		RaceDetectorAlgorithm.lock(this);
	}

	/**
	 * @author IROHA (Aureum)
	 * @reason 同上。クラッシュレポートはバニラの makeThreadingException で作る。
	 */
	@Overwrite
	public void checkAndUnlock() {
		RaceDetectorAlgorithm.unlock(this,
			failed -> aureum$makeVanillaException(this.name, failed));
	}

	@Unique
	private static ReportedException aureum$makeVanillaException(final String name, final Thread failed) {
		return ThreadingDetector.makeThreadingException(name, failed);
	}

	// ---- RaceDetectorAlgorithm.State ------------------------------------------------------

	@Override
	public Object monitor() {
		return this;
	}

	@Override
	public Thread aureum$getOwner() {
		return this.aureum$owner;
	}

	@Override
	public void aureum$setOwner(final Thread thread) {
		this.aureum$owner = thread;
	}

	@Override
	public Thread aureum$getFailedThread() {
		return this.aureum$failedThread;
	}

	@Override
	public void aureum$setFailedThread(final Thread thread) {
		this.aureum$failedThread = thread;
	}

	@Override
	public RuntimeException aureum$getPublished() {
		return this.aureum$published;
	}

	@Override
	public void aureum$setPublished(final RuntimeException exception) {
		this.aureum$published = exception;
	}
}
