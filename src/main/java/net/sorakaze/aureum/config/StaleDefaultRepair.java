package net.sorakaze.aureum.config;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.List;

/**
 * <b>古い既定値が config ファイルに居座り続けるのを自動で直す。</b>
 *
 * <p>仕組みは Alpha パック各モジュールと同じ(sorakaze-sky の同名クラスが原型。
 * 発生した実害と「取り除く」ではなく「書き込む」でなければならない理由は
 * そちらの javadoc に詳しい)。Aureum は独立 MOD なので自分のコピーを持つ。
 *
 * <p>キーごとに<b>過去に出荷したすべての既定値</b>を表に持ち、読み込み時に:
 * <ul>
 *   <li>保存値が<b>いずれかの過去の既定値と一致</b> → 化石とみなし、いまの既定値を書き込む。</li>
 *   <li>一致しない → <b>利用者が自分で決めた値</b>。絶対に触らない。</li>
 * </ul>
 * 直した項目は 1 件ずつ INFO でログに出す。黙って書き換えない。
 */
public final class StaleDefaultRepair {

	/**
	 * この MOD の config スキーマ版。<b>過去の既定値の表に手を入れたら +1 する。</b>
	 *
	 * <p>1 = v1.0.0(初版)。初版なので「過去の既定値」はまだ存在せず、
	 * {@link AureumConfig#HISTORICAL_DEFAULTS} は空である。仕組みだけ先に置いてあるのは、
	 * 将来既定値を変えたときに、この表へ 1 行足して番号を +1 するだけで済むようにするため。
	 */
	public static final int CONFIG_VERSION = 1;

	/**
	 * 1 つのキーの経歴。
	 *
	 * @param sinceVersion     このエントリを導入した {@link #CONFIG_VERSION}。
	 * @param path             JSON 上の位置(入れ子はドット区切り)。
	 * @param currentDefault   いまの既定値(ログと自己検査のためだけに使う)。
	 * @param previousDefaults 過去に実際に出荷した既定値。
	 */
	public record Entry(int sinceVersion, String path, Object currentDefault, Object... previousDefaults) {
	}

	private StaleDefaultRepair() {
	}

	/** 保存されている config スキーマ版(キーが無ければ 0)。 */
	public static int storedVersion(final JsonObject root) {
		if (root != null && root.has("configVersion")) {
			try {
				JsonElement v = root.get("configVersion");
				if (v.isJsonPrimitive() && v.getAsJsonPrimitive().isNumber()) {
					return v.getAsInt();
				}
			} catch (RuntimeException ignored) {
				// 壊れた値は「知らない版」= 0 として扱い、修復対象にする。
			}
		}
		return 0;
	}

	/** 化石になった既定値を、いまの既定値で置きかえる。置きかえたキーの一覧を返す。 */
	public static List<String> repair(final JsonObject root, final Entry[] table, final int storedVersion,
			final Logger log, final String modId) {
		if (root == null) {
			return List.of();
		}
		List<String> repaired = new ArrayList<>();
		for (Entry entry : table) {
			if (storedVersion >= entry.sinceVersion()) {
				continue;
			}
			JsonObject parent = parentOf(root, entry.path());
			String leaf = leafOf(entry.path());
			if (parent == null || !parent.has(leaf)) {
				continue;
			}
			JsonElement stored = parent.get(leaf);
			Object fossil = firstMatch(stored, entry.previousDefaults());
			if (fossil == null) {
				continue;
			}
			JsonElement replacement = toJson(entry.currentDefault());
			if (replacement == null) {
				log.warn("{}: cannot express the current default for '{}' as JSON; leaving it alone",
					modId, entry.path());
				continue;
			}
			String before = stored.toString();
			parent.add(leaf, replacement);
			repaired.add(entry.path());
			log.info("{}: config key '{}' was {} — that is the default an older version shipped,"
					+ " not a setting you chose, so it has been updated to {}."
					+ " (古い既定値だったので新しい既定値にしました)",
				modId, entry.path(), before, replacement);
		}
		if (!repaired.isEmpty()) {
			log.info("{}: refreshed {} stale default(s) in config/{}.json: {}."
					+ " Values you tuned yourself were left alone."
					+ " (自分で変えた値はそのまま残してあります。設定ファイルを消す必要はありません)",
				modId, repaired.size(), modId, String.join(", ", repaired));
		}
		return List.copyOf(repaired);
	}

	private static JsonObject parentOf(final JsonObject root, final String path) {
		JsonObject node = root;
		int from = 0;
		int dot = path.indexOf('.');
		while (dot >= 0) {
			String segment = path.substring(from, dot);
			if (!node.has(segment) || !node.get(segment).isJsonObject()) {
				return null;
			}
			node = node.getAsJsonObject(segment);
			from = dot + 1;
			dot = path.indexOf('.', from);
		}
		return node;
	}

	private static String leafOf(final String path) {
		int dot = path.lastIndexOf('.');
		return dot < 0 ? path : path.substring(dot + 1);
	}

	private static Object firstMatch(final JsonElement stored, final Object[] candidates) {
		for (Object candidate : candidates) {
			if (matches(stored, candidate)) {
				return candidate;
			}
		}
		return null;
	}

	/**
	 * 保存値が過去の既定値と同じか。数値は相対 1e-6 の許容差で比べる
	 * (JSON は型を保たないので float の既定値がビット一致しないため — 原型の javadoc 参照)。
	 */
	private static boolean matches(final JsonElement stored, final Object expected) {
		if (stored == null || !stored.isJsonPrimitive()) {
			return false;
		}
		JsonPrimitive primitive = stored.getAsJsonPrimitive();
		try {
			if (expected instanceof Boolean b) {
				return primitive.isBoolean() && primitive.getAsBoolean() == b;
			}
			if (expected instanceof String s) {
				return primitive.isString() && primitive.getAsString().equals(s);
			}
			if (expected instanceof Number n) {
				if (!primitive.isNumber()) {
					return false;
				}
				double actual = primitive.getAsDouble();
				double want = n.doubleValue();
				return Math.abs(actual - want) <= 1.0E-6 * Math.max(1.0, Math.abs(want));
			}
		} catch (RuntimeException ignored) {
			return false;
		}
		return false;
	}

	/** 現在の既定値を JSON 値にする。表せない型なら {@code null}。 */
	private static JsonElement toJson(final Object value) {
		if (value instanceof Boolean b) {
			return new JsonPrimitive(b);
		}
		if (value instanceof Number n) {
			return new JsonPrimitive(n);
		}
		if (value instanceof String s) {
			return new JsonPrimitive(s);
		}
		return null;
	}

}
