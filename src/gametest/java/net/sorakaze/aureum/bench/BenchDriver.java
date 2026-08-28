package net.sorakaze.aureum.bench;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.levelgen.Heightmap;
import net.sorakaze.aureum.Aureum;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * 専用サーバー上で走る<b>再現可能なベンチ運転手</b>。
 *
 * <p>フェーズ構成(tick 駆動の状態機械):
 * <ol>
 *   <li><b>WARMUP</b> — 疑似プレイヤー 4 人を離れた 4 地点へ置き、周辺チャンクの
 *       生成/読み込みが落ち着くまで待つ。時刻は真夜中固定・天候固定・自動保存 off。</li>
 *   <li><b>HEAP</b> — System.gc() を数回かけてから使用ヒープを複数回標本し、
 *       jcmd GC.class_histogram をファイルに落とす。</li>
 *   <li><b>STEADY</b> — 各地点に固定数の永続 mob(ゾンビ/ウシ/村人)を召喚し、
 *       自然湧き無しで tick 時間を全数記録する。</li>
 *   <li><b>SPAWN</b> — 召喚した mob を消し、自然湧きを on にして tick 時間を全数記録する。</li>
 * </ol>
 *
 * <p><b>計測の非空虚性:</b> レポートには loadedChunks / entityCount / プレイヤー数を
 * フェーズごとに記録する。0 人・0 チャンクで測れてしまう「緑」を防ぐため、
 * RESULT 行はこれらの下限を自分で検査してから ok/fail を書く。
 */
public final class BenchDriver {

	private enum Phase { BOOT, WARMUP, IDLE, HEAP, STEADY_SETUP, STEADY, SPAWN_SETUP, SPAWN, DONE }

	/** 4 地点。128 ブロックの湧き圏が重ならないよう十分離す。 */
	private static final int[][] SITES = {{384, 384}, {-384, 384}, {384, -384}, {-384, -384}};

	private static final int WARMUP_TICKS = Integer.getInteger("aureum.bench.warmupTicks", 2400);
	private static final int PHASE_TICKS = Integer.getInteger("aureum.bench.phaseTicks", 4800);
	/**
	 * WARMUP と HEAP の間に挟む待機 tick(既定 0)。構造物テンプレート TTL の計測
	 * (worldgen 後、TTL+掃除間隔より長く待ってからヒープを測る)に使う。
	 */
	private static final int IDLE_TICKS = Integer.getInteger("aureum.bench.idleTicks", 0);
	/** フェーズ設定コマンドの影響を測定窓から外す猶予。 */
	private static final int SETTLE_TICKS = 200;

	private MinecraftServer server;
	private final List<FakeClient> clients = new ArrayList<>();
	private Phase phase = Phase.BOOT;
	private int phaseTick;
	private long tickStartNanos;

	private long[] steadySamples;
	private int steadyCount;
	private long[] spawnSamples;
	private int spawnCount;

	private final List<Long> heapSamples = new ArrayList<>();
	private int gcsDone;

	private final StringBuilder report = new StringBuilder();
	private boolean failed;
	private final List<String> failures = new ArrayList<>();

	public void onServerStarted(final MinecraftServer startedServer) {
		this.server = startedServer;
		phase = Phase.WARMUP;
		phaseTick = 0;
		Aureum.LOGGER.info("AUREUM-BENCH starting: warmup={} ticks, phase={} ticks", WARMUP_TICKS, PHASE_TICKS);
		command("gamerule doDaylightCycle false");
		command("gamerule doWeatherCycle false");
		command("gamerule doMobSpawning false");
		command("time set midnight");
		command("weather clear");
		command("save-off");
		command("difficulty normal");
		for (int i = 0; i < SITES.length; i++) {
			FakeClient client = FakeClient.join(server, "aureum-bench-" + i);
			clients.add(client);
			// まず上空へ(チャンクを読み込ませる)。地面高さはチャンクが載ってから引く。
			client.player.teleportTo(server.overworld(), SITES[i][0] + 0.5, 200.0, SITES[i][1] + 0.5,
				java.util.Set.of(), 0.0F, 0.0F, false);
			client.player.getAbilities().flying = true;
			client.player.onUpdateAbilities();
		}
	}

	public void onTickStart(final MinecraftServer s) {
		tickStartNanos = System.nanoTime();
	}

