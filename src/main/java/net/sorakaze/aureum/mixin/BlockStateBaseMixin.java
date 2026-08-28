package net.sorakaze.aureum.mixin;

import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.phys.shapes.VoxelShape;
import net.sorakaze.aureum.config.AureumConfig;
import net.sorakaze.aureum.dedup.StateCacheDeduper;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * <b>dedupeBlockStateCaches</b>: initCache が組み立て終えた直後
 * (<b>バニラの計算はそのまま全部走らせた後</b>)に、出来上がった不変キャッシュを
 * 内容が同じ既出の実体へ差し替える。差し替えるだけで、計算にも読み取りにも触れない。
 *
 * <h2>注入点が RETURN(末尾)である理由</h2>
 * initCache の途中(calculateSolid 等)は this.cache を読むが、そこで読まれる値は
 * 差し替え前後で内容が同一なので、末尾での差し替えは観測不能。バニラの計算経路を
 * 1 命令も変えないのが最も安全。
 *
 * <h2>バニラが変わったら何が壊れるか</h2>
 * <ul>
 *   <li>Cache のフィールドが増えたら: 共有鍵が内容を全部見なくなる恐れがある。
 *       GameTest {@code StateCacheDedupTests} は「キャッシュ経由」対「再計算」を
 *       突き合わせるので、増えたフィールドが観測可能なら赤くなる。</li>
 *   <li>Cache が可変になったら(どこかで書き込まれたら): 共有は不正になる。
 *       同テストが検出する範囲で赤くなるが、これは前提の破壊なので
 *       バージョン更新時に initCache と Cache を目視確認すること。</li>
 * </ul>
 *
 * <p>config {@code dedupeBlockStateCaches}(既定 on)。off にすると
 * {@code AureumMixinPlugin} がこの mixin 自体を適用しない = 完全にバニラ。
 * (initCache は Blocks のブートストラップ = MOD 初期化より前に走るので、
 * ここでの config 参照は lazy 読み込みの {@link AureumConfig#get()} を使う。)
 */
@Mixin(BlockBehaviour.BlockStateBase.class)
public abstract class BlockStateBaseMixin {

	@Shadow private BlockBehaviour.BlockStateBase.Cache cache;
	@Shadow private VoxelShape[] occlusionShapesByFace;

	@Inject(method = "initCache", at = @At("RETURN"))
	private void aureum$dedupeCaches(final CallbackInfo ci) {
		if (!AureumConfig.get().dedupeBlockStateCaches) {
			return;
		}
		BlockBehaviour.BlockStateBase.Cache freshCache = this.cache;
		if (freshCache != null) {
			this.cache = StateCacheDeduper.intern(freshCache);
		}
		VoxelShape[] faces = this.occlusionShapesByFace;
		if (faces != null) {
			this.occlusionShapesByFace = StateCacheDeduper.internFaces(faces);
		}
	}
}
