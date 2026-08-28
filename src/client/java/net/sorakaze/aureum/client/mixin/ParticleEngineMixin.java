package net.sorakaze.aureum.client.mixin;

import net.minecraft.client.particle.Particle;
import net.minecraft.client.particle.ParticleEngine;
import net.minecraft.client.particle.ParticleGroup;
import net.minecraft.client.particle.ParticleRenderType;
import net.sorakaze.aureum.clienttuning.ParticleBudgetLogic;
import net.sorakaze.aureum.config.AureumConfig;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Map;
import java.util.Queue;

/**
 * <b>particleLimit</b>(クライアント・既定 off): 画面に同時に存在できる
 * パーティクル数の上限。
 *
 * <h2>注入点</h2>
 * {@code ParticleEngine.add(Particle)} は 26.2 で<b>全ての</b>パーティクル追加が
 * 通る漏斗(makeParticle 経由も直接 add 経由もここに来る)。上限超過時に
 * ここで捨てるのは、グループが満杯のときバニラ自身がやっている
 * 「新しい方を捨てる」({@code ParticleGroup.add} が false を返す)と同じ向き。
 *
 * <h2>数え方</h2>
 * 生きているパーティクル(グループ別 size の和。グループはレンダータイプ別で
 * 高々数個)+ 追加待ちキュー(ArrayDeque なので size は O(1))。
 *
 * <h2>この環境では実行検証できない</h2>
 * クライアントが無いため、この mixin の実行は所有者の実機確認待ち
 * (README の未検証 register に明記)。判定ロジック
 * {@link ParticleBudgetLogic} だけは素の JUnit で固定してある。
 * 既定 off(particleLimit=0 のときは {@code AureumMixinPlugin} がこの mixin を
 * 適用すらしない)なので、有効にしない限り挙動はバニラと完全一致。
 */
@Mixin(ParticleEngine.class)
public abstract class ParticleEngineMixin {

	@Shadow @Final private Map<ParticleRenderType, ParticleGroup<?>> particles;
	@Shadow @Final private Queue<Particle> particlesToAdd;

	@Inject(method = "add(Lnet/minecraft/client/particle/Particle;)V", at = @At("HEAD"), cancellable = true)
	private void aureum$enforceParticleLimit(final Particle particle, final CallbackInfo ci) {
		int limit = AureumConfig.get().particleLimit;
		if (limit <= 0) {
			return;
		}
		int live = this.particlesToAdd.size();
		for (ParticleGroup<?> group : this.particles.values()) {
			live += group.size();
		}
		if (ParticleBudgetLogic.shouldDrop(live, limit)) {
			ci.cancel();
		}
	}
}
