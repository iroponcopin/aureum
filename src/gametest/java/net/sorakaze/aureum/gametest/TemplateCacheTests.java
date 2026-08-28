package net.sorakaze.aureum.gametest;

import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplateManager;
import net.sorakaze.aureum.template.TemplateCacheAccess;

import java.util.Optional;

/**
 * <b>structureTemplateCacheTtlSeconds の正当性を固定する。</b>
 *
 * <ul>
 *   <li>追い出し → 再アクセスで<b>透過的に読み直される</b>こと(内容も同じ)。</li>
 *   <li>structure block 由来({@code getOrCreate})の未保存テンプレートは
 *       <b>絶対に追い出されない</b>こと — 追い出せば作業内容が消えるため。</li>
 * </ul>
 *
 * <p>負の対照: mixin の evictStale からピン留め検査を一時的に外したビルドで
 * {@link #playerCreatedTemplatesArePinned} が赤くなることを確認済み
 * (実測記録は docs/VERIFICATION.md)。
 */
public class TemplateCacheTests {

	/** 追い出し → 透過的な読み直し。非空虚性: 実際に 1 件以上追い出されたことを要求。 */
	@GameTest(maxTicks = 400)
	public void evictionReloadsTransparently(final GameTestHelper helper) {
		StructureTemplateManager manager = helper.getLevel().getServer().getStructureManager();
		if (!(manager instanceof TemplateCacheAccess access)) {
			helper.fail("StructureTemplateManager does not implement TemplateCacheAccess —"
				+ " the TTL mixin did not apply; every other green here would prove nothing");
			return;
		}
		Identifier id = manager.listTemplates().findFirst().orElse(null);
		if (id == null) {
			helper.fail("no vanilla structure templates are listed — cannot exercise the cache");
			return;
		}
		Optional<StructureTemplate> first = manager.get(id);
		if (first.isEmpty()) {
			helper.fail("template " + id + " failed to load the first time — test subject missing");
			return;
		}
		int cachedBefore = access.aureum$cachedCount();
		int evicted = access.aureum$evictStale(0L);
		if (evicted < 1) {
			helper.fail("evictStale(0) evicted nothing although " + cachedBefore
				+ " entr(y/ies) were cached — the eviction is inert");
			return;
		}
		Optional<StructureTemplate> reloaded = manager.get(id);
		if (reloaded.isEmpty()) {
			helper.fail("template " + id + " did NOT reload after eviction — eviction is observable, bug");
			return;
		}
		if (!first.get().getSize().equals(reloaded.get().getSize())) {
			helper.fail("reloaded template " + id + " differs in size: " + first.get().getSize()
				+ " vs " + reloaded.get().getSize());
			return;
		}
		helper.succeed();
	}

	/** structure block(getOrCreate)由来はピン留めされ、TTL 0 でも生き残る。 */
	@GameTest(maxTicks = 400)
	public void playerCreatedTemplatesArePinned(final GameTestHelper helper) {
		StructureTemplateManager manager = helper.getLevel().getServer().getStructureManager();
		if (!(manager instanceof TemplateCacheAccess access)) {
			helper.fail("TTL mixin did not apply");
			return;
		}
		Identifier id = Identifier.fromNamespaceAndPath("aureum", "gametest_pin_probe");
		StructureTemplate created = manager.getOrCreate(id);
		if (created == null) {
			helper.fail("getOrCreate returned null for " + id);
			return;
		}
		if (access.aureum$pinnedCount() < 1) {
			helper.fail("getOrCreate did not pin " + id + " — the pin bookkeeping is inert");
			return;
		}
		access.aureum$evictStale(0L);
		Optional<StructureTemplate> after = manager.get(id);
		if (after.isEmpty() || after.get() != created) {
			helper.fail("the player-created template was evicted (or replaced) — this loses unsaved"
				+ " structure-block work; pinning is broken");
			return;
		}
		// 後片付け: 探针を消してピンも外れることを確認(remove 経路の帳簿)。
		manager.remove(id);
		if (manager.get(id).isPresent()) {
			helper.fail("remove(" + id + ") left the entry behind");
			return;
		}
		helper.succeed();
	}
}
