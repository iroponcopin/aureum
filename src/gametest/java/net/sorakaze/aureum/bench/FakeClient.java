package net.sorakaze.aureum.bench;

import com.mojang.authlib.GameProfile;
import io.netty.channel.embedded.EmbeddedChannel;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.BundlePacket;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.network.protocol.common.ClientboundKeepAlivePacket;
import net.minecraft.network.protocol.common.ServerboundKeepAlivePacket;
import net.minecraft.network.protocol.game.ClientboundChunkBatchFinishedPacket;
import net.minecraft.network.protocol.game.ServerboundChunkBatchReceivedPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;

import java.util.UUID;

/**
 * <b>本物の接続経路を通る</b>疑似クライアント 1 人ぶん。
 *
 * <p>{@code placeNewPlayer} を通しているので、プレイヤー一覧・チャンクチケット・
 * 湧き判定(プレイヤーから 128 ブロック)すべてに本物のプレイヤーとして数えられる。
 * この方式は mods-src(backrooms の IsolationBootProof)で実証済みのもの。
 *
 * <p>ベンチが長時間走るので、本物のクライアントが返す 2 種類の応答を返し続ける:
 * <ul>
 *   <li><b>KeepAlive</b> — 返さないと約 15 秒で蹴られてベンチ対象人口が消える。</li>
 *   <li><b>チャンク束の受領応答</b> — 返さないと {@code PlayerChunkSender} が
 *       1 束で止まり、チャンク送信系のコストが本物と違う経路になる。</li>
 * </ul>
 * どちらも<b>出荷されている本物のハンドラ</b>に渡す。独自の近道は作らない。
 */
final class FakeClient {

	final ServerPlayer player;
	private final EmbeddedChannel channel;

	private FakeClient(final ServerPlayer player, final EmbeddedChannel channel) {
		this.player = player;
		this.channel = channel;
	}

	static FakeClient join(final MinecraftServer server, final String name) {
		GameProfile profile = new GameProfile(UUID.randomUUID(), name);
		CommonListenerCookie cookie = CommonListenerCookie.createInitial(profile, false);
		ServerPlayer player = new ServerPlayer(server, server.overworld(),
			cookie.gameProfile(), cookie.clientInformation());
		Connection connection = new Connection(PacketFlow.SERVERBOUND);
		EmbeddedChannel channel = new EmbeddedChannel(connection);
		server.getPlayerList().placeNewPlayer(connection, player, cookie);
		return new FakeClient(player, channel);
	}

	/**
	 * 毎 tick サーバースレッドから呼ぶ。送信バッファを空にし、
	 * KeepAlive とチャンク束終端に応答する。
	 *
	 * <p>バッファは<b>必ず</b>空にする — 空にしないと EmbeddedChannel に
	 * パケットが無限に溜まり、ヒープ計測を汚す。
	 */
	void pump() {
		if (player.connection == null) {
			return;
		}
		Object message;
		while ((message = channel.readOutbound()) != null) {
			handleFlattened(message);
		}
	}

	private void handleFlattened(final Object message) {
		if (message instanceof BundlePacket<?> bundle) {
			for (Object sub : bundle.subPackets()) {
				handleFlattened(sub);
			}
			return;
		}
		if (message instanceof ClientboundKeepAlivePacket keepAlive) {
			player.connection.handleKeepAlive(new ServerboundKeepAlivePacket(keepAlive.getId()));
		} else if (message instanceof ClientboundChunkBatchFinishedPacket) {
			// 64.0f は PlayerChunkSender が受け付ける上限(Mth.clamp(x, 0.01F, 64.0F))。
			// 送信の速さを最大にするだけで、ゲームロジックには影響しない。
			player.connection.handleChunkBatchReceived(new ServerboundChunkBatchReceivedPacket(64.0F));
		}
	}
}
