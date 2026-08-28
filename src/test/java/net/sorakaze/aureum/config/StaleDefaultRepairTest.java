package net.sorakaze.aureum.config;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 化石既定値の修復機構そのものを、合成の表で固定する
 * (v1.0.0 の実表は空 — {@link AureumConfigTest} が空でも壊れないことを見る。
 * ここは<b>将来行を足したときに正しく働くこと</b>を先に固定しておく)。
 */
class StaleDefaultRepairTest {

	private static final StaleDefaultRepair.Entry[] TABLE = {
		new StaleDefaultRepair.Entry(2, "particleLimit", 4000, 3000),
		new StaleDefaultRepair.Entry(2, "nested.value", 2.5, 1.25),
	};

	private static JsonObject parse(final String json) {
		return JsonParser.parseString(json).getAsJsonObject();
	}

	@Test
	void fossilDefaultIsReplacedAndReported() {
		JsonObject root = parse("{\"particleLimit\": 3000}");
		List<String> repaired = StaleDefaultRepair.repair(root, TABLE, 0,
			LoggerFactory.getLogger("test"), "aureum");
		assertEquals(List.of("particleLimit"), repaired);
		assertEquals(4000, root.get("particleLimit").getAsInt());
	}

	@Test
	void userChosenValueIsNeverTouched() {
		JsonObject root = parse("{\"particleLimit\": 1234}");
		List<String> repaired = StaleDefaultRepair.repair(root, TABLE, 0,
			LoggerFactory.getLogger("test"), "aureum");
		assertTrue(repaired.isEmpty());
		assertEquals(1234, root.get("particleLimit").getAsInt());
	}

	@Test
	void newerStoredVersionSkipsTheEntry() {
		JsonObject root = parse("{\"particleLimit\": 3000}");
		List<String> repaired = StaleDefaultRepair.repair(root, TABLE, 2,
			LoggerFactory.getLogger("test"), "aureum");
		assertTrue(repaired.isEmpty(), "an entry must fire at most once per upgrade");
		assertEquals(3000, root.get("particleLimit").getAsInt());
	}

	@Test
	void nestedPathsAndFloatToleranceWork() {
		// 1.25 は 2 進で正確だが、あえて文字列経由の揺らぎを想定した許容差を確認する。
		JsonObject root = parse("{\"nested\": {\"value\": 1.2500000001}}");
		List<String> repaired = StaleDefaultRepair.repair(root, TABLE, 0,
			LoggerFactory.getLogger("test"), "aureum");
		assertEquals(List.of("nested.value"), repaired);
		assertEquals(2.5, root.getAsJsonObject("nested").get("value").getAsDouble());
	}

	@Test
	void storedVersionReadsBrokenValuesAsZero() {
		assertEquals(0, StaleDefaultRepair.storedVersion(parse("{}")));
		assertEquals(0, StaleDefaultRepair.storedVersion(parse("{\"configVersion\": \"broken\"}")));
		assertEquals(3, StaleDefaultRepair.storedVersion(parse("{\"configVersion\": 3}")));
	}
}
