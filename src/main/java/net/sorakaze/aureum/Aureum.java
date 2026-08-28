package net.sorakaze.aureum;

import net.fabricmc.api.ModInitializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Aureum — Alpha パックとは独立したブランドの軽量化 MOD。
 *
 * <p><b>設計方針(v1.0.0):</b> チャンク描画パイプラインには一切触れない。
 * そこは Sodium の領分であり、触れなければ Sodium と衝突しようがない。
 * Aureum が扱うのは Sodium が扱わない側 — ゲームロジック・メモリ・サーバー tick コスト。
 */
public final class Aureum implements ModInitializer {

	public static final String MOD_ID = "aureum";
	public static final Logger LOGGER = LoggerFactory.getLogger("Aureum");

	@Override
	public void onInitialize() {
		LOGGER.info("Aureum {} initialised.", version());
	}

	private static String version() {
		return net.fabricmc.loader.api.FabricLoader.getInstance()
			.getModContainer(MOD_ID)
			.map(c -> c.getMetadata().getVersion().getFriendlyString())
			.orElse("?");
	}
}
