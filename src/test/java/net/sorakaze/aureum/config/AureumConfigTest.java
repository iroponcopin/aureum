package net.sorakaze.aureum.config;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 読み込み時の範囲矯正と、利用者が選んだ値の保全を固定する。 */
class AureumConfigTest {

	@Test
	void validateClampsBrokenValues() {
		AureumConfig config = new AureumConfig();
		config.structureTemplateCacheTtlSeconds = -5;
		config.particleLimit = -1;
		config.blockEntityRenderDistanceCap = -3;
		config.validate();
		assertEquals(0, config.structureTemplateCacheTtlSeconds, "negative TTL means off");
		assertEquals(0, config.particleLimit);
		assertEquals(0, config.blockEntityRenderDistanceCap);
	}

	@Test
	void validateEnforcesTtlFloor() {
		AureumConfig config = new AureumConfig();
		config.structureTemplateCacheTtlSeconds = 30;
		config.validate();
		assertEquals(60, config.structureTemplateCacheTtlSeconds,
			"a sub-minute TTL would make active worldgen thrash template loads");
		config.structureTemplateCacheTtlSeconds = 0;
		config.validate();
		assertEquals(0, config.structureTemplateCacheTtlSeconds, "0 stays off — it is not pushed to 60");
	}

	@Test
	void userChosenValuesSurviveReadAndRepair() {
		String json = """
			{
			  "dedupeBlockStateCaches": false,
			  "slimThreadingDetector": false,
			  "structureTemplateCacheTtlSeconds": 900,
			  "particleLimit": 2500
			}
			""";
		AureumConfig config = AureumConfig.readAndRepair(json);
		assertFalse(config.dedupeBlockStateCaches, "the user turned this off — it must stay off");
		assertFalse(config.slimThreadingDetector);
		assertEquals(900, config.structureTemplateCacheTtlSeconds);
		assertEquals(2500, config.particleLimit);
		// 欠落キーはフィールド既定値のまま(移行安全)。
		assertEquals(0, config.blockEntityRenderDistanceCap);
		assertEquals(StaleDefaultRepair.CONFIG_VERSION, config.configVersion, "version is stamped on read");
	}

	@Test
	void defaultsAreTheDocumentedOnes() {
		AureumConfig config = new AureumConfig();
		assertTrue(config.dedupeBlockStateCaches);
		assertTrue(config.slimThreadingDetector);
		assertEquals(300, config.structureTemplateCacheTtlSeconds);
		assertEquals(0, config.particleLimit, "client toggles ship OFF — unmeasured in this environment");
		assertEquals(0, config.blockEntityRenderDistanceCap, "client toggles ship OFF — unmeasured in this environment");
	}
}
