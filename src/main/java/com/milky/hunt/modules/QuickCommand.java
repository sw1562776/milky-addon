package com.milky.hunt.modules;

import com.milky.hunt.Addon;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.StringSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.network.packet.c2s.play.CommandExecutionC2SPacket;
import net.minecraft.network.packet.c2s.play.ChatMessageC2SPacket;

import java.time.Instant;
import java.util.BitSet;

public class QuickCommand extends Module {
    private final Setting<String> command = settings.getDefaultGroup().add(new StringSetting.Builder()
        .name("command")
        .description("Command or message to send. Use {CoordX}, {CoordY}, {CoordZ} for player position.")
        .defaultValue("/w tifmaid Hello at {CoordX} {CoordY} {CoordZ}")
        .build()
    );

    private boolean hasSent = false;

    public QuickCommand() {
        super(Addon.CATEGORY, "quick-command", "Sends a command or message (even on 2b2t) when toggled.");
    }

    @Override
    public void onActivate() {
        hasSent = false;
    }

    @EventHandler
    private void onTick(TickEvent.Post event) {
        if (mc.player == null || mc.world == null || mc.getNetworkHandler() == null) return;
        if (hasSent) return;

        String raw = command.get();
        String parsed = parseCommand(raw);

        if (parsed.startsWith("/")) {
            // Remove leading slash and send as command packet
            String commandWithoutSlash = parsed.substring(1);
            mc.getNetworkHandler().sendPacket(new CommandExecutionC2SPacket(
                commandWithoutSlash,
                Instant.now().toEpochMilli(),
                0L,
                null,
                0,
                new BitSet()
            ));
        } else {
            // Send as regular chat message
            mc.getNetworkHandler().sendPacket(new ChatMessageC2SPacket(
                parsed,
                Instant.now().toEpochMilli(),
                0L,
                null,
                0,
                new BitSet()
            ));
        }

        hasSent = true;
        toggle();
    }

    private String parseCommand(String input) {
        double x = mc.player.getX();
        double y = mc.player.getY();
        double z = mc.player.getZ();

        return input
            .replace("{CoordX}", String.format("%.1f", x))
            .replace("{CoordY}", String.format("%.1f", y))
            .replace("{CoordZ}", String.format("%.1f", z));
    }
}
