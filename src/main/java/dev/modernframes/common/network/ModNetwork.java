package dev.modernframes.common.network;

import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

/**
 * Registers the mod payloads. Frame data is server -> client synchronised through the vanilla
 * block-entity update packet, so only three custom payloads are needed:
 * <ul>
 *   <li>{@link SetFramePayload} (client -> server) to save the screen edits.</li>
 *   <li>{@link ResizeFramePayload} (client -> server) for the right-click quick resize.</li>
 *   <li>{@link FrameResultPayload} (server -> client) to confirm or reject them.</li>
 * </ul>
 */
public final class ModNetwork {

    private ModNetwork() {
    }

    public static void register(IEventBus modBus) {
        modBus.addListener(ModNetwork::onRegisterPayloads);
    }

    private static void onRegisterPayloads(RegisterPayloadHandlersEvent event) {
        PayloadRegistrar registrar = event.registrar("1");
        registrar.playToServer(SetFramePayload.TYPE, SetFramePayload.STREAM_CODEC, SetFramePayload::handle);
        registrar.playToServer(ResizeFramePayload.TYPE, ResizeFramePayload.STREAM_CODEC, ResizeFramePayload::handle);
        registrar.playToClient(FrameResultPayload.TYPE, FrameResultPayload.STREAM_CODEC, FrameResultPayload::handle);
    }
}
