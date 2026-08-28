package net.sorakaze.aureum.client.mixin;

import net.minecraft.client.renderer.blockentity.BlockEntityRenderDispatcher;
import net.minecraft.client.renderer.blockentity.state.BlockEntityRenderState;
import net.minecraft.client.renderer.feature.ModelFeatureRenderer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.phys.Vec3;
import net.sorakaze.aureum.clienttuning.BeDistanceCapLogic;
import net.sorakaze.aureum.config.AureumConfig;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * <b>blockEntityRenderDistanceCap</b>(クライアント・既定 off):
 * ブロックエンティティ描画距離の上限。
 *
 * <h2>注入点</h2>
 * {@code tryExtractRenderState} は 26.2 でブロックエンティティ描画の唯一の入口。
 * バニラはこの中で {@code renderer.shouldRender(be, cameraPos)}(レンダラーごとの
 * 距離判定・既定 64)を通す。この mixin はその<b>手前</b>で、config の上限より
 * 遠ければ null を返す — バニラが shouldRender=false のとき返すのと同じ null。
 *
 * <h2>安全側の設計</h2>
 * <ul>
 *   <li>「近づける方向にしか働かない」: この判定を抜けてもバニラ自身の距離判定は
 *       そのまま走るので、バニラより遠くまで描かせることは構造的にできない。</li>
 *   <li>{@code isGloballyRendered}(ビーコンの光柱など画面外でも描く特別扱い)は
 *       <b>対象外</b> — 遠距離から見えることが仕様の描画を消さない。</li>
 * </ul>
 *
 * <h2>この環境では実行検証できない</h2>
 * クライアントが無いため実行は所有者の実機確認待ち(README の未検証 register)。
 * 判定ロジック {@link BeDistanceCapLogic} は素の JUnit で固定。既定 off
 * (=0 のときは mixin 自体が適用されない)なので、有効にしない限りバニラと完全一致。
 */
@Mixin(BlockEntityRenderDispatcher.class)
public abstract class BlockEntityRenderDispatcherMixin {

	@Shadow private Vec3 cameraPos;

	@Inject(method = "tryExtractRenderState", at = @At("HEAD"), cancellable = true)
	private <E extends BlockEntity, S extends BlockEntityRenderState> void aureum$capDistance(
			final E blockEntity, final float partialTicks,
			final ModelFeatureRenderer.CrumblingOverlay breakProgress, final boolean isGloballyRendered,
			final CallbackInfoReturnable<S> cir) {
		int cap = AureumConfig.get().blockEntityRenderDistanceCap;
		if (cap <= 0 || isGloballyRendered) {
			return;
		}
		Vec3 camera = this.cameraPos;
		if (camera == null) {
			return;
		}
		BlockPos pos = blockEntity.getBlockPos();
		double dx = pos.getX() + 0.5 - camera.x;
		double dy = pos.getY() + 0.5 - camera.y;
		double dz = pos.getZ() + 0.5 - camera.z;
		if (BeDistanceCapLogic.shouldSkip(dx * dx + dy * dy + dz * dz, cap)) {
			cir.setReturnValue(null);
		}
	}
}
