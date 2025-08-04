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
import net.minecraft.util.registry.Registry;
import net.minecraft.entity.player.PlayerEntity;

import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import java.util.stream.Collectors;

public class QuickCommandPlus extends Module {
    private final Setting<String> command = settings.getDefaultGroup().add(new StringSetting.Builder()
        .name("command")
        .description("Send a quick message/command with rich placeholders.")
        .defaultValue("/w Wandelion {CoordX}, {CoordY}, {CoordZ}")
        .build()
    );

    private boolean hasSent;

    public QuickCommandPlus() {
        super(Addon.CATEGORY, "QuickCommandPlus", "Send a message/command with rich placeholders like {CoordX}, {Health}, etc.");
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
        int bx = (int) Math.floor(x);
        int by = (int) Math.floor(y);
        int bz = (int) Math.floor(z);

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

        String result = input
            .replace("{CoordX}", String.format("%.1f", x))
            .replace("{CoordY}", String.format("%.1f", y))
            .replace("{CoordZ}", String.format("%.1f", z))
            .replace("{Dimension}", dimension)

            .replace("{Player}", playerName)
            .replace("{UUID}", uuid)
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

            .replace("{Time}", time)
            .replace("{Timestamp}", timestamp)

            .replace("{IP}", serverIp)
            .replace("{ServerName}", serverName)

            .replace("{MainHand}", mainHand.getName().getString())
            .replace("{MainHandRaw}", Registry.ITEM.getId(mainHand.getItem()).toString())
            .replace("{OffHand}", offHand.getName().getString())

            .replace("{Helmet}", helmet.getName().getString())
            .replace("{Chestplate}", chest.getName().getString())
            .replace("{Leggings}", legs.getName().getString())
            .replace("{Boots}", boots.getName().getString())

            .replace("{BlockUnder}", blockUnder.getName().getString())
            .replace("{Biome}", biome)
            .replace("{LightLevel}", String.valueOf(light))

            .replace("{SelectedSlot}", String.valueOf(mc.player.getInventory().selectedSlot))

            .replace("{NearbyPlayers}", nearbyPlayers);

        for (int i = 0; i < 36; i++) {
            ItemStack stack = mc.player.getInventory().getStack(i);
            String name = stack.isEmpty() ? "air" : stack.getName().getString();
            result = result.replace("{Inventory" + i + "}", name);
        }

        return result;
    }
}
