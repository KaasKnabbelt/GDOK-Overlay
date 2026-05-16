package nl.geocraft.overlay;

import com.google.common.io.ByteArrayDataInput;
import com.google.common.io.ByteArrayDataOutput;
import com.google.common.io.ByteStreams;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.function.Consumer;

/**
 * Queries the BungeeCord/Velocity proxy for the current backend sub-server name
 * via the standard "bungeecord:main" plugin channel. Used to detect whether the
 * player is on one of the allowed GeoCraft worlds (zuid/midden/noord).
 */
public final class BungeeCordChannel {

    private static final Logger LOGGER = LoggerFactory.getLogger("geocraft-overlay");
    private static boolean registered = false;
    private static Consumer<String> listener = s -> {};

    private BungeeCordChannel() {}

    public static void register(Consumer<String> onServerDetected) {
        listener = onServerDetected;
        if (registered) return;
        registered = true;

        PayloadTypeRegistry.clientboundPlay().register(BungeePayload.TYPE, BungeePayload.STREAM_CODEC);
        PayloadTypeRegistry.serverboundPlay().register(BungeePayload.TYPE, BungeePayload.STREAM_CODEC);

        ClientPlayNetworking.registerGlobalReceiver(BungeePayload.TYPE, (payload, context) -> {
            try {
                ByteArrayDataInput in = ByteStreams.newDataInput(payload.data);
                String subchannel = in.readUTF();
                if ("GetServer".equals(subchannel)) {
                    String server = in.readUTF();
                    LOGGER.info("[GeoCraft Overlay] Sub-server gedetecteerd: {}", server);
                    listener.accept(server);
                }
            } catch (Exception e) {
                LOGGER.warn("[GeoCraft Overlay] Fout bij BungeeCord plugin message: {}", e.getMessage());
            }
        });
    }

    public static void requestServerName() {
        if (!ClientPlayNetworking.canSend(BungeePayload.TYPE)) {
            LOGGER.debug("[GeoCraft Overlay] BungeeCord kanaal niet beschikbaar");
            return;
        }
        ByteArrayDataOutput out = ByteStreams.newDataOutput();
        out.writeUTF("GetServer");
        ClientPlayNetworking.send(new BungeePayload(out.toByteArray()));
    }

    public record BungeePayload(byte[] data) implements CustomPacketPayload {
        public static final Type<BungeePayload> TYPE =
                new Type<>(Identifier.fromNamespaceAndPath("bungeecord", "main"));
        public static final StreamCodec<RegistryFriendlyByteBuf, BungeePayload> STREAM_CODEC = StreamCodec.of(
                (buf, value) -> buf.writeBytes(value.data),
                buf -> {
                    byte[] bytes = new byte[buf.readableBytes()];
                    buf.readBytes(bytes);
                    return new BungeePayload(bytes);
                }
        );

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }
}
