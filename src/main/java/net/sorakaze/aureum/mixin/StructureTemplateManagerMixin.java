package net.sorakaze.aureum.mixin;

import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplateManager;
import net.sorakaze.aureum.Aureum;
import net.sorakaze.aureum.template.TemplateCacheAccess;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * <b>structureTemplateCacheTtlSeconds</b>: 構造物テンプレートキャッシュの追い出し。
 *
 * <h2>何を・なぜ</h2>
 * バニラの {@code structureRepository} は <b>一度読んだ .nbt テンプレートを
 * データパック再読込まで永遠に保持する</b>。2,209 チャンク生成後の実測
 * (docs/bench の class histogram)で StructureBlockInfo が 302,650 個 /
 * 7.26 MB、付随する BlockPos・NBT を含めるとさらに大きい。ワールド生成が
 * 進むほど増える一方で、生成が済めばほぼ二度と読まれない。
 * そこで<b>最終アクセスから TTL(既定 300 秒)を超えた項目をキャッシュから外す</b>。
 * 外された項目は次に必要になった瞬間、バニラ自身の読み込み経路
 * ({@code computeIfAbsent} → {@code tryLoad})で透過的に読み直される —
 * 初回アクセスと全く同じ経路なので、観測可能な違いはディスク再読の時間だけ。
 *
 * <h2>プレイヤー作成テンプレートは絶対に捨てない</h2>
 * structure block の保存モードは {@code getOrCreate} でキャッシュに<b>未保存の</b>
 * テンプレートを作る。これを捨てると保存前の作業が消える。だから
 * {@code getOrCreate} を通った id は<b>ピン留め</b>して追い出し対象から外す
 * (GameTest {@code TemplateCacheTests} が固定)。
 *
 * <h2>並行性</h2>
 * {@code structureRepository} は ConcurrentHashMap で、ワールド生成スレッドが
 * 並行に {@code get} する。追い出しはサーバースレッドから同じ公開 API 相当の
 * {@code remove} 操作(CHM の remove)で行うので、競合しても「消した直後に
 * 読み直される」だけ(一時的な二重読み込み)で壊れない。アクセス記録も
 * ConcurrentHashMap。<b>タイムスタンプが無い項目は捨てずにまず刻印する</b>
 * (最初の掃除で刻印 → 次の掃除で TTL 判定)ので、記録の取りこぼしが
 * 即追い出しになることはない。
 *
 * <h2>バニラが変わったら何が壊れるか</h2>
 * getOrCreate 以外にキャッシュへ<b>書く</b>経路が増えたら、その経路の項目が
 * ピン留めされず追い出され得る。バージョン更新時は structureRepository への
 * put 経路を目視で数えること(26.2 では computeIfAbsent と getOrCreate の 2 つ)。
 *
 * <p>config {@code structureTemplateCacheTtlSeconds}(既定 300、0 = off)。
 * off のときは {@code AureumMixinPlugin} がこの mixin 自体を適用しない。
 */
@Mixin(StructureTemplateManager.class)
public abstract class StructureTemplateManagerMixin implements TemplateCacheAccess {

	@Shadow @Final private Map<Identifier, Optional<StructureTemplate>> structureRepository;

	@Unique private ConcurrentHashMap<Identifier, Long> aureum$lastAccess;
	@Unique private Set<Identifier> aureum$pinned;

	@Inject(method = "<init>", at = @At("RETURN"))
	private void aureum$init(final CallbackInfo ci) {
		this.aureum$lastAccess = new ConcurrentHashMap<>();
		this.aureum$pinned = ConcurrentHashMap.newKeySet();
	}

	@Inject(method = "get", at = @At("RETURN"))
	private void aureum$recordAccess(final Identifier id,
			final CallbackInfoReturnable<Optional<StructureTemplate>> cir) {
		this.aureum$lastAccess.put(id, System.nanoTime());
	}

	/** structure block の保存経路。<b>get より先に</b>ピン留めして競合窓を無くす。 */
	@Inject(method = "getOrCreate", at = @At("HEAD"))
	private void aureum$pinCreated(final Identifier id, final CallbackInfoReturnable<StructureTemplate> cir) {
		this.aureum$pinned.add(id);
	}

	@Inject(method = "remove", at = @At("RETURN"))
	private void aureum$forgetRemoved(final Identifier id, final CallbackInfo ci) {
		this.aureum$lastAccess.remove(id);
		this.aureum$pinned.remove(id);
	}

	@Inject(method = "onResourceManagerReload", at = @At("RETURN"))
	private void aureum$forgetAllOnReload(final ResourceManager resourceManager, final CallbackInfo ci) {
		this.aureum$lastAccess.clear();
		this.aureum$pinned.clear();
	}

	@Override
	public int aureum$evictStale(final long maxAgeMillis) {
		long now = System.nanoTime();
		long maxAgeNanos = maxAgeMillis * 1_000_000L;
		int evicted = 0;
		for (Identifier id : this.structureRepository.keySet()) {
			if (this.aureum$pinned.contains(id)) {
				continue;
			}
			Long last = this.aureum$lastAccess.get(id);
			if (last == null) {
				// 記録が無い項目は捨てずにまず刻印(取りこぼし ≠ 即追い出し)。
				this.aureum$lastAccess.put(id, now);
				continue;
			}
			if (now - last > maxAgeNanos) {
				this.structureRepository.remove(id);
				this.aureum$lastAccess.remove(id);
				evicted++;
			}
		}
		if (evicted > 0) {
			Aureum.LOGGER.debug("aureum: evicted {} idle structure template(s) from the cache", evicted);
		}
		return evicted;
	}

	@Override
	public int aureum$cachedCount() {
		return this.structureRepository.size();
	}

	@Override
	public int aureum$pinnedCount() {
		return this.aureum$pinned.size();
	}
}