	public void onTickEnd(final MinecraftServer s) {
		if (server == null || phase == Phase.BOOT || phase == Phase.DONE) {
			return;
		}
		long duration = System.nanoTime() - tickStartNanos;
		for (FakeClient client : clients) {
			client.pump();
		}
		phaseTick++;
		switch (phase) {
			case WARMUP -> tickWarmup();
			case IDLE -> tickIdle();
			case HEAP -> tickHeap();
			case STEADY_SETUP -> tickSteadySetup();
			case STEADY -> {
				if (steadyCount < steadySamples.length) {
					steadySamples[steadyCount++] = duration;
				}
				if (phaseTick >= PHASE_TICKS) {
					enterSpawnSetup();
				}
			}
			case SPAWN_SETUP -> tickSpawnSetup();
			case SPAWN -> {
				if (spawnCount < spawnSamples.length) {
					spawnSamples[spawnCount++] = duration;
				}
				if (phaseTick >= PHASE_TICKS) {
					finish();
				}
			}
			default -> { }
		}
	}

	private void tickWarmup() {
		if (phaseTick == WARMUP_TICKS / 2) {
			// チャンクが載ったので、各プレイヤーを地表へ降ろす(湧き圏の縦 128 制約のため)。
			for (FakeClient client : clients) {
				ServerPlayer player = client.player;
				ServerLevel overworld = server.overworld();
				int x = player.blockPosition().getX();
				int z = player.blockPosition().getZ();
				int ground = overworld.getHeight(Heightmap.Types.MOTION_BLOCKING, x, z);
				player.teleportTo(overworld, x + 0.5, ground + 1.0, z + 0.5,
					java.util.Set.of(), 0.0F, 0.0F, false);
			}
		}
		if (phaseTick >= WARMUP_TICKS) {
			if (IDLE_TICKS > 0) {
				phase = Phase.IDLE;
				phaseTick = 0;
				Aureum.LOGGER.info("AUREUM-BENCH phase IDLE begins ({} ticks)", IDLE_TICKS);
			} else {
				enterHeap();
			}
		}
	}

	private void tickIdle() {
		if (phaseTick >= IDLE_TICKS) {
			enterHeap();
		}
	}

	private void enterHeap() {
		phase = Phase.HEAP;
		phaseTick = 0;
		gcsDone = 0;
		heapSamples.clear();
		Aureum.LOGGER.info("AUREUM-BENCH phase HEAP begins (loadedChunks={}, players={})",
			server.overworld().getChunkSource().getLoadedChunksCount(), server.getPlayerList().getPlayerCount());
	}

	/**
	 * <b>GC → 少し待つ → 標本、を 7 回</b>繰り返して中央値を取る。
	 * (最初の実装は「GC を先に全部かけてから標本を並べる」形だったが、
	 * サーバーは動き続けているので標本列が単調に増えるだけだった —
	 * prep 走行の実測で 265→304 MB のドリフトを確認。対にすることで
	 * 各標本が「GC 直後の生存集合」を測るようになる。)
	 */
	private void tickHeap() {
		int cycle = 15;
		if (phaseTick % cycle == 1 && gcsDone < 7) {
			System.gc();
			gcsDone++;
		} else if (phaseTick % cycle == 6 && gcsDone > 0 && heapSamples.size() < gcsDone) {
			Runtime runtime = Runtime.getRuntime();
			heapSamples.add(runtime.totalMemory() - runtime.freeMemory());
			if (heapSamples.size() >= 7) {
				dumpHistogram();
				enterSteadySetup();
			}
		}
	}

	private void dumpHistogram() {
		Path out = Path.of("aureum-bench", "class-histogram.txt");
		try {
			Files.createDirectories(out.getParent());
			Path jcmd = Path.of(System.getProperty("java.home"), "bin", "jcmd");
			long pid = ProcessHandle.current().pid();
			Process process = new ProcessBuilder(jcmd.toString(), Long.toString(pid), "GC.class_histogram")
				.redirectOutput(out.toFile()).redirectErrorStream(true).start();
			if (!process.waitFor(30, java.util.concurrent.TimeUnit.SECONDS)) {
				process.destroyForcibly();
				fail("class_histogram: jcmd did not finish in 30 s");
			}
		} catch (IOException e) {
			fail("class_histogram failed: " + e);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			fail("class_histogram interrupted");
		}
	}

