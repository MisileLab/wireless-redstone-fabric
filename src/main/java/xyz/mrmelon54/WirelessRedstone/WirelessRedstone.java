package xyz.mrmelon54.WirelessRedstone;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.entity.event.v1.ServerEntityWorldChangeEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerChunkEvents;
import net.fabricmc.fabric.api.item.v1.FabricItemSettings;
import net.fabricmc.fabric.api.itemgroup.v1.ItemGroupEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.fabricmc.fabric.api.object.builder.v1.block.FabricBlockSettings;
import net.fabricmc.fabric.api.object.builder.v1.block.entity.FabricBlockEntityTypeBuilder;
import net.minecraft.block.Block;
import net.minecraft.block.entity.BlockEntityType;
import net.minecraft.item.BlockItem;
import net.minecraft.item.Item;
import net.minecraft.item.ItemGroups;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.minecraft.resource.featuretoggle.FeatureSet;
import net.minecraft.screen.ScreenHandlerContext;
import net.minecraft.screen.ScreenHandlerType;
import net.minecraft.state.property.Properties;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.world.World;
import net.minecraft.world.tick.OrderedTick;
import net.minecraft.world.tick.TickScheduler;
import xyz.mrmelon54.WirelessRedstone.block.WirelessReceiverBlock;
import xyz.mrmelon54.WirelessRedstone.block.WirelessTransmitterBlock;
import xyz.mrmelon54.WirelessRedstone.block.entity.WirelessReceiverBlockEntity;
import xyz.mrmelon54.WirelessRedstone.block.entity.WirelessTransmitterBlockEntity;
import xyz.mrmelon54.WirelessRedstone.gui.WirelessFrequencyGuiDescription;
import xyz.mrmelon54.WirelessRedstone.item.WirelessHandheldItem;
import xyz.mrmelon54.WirelessRedstone.util.HandheldItemUtils;
import xyz.mrmelon54.WirelessRedstone.util.NetworkingConstants;

public class WirelessRedstone implements ModInitializer {
    public static final Block WIRELESS_TRANSMITTER = new WirelessTransmitterBlock(FabricBlockSettings.create().collidable(true).strength(0).luminance(value -> value.get(Properties.LIT) ? 7 : 0));
    public static final BlockItem WIRELESS_TRANSMITTER_ITEM = new BlockItem(WIRELESS_TRANSMITTER, new FabricItemSettings().maxCount(64));
    public static final Block WIRELESS_RECEIVER = new WirelessReceiverBlock(FabricBlockSettings.create().collidable(true).strength(0).luminance(value -> value.get(Properties.LIT) ? 7 : 0));
    public static final BlockItem WIRELESS_RECEIVER_ITEM = new BlockItem(WIRELESS_RECEIVER, new FabricItemSettings().maxCount(64));
    public static final Item WIRELESS_HANDHELD = new WirelessHandheldItem(new FabricItemSettings().maxCount(1));
    public static BlockEntityType<WirelessTransmitterBlockEntity> WIRELESS_TRANSMITTER_BLOCK_ENTITY;
    public static BlockEntityType<WirelessReceiverBlockEntity> WIRELESS_RECEIVER_BLOCK_ENTITY;
    public static ScreenHandlerType<WirelessFrequencyGuiDescription> WIRELESS_FREQUENCY_SCREEN;
    public static final BlockPos ImpossibleBlockPos = BlockPos.ORIGIN.offset(Direction.DOWN, 30000);

    public void onInitialize() {
        ServerPlayConnectionEvents.INIT.register((handler, server) -> HandheldItemUtils.addHandheldFromPlayer(handler.player, handler.player.getWorld()));
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> HandheldItemUtils.removeHandheldFromPlayer(handler.player, handler.player.getWorld()));
        ServerChunkEvents.CHUNK_LOAD.register(HandheldItemUtils::addHandheldFromChunk);
        ServerChunkEvents.CHUNK_UNLOAD.register(HandheldItemUtils::removeHandheldFromChunk);

