package net.sorakaze.aureum.mixin;

import net.sorakaze.aureum.Aureum;
import net.sorakaze.aureum.config.AureumConfig;
import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

import java.util.List;
import java.util.Set;

/**
 * <b>config の off は「mixin を適用しない」を意味する</b>ための門番。
 *
 * <p>最適化を実行時の if で殺すのではなく、そもそもバニラのバイトコードに
 * 触らない。off にした利用者のサーバー/クライアントは 1 命令も変わらない —
 * 相性問題の切り分けとして最も強い保証になる。適用可否は起動時に決まるので、
 * config の変更は<b>再起動で反映</b>(各キーの javadoc にも明記)。
 *
 * <p>この判定は mixin ブートストラップ(ゲームクラスが 1 つも読まれる前)に走る。
 * ここで触ってよいのは loader と自分の config だけ — Minecraft のクラスは絶対に
 * 参照しないこと({@link AureumConfig} は素の Gson + loader API しか使わない)。
 */
public final class AureumMixinPlugin implements IMixinConfigPlugin {

	@Override
	public boolean shouldApplyMixin(final String targetClassName, final String mixinClassName) {
		AureumConfig config = AureumConfig.get();
		String simpleName = mixinClassName.substring(mixinClassName.lastIndexOf('.') + 1);
		boolean apply = switch (simpleName) {
			case "ThreadingDetectorMixin" -> config.slimThreadingDetector;
			case "BlockStateBaseMixin" -> config.dedupeBlockStateCaches;
			case "StructureTemplateManagerMixin" -> config.structureTemplateCacheTtlSeconds > 0;
			case "ParticleEngineMixin" -> config.particleLimit > 0;
			case "BlockEntityRenderDispatcherMixin" -> config.blockEntityRenderDistanceCap > 0;
			// 知らない mixin は「適用する」に倒す(旗の付け忘れで silently 無効化される
			// 方が、適用されて GameTest に見張られるより危険)。
			default -> true;
		};
		if (!apply) {
			Aureum.LOGGER.info("aureum: {} disabled by config — vanilla bytecode untouched", simpleName);
		}
		return apply;
	}

	@Override
	public void onLoad(final String mixinPackage) {
	}

	@Override
	public String getRefMapperConfig() {
		return null;
	}

	@Override
	public void acceptTargets(final Set<String> myTargets, final Set<String> otherTargets) {
	}

	@Override
	public List<String> getMixins() {
		return null;
	}

	@Override
	public void preApply(final String targetClassName, final ClassNode targetClass,
			final String mixinClassName, final IMixinInfo mixinInfo) {
	}

	@Override
	public void postApply(final String targetClassName, final ClassNode targetClass,
			final String mixinClassName, final IMixinInfo mixinInfo) {
	}
}
