package net.sorakaze.aureum;

import com.mojang.brigadier.Command;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.sorakaze.aureum.config.AureumConfig;
import net.sorakaze.aureum.dedup.StateCacheDeduper;
import net.sorakaze.aureum.template.TemplateCacheAccess;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Aureum — Alpha パックとは独立したブランドの軽量化 MOD。
 *
 * <p><b>設計方針(v1.0.0):</b> チャンク描画パイプラインには一切触れない。
 * そこは Sodium の領分であり、触れなければ Sodium と衝突しようがない。
 * Aureum が扱うのは Sodium が扱わない側 — ゲームロジック・メモリ・サーバー tick コスト。
 *
 * <p>個々の最適化は {@link AureumConfig} で 1 つずつ切れる。off は
 * {@code AureumMixinPlugin} により「バニラのバイトコードに触らない」を意味する。
 */
public final class Aureum implements ModInitializer {

	public static final String MOD_ID = "aureum";
	public static final Logger LOGGER = LoggerFactory.getLogger("Aureum");

	/** 構造物テンプレートキャッシュの掃除間隔(tick)。TTL 判定自体は config の秒数。 */
	private static final int TEMPLATE_SWEEP_INTERVAL_TICKS = 400;

	@Override
	public void onInitialize() {
		AureumConfig config = AureumConfig.get();
		LOGGER.info("Aureum {} initialised — dedupeBlockStateCaches={}, slimThreadingDetector={},"
				+ " structureTemplateCacheTtlSeconds={}, particleLimit={}, blockEntityRenderDistanceCap={}",
			version(), config.dedupeBlockStateCaches, config.slimThreadingDetector,
			config.structureTemplateCacheTtlSeconds, config.particleLimit, config.blockEntityRenderDistanceCap);

		ServerLifecycleEvents.SERVER_STARTED.register(Aureum::logActivationTrace);
		ServerTickEvents.END_SERVER_TICK.register(Aureum::sweepTemplateCache);
		CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) ->
			dispatcher.register(Commands.literal("aureum")
				.requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
				.executes(context -> reportStatus(context.getSource()))));
	}

	/**
	 * <b>正直な起動痕跡</b>: 各最適化が「実際に何かをした」ことを数字で残す。
	 * 互換性起動試験はこの行を数える(存在しない効果を主張する緑を作らないため)。
	 */
	private static void logActivationTrace(final MinecraftServer server) {
		AureumConfig config = AureumConfig.get();
		if (config.dedupeBlockStateCaches) {
			LOGGER.info("aureum trace: state-cache dedup — {} caches seen, {} distinct kept;"
					+ " {} occlusion-face arrays seen, {} distinct kept",
				StateCacheDeduper.cachesSeen(), StateCacheDeduper.distinctCaches(),
				StateCacheDeduper.faceArraysSeen(), StateCacheDeduper.distinctFaceArrays());
		}
		if (config.structureTemplateCacheTtlSeconds > 0
				&& server.getStructureManager() instanceof TemplateCacheAccess access) {
			LOGGER.info("aureum trace: template cache TTL armed ({} s, sweep every {} ticks), {} cached now",
				config.structureTemplateCacheTtlSeconds, TEMPLATE_SWEEP_INTERVAL_TICKS, access.aureum$cachedCount());
		}
	}

	private static void sweepTemplateCache(final MinecraftServer server) {
		AureumConfig config = AureumConfig.get();
		if (config.structureTemplateCacheTtlSeconds <= 0
				|| server.getTickCount() % TEMPLATE_SWEEP_INTERVAL_TICKS != 0) {
			return;
		}
		if (server.getStructureManager() instanceof TemplateCacheAccess access) {
			access.aureum$evictStale(config.structureTemplateCacheTtlSeconds * 1000L);
		}
	}

	/**
	 * {@code /aureum} — 何が有効でいま何をしているかを運営者に見せる。
	 * MOD を入れていないクライアントでも読めるよう、翻訳キーではなく
	 * fallback 付き翻訳(英)で送る。
	 */
	private static int reportStatus(final CommandSourceStack source) {
		AureumConfig config = AureumConfig.get();
		Component on = line("aureum.status.on", "on");
		Component off = line("aureum.status.off", "off");
		source.sendSuccess(() -> line("aureum.status.header",
			"Aureum %s — active optimisations:", version()), false);
		source.sendSuccess(() -> config.dedupeBlockStateCaches
			? line("aureum.status.dedup.on", "- Block-state cache sharing: %s caches held as %s distinct copies",
				StateCacheDeduper.cachesSeen(), StateCacheDeduper.distinctCaches())
			: line("aureum.status.dedup.off", "- Block-state cache sharing: %s", off), false);
		source.sendSuccess(() -> line("aureum.status.guard", "- Slim threading detector: %s",
			config.slimThreadingDetector ? on : off), false);
		if (config.structureTemplateCacheTtlSeconds > 0
				&& source.getServer().getStructureManager() instanceof TemplateCacheAccess access) {
			source.sendSuccess(() -> line("aureum.status.template.on",
				"- Structure template cache TTL: %s s (%s cached, %s pinned)",
				config.structureTemplateCacheTtlSeconds, access.aureum$cachedCount(),
				access.aureum$pinnedCount()), false);
		} else {
			source.sendSuccess(() -> line("aureum.status.template.off",
				"- Structure template cache TTL: %s", off), false);
		}
		source.sendSuccess(() -> line("aureum.status.client",
			"- Client toggles (not measurable on a dedicated server): particle limit %s,"
				+ " block-entity render distance cap %s",
			config.particleLimit > 0 ? Component.literal(Integer.toString(config.particleLimit)) : off,
			config.blockEntityRenderDistanceCap > 0
				? Component.literal(Integer.toString(config.blockEntityRenderDistanceCap)) : off), false);
		return Command.SINGLE_SUCCESS;
	}

	private static Component line(final String key, final String fallback, final Object... args) {
		return Component.translatableWithFallback(key, fallback, args);
	}

	private static String version() {
		return FabricLoader.getInstance()
			.getModContainer(MOD_ID)
			.map(c -> c.getMetadata().getVersion().getFriendlyString())
			.orElse("?");
	}
}
