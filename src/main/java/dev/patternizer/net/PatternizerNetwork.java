package dev.patternizer.net;

import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.simple.SimpleChannel;

import dev.patternizer.AIPatternizer;

public final class PatternizerNetwork {

    private static final String PROTOCOL_VERSION = "1";

    public static final SimpleChannel CHANNEL = NetworkRegistry.newSimpleChannel(
            new ResourceLocation(AIPatternizer.MOD_ID, "main"),
            () -> PROTOCOL_VERSION,
            PROTOCOL_VERSION::equals,
            PROTOCOL_VERSION::equals);

    private PatternizerNetwork() {
    }

    public static void register() {
        int id = 0;
        CHANNEL.registerMessage(id++, PatternSpecRequestPacket.class,
                PatternSpecRequestPacket::encode, PatternSpecRequestPacket::decode, PatternSpecRequestPacket::handle);
        CHANNEL.registerMessage(id++, EncodeResultPacket.class,
                EncodeResultPacket::encode, EncodeResultPacket::decode, EncodeResultPacket::handle);
        CHANNEL.registerMessage(id++, LinePlanRequestPacket.class,
                LinePlanRequestPacket::encode, LinePlanRequestPacket::decode, LinePlanRequestPacket::handle);
        CHANNEL.registerMessage(id++, LinePlanResultPacket.class,
                LinePlanResultPacket::encode, LinePlanResultPacket::decode, LinePlanResultPacket::handle);
        CHANNEL.registerMessage(id++, LinePlanConfirmPacket.class,
                LinePlanConfirmPacket::encode, LinePlanConfirmPacket::decode, LinePlanConfirmPacket::handle);
        CHANNEL.registerMessage(id++, LinePlaceResultPacket.class,
                LinePlaceResultPacket::encode, LinePlaceResultPacket::decode, LinePlaceResultPacket::handle);
    }
}
