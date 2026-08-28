package net.sorakaze.aureum.gametest;

import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.SupportType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;
import net.sorakaze.aureum.dedup.StateCacheDeduper;

/**
 * <b>dedupeBlockStateCaches の正当性を全数で固定する。</b>
 *
 * <p>比較の両辺:
 * <ul>
 *   <li><b>実際の答え</b> — キャッシュ(共有済み)を通る公開 API。</li>
 *   <li><b>参照の答え</b> — キャッシュを通らない再計算
 *       ({@code SupportType.isSupporting} / 3 引数の {@code getCollisionShape} /
 *       {@code getOcclusionShape().getFaceShape}) 。キャッシュ構築時と同じ
 *       {@code EmptyBlockGetter.INSTANCE} + {@code BlockPos.ZERO} で呼ぶ。</li>
 * </ul>
 * 共有の取り違え(石のキャッシュがガラスに配られる等)があれば、どこかの状態で
 * 両辺が食い違う。負の対照: {@code -Daureum.debug.breakStateCacheDedup=true} で
 * わざと取り違えさせると、このテストは赤くなる(実測記録は docs/VERIFICATION.md)。
 */
public class StateCacheDedupTests {

	private static final Direction[] DIRECTIONS = Direction.values();
	private static final SupportType[] SUPPORT_TYPES = SupportType.values();

	/** 全ブロック状態 × 全方向 × 全支持タイプ + 衝突形状 + 遮蔽面形状の全数照合。 */
	@GameTest(maxTicks = 1200)
	public void everyStateAnswersLikeVanilla(final GameTestHelper helper) {
		long statesChecked = 0;
		long mismatches = 0;
		StringBuilder firstMismatch = new StringBuilder();
		for (Block block : BuiltInRegistries.BLOCK) {
			for (BlockState state : block.getStateDefinition().getPossibleStates()) {
				statesChecked++;
				for (Direction direction : DIRECTIONS) {
					for (SupportType supportType : SUPPORT_TYPES) {
						boolean actual = state.isFaceSturdy(EmptyBlockGetter.INSTANCE, BlockPos.ZERO,
							direction, supportType);
						boolean expected = supportType.isSupporting(state, EmptyBlockGetter.INSTANCE,
							BlockPos.ZERO, direction);
						if (actual != expected) {
							mismatches++;
							noteFirst(firstMismatch, state + " isFaceSturdy(" + direction + "," + supportType
								+ ") = " + actual + " but recomputed " + expected);
						}
					}
				}
				VoxelShape actualCollision = state.getCollisionShape(EmptyBlockGetter.INSTANCE, BlockPos.ZERO);
				VoxelShape referenceCollision = state.getCollisionShape(EmptyBlockGetter.INSTANCE, BlockPos.ZERO,
					CollisionContext.empty());
				if (!shapesEqual(actualCollision, referenceCollision)) {
					mismatches++;
					noteFirst(firstMismatch, state + " collision shape differs from recomputed shape");
				}
				// cache 無し(動的形状ブロック)のときの vanilla の答えは「true 固定」なので、
				// large の照合はキャッシュ持ちの状態だけが対象。
				if (!state.getBlock().hasDynamicShape()) {
					boolean actualLarge = state.hasLargeCollisionShape();
					boolean referenceLarge = computeLarge(referenceCollision);
					if (actualLarge != referenceLarge) {
						mismatches++;
						noteFirst(firstMismatch, state + " hasLargeCollisionShape=" + actualLarge
							+ " but recomputed " + referenceLarge);
					}
				}
				if (state.canOcclude()) {
					VoxelShape occlusion = state.getOcclusionShape();
					for (Direction direction : DIRECTIONS) {
						VoxelShape actualFace = state.getFaceOcclusionShape(direction);
						VoxelShape referenceFace = occlusion.getFaceShape(direction);
						if (!shapesEqual(actualFace, referenceFace)) {
							mismatches++;
							noteFirst(firstMismatch, state + " occlusion face " + direction
								+ " differs from recomputed face");
						}
					}
				}
			}
		}
		if (statesChecked < 20_000) {
			helper.fail("only " + statesChecked + " block states were checked — the registry walk is broken"
				+ " and this green would prove nothing");
			return;
		}
		if (mismatches > 0) {
			helper.fail(mismatches + " mismatches across " + statesChecked + " states; first: " + firstMismatch);
			return;
		}
		helper.succeed();
	}

	/**
	 * <b>非空虚性の門</b>: 共有が実際に起きたことを数字で要求する。
	 * mixin が適用されていなければ cachesSeen() は 0 でこのテストは赤い —
	 * 「何もしていないのに緑」をここで塞ぐ。
	 */
	@GameTest(maxTicks = 100)
	public void dedupActuallyHappened(final GameTestHelper helper) {
		long seen = StateCacheDeduper.cachesSeen();
		long distinct = StateCacheDeduper.distinctCaches();
		long facesSeen = StateCacheDeduper.faceArraysSeen();
		long facesDistinct = StateCacheDeduper.distinctFaceArrays();
		if (seen < 20_000) {
			helper.fail("state-cache dedup saw only " + seen + " caches — the mixin did not run over bootstrap");
			return;
		}
		if (distinct <= 500 || distinct * 4 > seen) {
			helper.fail("sharing looks broken: " + seen + " caches but " + distinct + " distinct"
				+ " (expected heavy sharing, and >500 genuinely distinct shapes)");
			return;
		}
		if (facesSeen < 20_000 || facesDistinct * 4 > facesSeen) {
			helper.fail("occlusion-face sharing looks broken: " + facesSeen + " arrays, "
				+ facesDistinct + " distinct");
			return;
		}
		helper.succeed();
	}

	private static void noteFirst(final StringBuilder sink, final String message) {
		if (sink.isEmpty()) {
			sink.append(message);
		}
	}

	private static boolean shapesEqual(final VoxelShape a, final VoxelShape b) {
		return a == b || a.toAabbs().equals(b.toAabbs());
	}

	/** バニラ Cache コンストラクタの largeCollisionShape の定義を再計算(同じ式)。 */
	private static boolean computeLarge(final VoxelShape collision) {
		for (Direction.Axis axis : Direction.Axis.values()) {
			if (collision.min(axis) < 0.0 || collision.max(axis) > 1.0) {
				return true;
			}
		}
		return false;
	}
}
