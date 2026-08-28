package net.sorakaze.aureum.dedup;

import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.phys.shapes.VoxelShape;

import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * <b>dedupeBlockStateCaches の中身</b>: ブロック状態ごとの形状キャッシュ
 * ({@code BlockBehaviour.BlockStateBase.Cache})と面ごとの遮蔽形状配列
 * ({@code VoxelShape[6]})を、<b>内容が同じなら同じ 1 個に共有</b>する。
 *
 * <h2>なぜ安全か</h2>
 * <ul>
 *   <li>Cache は全フィールド final の不変オブジェクトで、バニラは同一性
 *       (==)を一切使わない(private 内部クラスで、読み取りメソッドしか無い)。
 *       内容が等しい実体の共有は観測不能。</li>
 *   <li>共有の判定は<b>衝突形状は同一性・faceSturdy はビット内容</b>で行う。
 *       VoxelShape の内容равность は定義されていない(equals 未定義)ので、
 *       形状は同じインスタンスのときだけ「同じ」とみなす — 保守的で誤共有が無い。
 *       (実際には満方体 {@code Shapes.block()} などが広く共有されているので
 *       これで十分効く。)</li>
 *   <li>faceSturdy 配列は作成後に書き込まれない(バニラは読むだけ)。</li>
 * </ul>
 *
 * <h2>正当性の固定</h2>
 * GameTest {@code StateCacheDedupTests} が全ブロック状態 × 全方向 × 全支持タイプで
 * 「キャッシュ経由の答え」と「キャッシュを通さず計算し直した答え」を突き合わせる。
 * さらに<b>共有が実際に起きたこと</b>(統計カウンタ)も検査する — 何もしていないのに
 * 緑になる門をここでは作らない。
 *
 * <p>負の対照用に {@code -Daureum.debug.breakStateCacheDedup=true} があり、
 * これを立てると<b>わざと</b>最初に見たキャッシュを全状態に配る(= 誤共有)。
 * GameTest が赤くなることを確認するためだけの旗で、通常は絶対に立てない。
 */
public final class StateCacheDeduper {

	private static final boolean DEBUG_BREAK = Boolean.getBoolean("aureum.debug.breakStateCacheDedup");

	private static final ConcurrentHashMap<CacheKey, BlockBehaviour.BlockStateBase.Cache> CACHE_POOL =
		new ConcurrentHashMap<>();
	private static final ConcurrentHashMap<List<VoxelShape>, VoxelShape[]> FACE_POOL =
		new ConcurrentHashMap<>();

	private static final AtomicLong CACHES_SEEN = new AtomicLong();
	private static final AtomicLong FACE_ARRAYS_SEEN = new AtomicLong();
	private static volatile BlockBehaviour.BlockStateBase.Cache debugFirstCache;

	private StateCacheDeduper() {
	}

	/**
	 * 共有鍵。{@code shape} は同一性比較(VoxelShape は equals を定義しないので
	 * record の Objects.equals がそのまま同一性になる)。{@code sturdyBits} は
	 * faceSturdy(6 方向 × 支持タイプ、26.2 で 18 要素)のビット詰め。
	 */
	private record CacheKey(VoxelShape shape, boolean largeCollisionShape, long sturdyBits) {
	}

	/** 内容が同じ既出キャッシュがあればそれを、無ければこの実体を正典として返す。 */
	public static BlockBehaviour.BlockStateBase.Cache intern(final BlockBehaviour.BlockStateBase.Cache fresh) {
		CACHES_SEEN.incrementAndGet();
		if (DEBUG_BREAK) {
			// 負の対照: わざと壊す(全状態に最初のキャッシュを配る)。
			BlockBehaviour.BlockStateBase.Cache first = debugFirstCache;
			if (first == null) {
				debugFirstCache = fresh;
				return fresh;
			}
			return first;
		}
		boolean[] sturdy = fresh.faceSturdy;
		if (sturdy.length > 64) {
			// 将来 faceSturdy が 64 を超えたらビット詰めできないので共有を諦める
			// (安全側: 共有しないのは常に正しい)。
			return fresh;
		}
		long bits = 0L;
		for (int i = 0; i < sturdy.length; i++) {
			if (sturdy[i]) {
				bits |= 1L << i;
			}
		}
		return CACHE_POOL.computeIfAbsent(
			new CacheKey(fresh.collisionShape, fresh.largeCollisionShape, bits), key -> fresh);
	}

	/**
	 * 面ごとの遮蔽形状配列の共有。要素の並びが(同一性で)完全一致する既出配列が
	 * あればそれを返す。{@code List.of} の equals は要素の equals = VoxelShape の
	 * 同一性なので、鍵は「6 枚の形状インスタンスの並び」そのもの。
	 */
	public static VoxelShape[] internFaces(final VoxelShape[] fresh) {
		FACE_ARRAYS_SEEN.incrementAndGet();
		return FACE_POOL.computeIfAbsent(List.of(fresh), key -> fresh);
	}

	// ---- 統計(正当性テストの非空虚性の門と /aureum が読む) --------------------------------

	public static long cachesSeen() {
		return CACHES_SEEN.get();
	}

	public static long distinctCaches() {
		return CACHE_POOL.size();
	}

	public static long faceArraysSeen() {
		return FACE_ARRAYS_SEEN.get();
	}

	public static long distinctFaceArrays() {
		return FACE_POOL.size();
	}
}