        ItemGroupEvents.modifyEntriesEvent(ItemGroups.REDSTONE).register(entries -> entries.add(WIRELESS_TRANSMITTER));
        ItemGroupEvents.modifyEntriesEvent(ItemGroups.REDSTONE).register(entries -> entries.add(WIRELESS_RECEIVER));
        ItemGroupEvents.modifyEntriesEvent(ItemGroups.REDSTONE).register(entries -> entries.add(WIRELESS_HANDHELD));

        // remove and replace all handhelds when changing world
        ServerEntityWorldChangeEvents.AFTER_PLAYER_CHANGE_WORLD.register((player, origin, destination) -> {
            HandheldItemUtils.removeHandheldFromPlayer(player, origin);
            HandheldItemUtils.addHandheldFromPlayer(player, destination);
        });

        WIRELESS_FREQUENCY_SCREEN = Registry.register(Registries.SCREEN_HANDLER, new Identifier("wireless-redstone", "frequency-screen"), new ScreenHandlerType<>(((syncId, inventory) -> new WirelessFrequencyGuiDescription(syncId, inventory, ScreenHandlerContext.EMPTY)), FeatureSet.empty()));

        Registry.register(Registries.BLOCK, new Identifier("wireless-redstone", "transmitter"), WIRELESS_TRANSMITTER);
        Registry.register(Registries.ITEM, new Identifier("wireless-redstone", "transmitter"), WIRELESS_TRANSMITTER_ITEM);
        WIRELESS_TRANSMITTER_BLOCK_ENTITY = Registry.register(Registries.BLOCK_ENTITY_TYPE, "wireless-redstone:transmitter", FabricBlockEntityTypeBuilder.create(WirelessTransmitterBlockEntity::new, WIRELESS_TRANSMITTER).build());

        Registry.register(Registries.BLOCK, new Identifier("wireless-redstone", "receiver"), WIRELESS_RECEIVER);
        Registry.register(Registries.ITEM, new Identifier("wireless-redstone", "receiver"), WIRELESS_RECEIVER_ITEM);
        WIRELESS_RECEIVER_BLOCK_ENTITY = Registry.register(Registries.BLOCK_ENTITY_TYPE, "wireless-redstone:receiver", FabricBlockEntityTypeBuilder.create(WirelessReceiverBlockEntity::new, WIRELESS_RECEIVER).build());

        Registry.register(Registries.ITEM, new Identifier("wireless-redstone", "handheld"), WIRELESS_HANDHELD);

        ServerPlayNetworking.registerGlobalReceiver(NetworkingConstants.WIRELESS_FREQUENCY_CHANGE_PACKET_ID, ((server, player, handler, buf, responseSender) -> {
            if (player.currentScreenHandler instanceof WirelessFrequencyGuiDescription wirelessFrequencyScreenHandler) {
                if (wirelessFrequencyScreenHandler.getPropertyDelegate() != null)
                    wirelessFrequencyScreenHandler.getPropertyDelegate().set(0, buf.readInt());
            }
        }));
    }

    public static boolean hasLitTransmitterOnFrequency(World world, long freq) {
        return MyComponents.FrequencyStorage.get(world).getTransmitting().stream().anyMatch(transmittingFrequencyEntry -> transmittingFrequencyEntry.freq() == freq)
                || MyComponents.FrequencyStorage.get(world).getHandheld().stream().anyMatch(transmittingHandheldEntry -> transmittingHandheldEntry.freq() == freq);
    }

    public static void sendTickScheduleToReceivers(World world) {
        TickScheduler<Block> blockTickScheduler = world.getBlockTickScheduler();
        for (BlockPos p : MyComponents.FrequencyStorage.get(world).getReceivers())
            blockTickScheduler.scheduleTick(OrderedTick.create(WirelessRedstone.WIRELESS_RECEIVER, p));
    }
}