	private void enterSteadySetup() {
		phase = Phase.STEADY_SETUP;
		phaseTick = 0;
		ServerLevel overworld = server.overworld();
		for (int[] site : SITES) {
			summonRing(overworld, site[0], site[1], "minecraft:zombie", 25);
			summonRing(overworld, site[0], site[1], "minecraft:cow", 15);
			summonRing(overworld, site[0], site[1], "minecraft:villager", 10);
		}
		jfr("JFR.start", "name=aureum", "settings=profile");
		Aureum.LOGGER.info("AUREUM-BENCH phase STEADY set up: 200 persistent mobs summoned");
	}

	private void summonRing(final ServerLevel level, final int centerX, final int centerZ,
			final String type, final int count) {
		for (int i = 0; i < count; i++) {
			double angle = 2.0 * Math.PI * i / count;
			int x = centerX + (int) Math.round(Math.cos(angle) * 20.0);
			int z = centerZ + (int) Math.round(Math.sin(angle) * 20.0);
			int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING, x, z);
			command(String.format(Locale.ROOT, "summon %s %d %d %d {PersistenceRequired:1b}",
				type, x, y, z));
		}
	}

	private void tickSteadySetup() {
		if (phaseTick >= SETTLE_TICKS) {
			phase = Phase.STEADY;
			phaseTick = 0;
			steadySamples = new long[PHASE_TICKS];
			steadyCount = 0;
			Aureum.LOGGER.info("AUREUM-BENCH phase STEADY measuring (entities={}, loadedChunks={})",
				countEntities(), server.overworld().getChunkSource().getLoadedChunksCount());
		}
	}

	private void enterSpawnSetup() {
		phase = Phase.SPAWN_SETUP;
		phaseTick = 0;
		command("kill @e[type=!minecraft:player]");
		command("gamerule doMobSpawning true");
		command("time set midnight");
		Aureum.LOGGER.info("AUREUM-BENCH phase SPAWN set up: summons killed, natural spawning on");
	}

	private void tickSpawnSetup() {
		if (phaseTick >= SETTLE_TICKS) {
			phase = Phase.SPAWN;
			phaseTick = 0;
			spawnSamples = new long[PHASE_TICKS];
			spawnCount = 0;
			Aureum.LOGGER.info("AUREUM-BENCH phase SPAWN measuring");
		}
	}

	private void finish() {
		phase = Phase.DONE;
		jfr("JFR.dump", "name=aureum", "filename=aureum-bench/steady-spawn.jfr");
		jfr("JFR.stop", "name=aureum");
		writeReport();
		int players = server.getPlayerList().getPlayerCount();
		int chunks = server.overworld().getChunkSource().getLoadedChunksCount();
		// 非空虚性の門: プレイヤーが残っていて、チャンクが載っていて、標本が満数であること。
		if (players < SITES.length) {
			fail("only " + players + " of " + SITES.length + " bench players survived");
		}
		if (chunks < 400) {
			fail("only " + chunks + " chunks loaded — the workload never existed");
		}
		if (steadyCount < PHASE_TICKS || spawnCount < PHASE_TICKS) {
			fail("sample counts short: steady=" + steadyCount + " spawn=" + spawnCount);
		}
		if (failed) {
			Aureum.LOGGER.info("AUREUM-BENCH RESULT fail: {}", String.join("; ", failures));
		} else {
			Aureum.LOGGER.info("AUREUM-BENCH RESULT ok steadyMedianMs={} spawnMedianMs={} heapUsedMedianMB={}",
				format(median(Arrays.copyOf(steadySamples, steadyCount)) / 1.0e6),
				format(median(Arrays.copyOf(spawnSamples, spawnCount)) / 1.0e6),
				format(medianLong(heapSamples) / (1024.0 * 1024.0)));
		}
		server.halt(false);
	}

	private void writeReport() {
		report.append("{\n");
		report.append("  \"phaseTicks\": ").append(PHASE_TICKS).append(",\n");
		report.append("  \"warmupTicks\": ").append(WARMUP_TICKS).append(",\n");
		report.append("  \"players\": ").append(server.getPlayerList().getPlayerCount()).append(",\n");
		report.append("  \"loadedChunksEnd\": ").append(server.overworld().getChunkSource().getLoadedChunksCount()).append(",\n");
		report.append("  \"entitiesEnd\": ").append(countEntities()).append(",\n");
		report.append("  \"entitiesByType\": \"").append(entityBreakdown()).append("\",\n");
		if (server.getStructureManager() instanceof net.sorakaze.aureum.template.TemplateCacheAccess access) {
			report.append("  \"templateCacheCount\": ").append(access.aureum$cachedCount()).append(",\n");
		}
		report.append("  \"heapUsedBytes\": ").append(heapSamples).append(",\n");
		appendStats("steady", steadySamples, steadyCount);
		report.append(",\n");
		appendStats("spawn", spawnSamples, spawnCount);
		report.append("\n}\n");
		try {
			Path dir = Path.of("aureum-bench");
			Files.createDirectories(dir);
			Files.writeString(dir.resolve("report.json"), report.toString(), StandardCharsets.UTF_8);
			writeSamplesCsv(dir.resolve("steady-ticks-ns.csv"), steadySamples, steadyCount);
			writeSamplesCsv(dir.resolve("spawn-ticks-ns.csv"), spawnSamples, spawnCount);
		} catch (IOException e) {
			fail("report write failed: " + e);
		}
	}

	private void writeSamplesCsv(final Path path, final long[] samples, final int count) throws IOException {
		StringBuilder csv = new StringBuilder(count * 8);
		for (int i = 0; i < count; i++) {
			csv.append(samples[i]).append('\n');
		}
		Files.writeString(path, csv.toString(), StandardCharsets.UTF_8);
	}

	private void appendStats(final String name, final long[] samples, final int count) {
		long[] data = Arrays.copyOf(samples, count);
		Arrays.sort(data);
		report.append("  \"").append(name).append("\": {");
		if (count == 0) {
			report.append("\"count\": 0}");
			return;
		}
		double mean = Arrays.stream(data).average().orElse(0);
		report.append("\"count\": ").append(count)
			.append(", \"meanMs\": ").append(format(mean / 1.0e6))
			.append(", \"medianMs\": ").append(format(percentileSorted(data, 50) / 1.0e6))
			.append(", \"p95Ms\": ").append(format(percentileSorted(data, 95) / 1.0e6))
			.append(", \"p99Ms\": ").append(format(percentileSorted(data, 99) / 1.0e6))
			.append(", \"maxMs\": ").append(format(data[data.length - 1] / 1.0e6))
			.append("}");
	}

	private static long median(final long[] data) {
		long[] copy = data.clone();
		Arrays.sort(copy);
		return percentileSorted(copy, 50);
	}

	private static long medianLong(final List<Long> data) {
		return median(data.stream().mapToLong(Long::longValue).toArray());
	}

	private static long percentileSorted(final long[] sorted, final int percentile) {
		if (sorted.length == 0) {
			return 0;
		}
		int index = (int) Math.ceil(percentile / 100.0 * sorted.length) - 1;
		return sorted[Math.clamp(index, 0, sorted.length - 1)];
	}

	private static String format(final double value) {
		return String.format(Locale.ROOT, "%.3f", value);
	}

	private int countEntities() {
		int count = 0;
		for (@SuppressWarnings("unused") var entity : server.overworld().getAllEntities()) {
			count++;
		}
		return count;
	}

	/** 型別の内訳(上位 12)。数の食い違いを追えるように残す。 */
	private String entityBreakdown() {
		java.util.Map<String, Integer> byType = new java.util.TreeMap<>();
		for (var entity : server.overworld().getAllEntities()) {
			byType.merge(entity.getType().toShortString(), 1, Integer::sum);
		}
		return byType.entrySet().stream()
			.sorted(java.util.Map.Entry.<String, Integer>comparingByValue().reversed())
			.limit(12)
			.map(e -> e.getKey() + ":" + e.getValue())
			.reduce((a, b) -> a + " " + b).orElse("none");
	}

	private void command(final String cmd) {
		server.getCommands().performPrefixedCommand(server.createCommandSourceStack(), cmd);
	}

	private void jfr(final String... args) {
		try {
			Files.createDirectories(Path.of("aureum-bench"));
			Path jcmd = Path.of(System.getProperty("java.home"), "bin", "jcmd");
			List<String> commandLine = new ArrayList<>();
			commandLine.add(jcmd.toString());
			commandLine.add(Long.toString(ProcessHandle.current().pid()));
			commandLine.addAll(Arrays.asList(args));
			Process process = new ProcessBuilder(commandLine)
				.redirectOutput(new java.io.File("aureum-bench/jfr-" + args[0] + ".log"))
				.redirectErrorStream(true).start();
			process.waitFor(30, java.util.concurrent.TimeUnit.SECONDS);
		} catch (IOException e) {
			fail("jfr " + args[0] + " failed: " + e);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}
	}

	private void fail(final String reason) {
		failed = true;
		failures.add(reason);
		Aureum.LOGGER.warn("AUREUM-BENCH problem: {}", reason);
	}
}
