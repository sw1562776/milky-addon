package com.milky.hunt.modules;

import com.milky.hunt.Addon;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.StringSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.orbit.EventHandler;

import net.minecraft.block.Block;
import net.minecraft.item.ItemStack;
import net.minecraft.network.packet.c2s.play.CommandExecutionC2SPacket;
import net.minecraft.util.math.BlockPos;
import net.minecraft.registry.Registries;
import net.minecraft.inventory.Inventory;
import net.minecraft.block.entity.BlockEntity;
//import net.minecraft.entity.player.PlayerEntity;
//import net.minecraft.block.entity.*;
//import net.minecraft.client.world.ClientWorld;
//import net.minecraft.client.world.ClientChunkManager;
//import net.minecraft.world.chunk.Chunk;
//import net.minecraft.world.chunk.WorldChunk;

import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import java.util.stream.Collectors;

public class QuickCommand extends Module {
    private final Setting<String> command = settings.getDefaultGroup().add(new StringSetting.Builder()
        .name("command")
        .description("Send a quick message/command with rich placeholders.")
        .defaultValue("/w Wandelion {CoordX}, {CoordY}, {CoordZ}")
        .build()
    );

    private boolean hasSent;

    public QuickCommand() {
        super(Addon.CATEGORY, "QuickCommand", "Send a message/command with rich placeholders like {CoordX}, {Health}, etc.");
    }

    @Override
    public void onActivate() {
        hasSent = false;
    }

    @EventHandler
    private void onTick(TickEvent.Post event) {
        if (mc.player == null || mc.world == null || mc.getNetworkHandler() == null) return;
        if (hasSent) return;

        String parsed = parseCommand(command.get());

        if (parsed.startsWith("/")) {
            mc.getNetworkHandler().sendPacket(new CommandExecutionC2SPacket(parsed.substring(1)));
        } else {
            mc.getNetworkHandler().sendChatMessage(parsed);
        }

        hasSent = true;
        toggle();
    }

    private String parseCommand(String input) {
        double x = mc.player.getX(), y = mc.player.getY(), z = mc.player.getZ();

        String time = LocalTime.now().toString().split("\\.")[0];
        String timestamp = LocalDateTime.now().toString().replace("T", " ").split("\\.")[0];

        String dimension = mc.world.getRegistryKey().getValue().toString();
        String playerName = mc.player.getName().getString();
        String uuid = mc.player.getUuidAsString();

        float health = mc.player.getHealth();
        float maxHealth = mc.player.getMaxHealth();
        int hunger = mc.player.getHungerManager().getFoodLevel();
        int xp = mc.player.experienceLevel;
        String facing = mc.player.getHorizontalFacing().toString();

        String serverIp = mc.getCurrentServerEntry() != null ? mc.getCurrentServerEntry().address : "localhost";
        String serverName = mc.getCurrentServerEntry() != null ? mc.getCurrentServerEntry().name : "singleplayer";

        ItemStack mainHand = mc.player.getMainHandStack();
        ItemStack offHand = mc.player.getOffHandStack();

        ItemStack helmet = mc.player.getInventory().armor.get(3);
        ItemStack chest = mc.player.getInventory().armor.get(2);
        ItemStack legs = mc.player.getInventory().armor.get(1);
        ItemStack boots = mc.player.getInventory().armor.get(0);

        BlockPos posUnder = mc.player.getBlockPos().down();
        Block blockUnder = mc.world.getBlockState(posUnder).getBlock();
        String biome = mc.world.getBiome(posUnder).getKey().get().getValue().toString();
        int light = mc.world.getLightLevel(posUnder);

        boolean sneaking = mc.player.isSneaking();
        boolean sprinting = mc.player.isSprinting();
        boolean onGround = mc.player.isOnGround();
        int air = mc.player.getAir();
        int fireTicks = mc.player.getFireTicks();

        List<String> nearbyNames = mc.world.getPlayers().stream()
            .filter(p -> !p.getUuid().equals(mc.player.getUuid()))
            .map(p -> p.getGameProfile().getName())
            .collect(Collectors.toList());

        String nearbyPlayers = String.join(", ", nearbyNames);
        
        int containerCount = 0;

for (Chunk chunk : mc.world.getChunkManager().getLoadedChunks()) {
    if (chunk instanceof WorldChunk worldChunk) {
        for (BlockEntity be : worldChunk.blockEntities.values()) {
            if (be instanceof Inventory) {
                containerCount++;
            }
        }
    }
}


        String result = input
            .replace("{CoordX}", String.format("%.1f", x))
            .replace("{CoordY}", String.format("%.1f", y))
            .replace("{CoordZ}", String.format("%.1f", z))
            .replace("{Dimension}", dimension)
            .replace("{Player}", playerName)
            .replace("{UUID}", uuid)
            .replace("{IP}", serverIp)
            .replace("{ServerName}", serverName)
            .replace("{Time}", time)
            .replace("{Timestamp}", timestamp)
            .replace("{Health}", String.format("%.1f", health))
            .replace("{MaxHealth}", String.format("%.1f", maxHealth))
            .replace("{Hunger}", String.valueOf(hunger))
            .replace("{XP}", String.valueOf(xp))
            .replace("{Facing}", facing)
            .replace("{Sneaking}", String.valueOf(sneaking))
            .replace("{Sprinting}", String.valueOf(sprinting))
            .replace("{OnGround}", String.valueOf(onGround))
            .replace("{Air}", String.valueOf(air))
            .replace("{FireTicks}", String.valueOf(fireTicks))
            .replace("{SelectedSlot}", String.valueOf(mc.player.getInventory().selectedSlot))
            .replace("{BlockUnder}", blockUnder.getName().getString())
            .replace("{Biome}", biome)
            .replace("{LightLevel}", String.valueOf(light))
            .replace("{MainHand}", mainHand.getName().getString())
            .replace("{MainHandRaw}", Registries.ITEM.getId(mainHand.getItem()).toString())
            .replace("{OffHand}", offHand.getName().getString())
            .replace("{OffHandRaw}", Registries.ITEM.getId(offHand.getItem()).toString())
            .replace("{Helmet}", helmet.getName().getString())
            .replace("{HelmetRaw}", Registries.ITEM.getId(helmet.getItem()).toString())
            .replace("{Chestplate}", chest.getName().getString())
            .replace("{ChestplateRaw}", Registries.ITEM.getId(chest.getItem()).toString())
            .replace("{Leggings}", legs.getName().getString())
            .replace("{LeggingsRaw}", Registries.ITEM.getId(legs.getItem()).toString())
            .replace("{Boots}", boots.getName().getString())
            .replace("{BootsRaw}", Registries.ITEM.getId(boots.getItem()).toString())
            .replace("{NearbyPlayers}", nearbyPlayers);
            for (int i = 0; i < 36; i++) {
                ItemStack stack = mc.player.getInventory().getStack(i);
                String name = stack.isEmpty() ? "air" : stack.getName().getString();
                String raw = stack.isEmpty() ? "minecraft:air" : Registries.ITEM.getId(stack.getItem()).toString();
                result = result.replace("{Inventory" + i + "}", name);
                result = result.replace("{Inventory" + i + "Raw}", raw);
            }

        return result;
    }
}
