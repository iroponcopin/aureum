package net.sorakaze.aureum.bench;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.sorakaze.aureum.Aureum;

/**
 * ベンチ運転手の起動口。<b>-Daureum.bench=true のときだけ</b>何かをする。
 * gametest コンパニオン MOD 側にあるので配布 jar には入らない。
 *
 * <p>判定規約(mods-src の実測から): exit code は判定に使わない。
 * 判定は「AUREUM-BENCH RESULT」ログ行とレポート JSON の存在+mtime で行う。
 */
public final class BenchEntrypoint implements ModInitializer {

	@Override
	public void onInitialize() {
		if (!Boolean.getBoolean("aureum.bench")) {
			return;
		}
		Aureum.LOGGER.info("AUREUM-BENCH armed (aureum.bench=true).");
		BenchDriver driver = new BenchDriver();
		ServerLifecycleEvents.SERVER_STARTED.register(driver::onServerStarted);
		ServerTickEvents.START_SERVER_TICK.register(driver::onTickStart);
		ServerTickEvents.END_SERVER_TICK.register(driver::onTickEnd);
	}
}
