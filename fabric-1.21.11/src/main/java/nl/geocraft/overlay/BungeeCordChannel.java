package nl.geocraft.overlay;

import com.google.common.io.ByteArrayDataInput;
import com.google.common.io.ByteArrayDataOutput;
import com.google.common.io.ByteStreams;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;
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

        PayloadTypeRegistry.playS2C().register(BungeePayload.ID, BungeePayload.CODEC);
        PayloadTypeRegistry.playC2S().register(BungeePayload.ID, BungeePayload.CODEC);

        ClientPlayNetworking.registerGlobalReceiver(BungeePayload.ID, (payload, context) -> {
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
        if (!ClientPlayNetworking.canSend(BungeePayload.ID)) {
            LOGGER.debug("[GeoCraft Overlay] BungeeCord kanaal niet beschikbaar");
            return;
        }
        ByteArrayDataOutput out = ByteStreams.newDataOutput();
        out.writeUTF("GetServer");
        ClientPlayNetworking.send(new BungeePayload(out.toByteArray()));
    }

    public record BungeePayload(byte[] data) implements CustomPayload {
        public static final Id<BungeePayload> ID = new Id<>(Identifier.of("bungeecord", "main"));
        public static final PacketCodec<RegistryByteBuf, BungeePayload> CODEC = PacketCodec.of(
                (value, buf) -> buf.writeBytes(value.data),
                buf -> {
                    byte[] bytes = new byte[buf.readableBytes()];
                    buf.readBytes(bytes);
                    return new BungeePayload(bytes);
                }
        );

        @Override
        public Id<? extends CustomPayload> getId() {
            return ID;
        }
    }
}
