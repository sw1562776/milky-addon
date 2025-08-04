package com.milky.hunt.modules;

import com.milky.hunt.Addon;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.StringSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.orbit.EventHandler;

import net.minecraft.network.packet.c2s.play.CommandExecutionC2SPacket;
import net.minecraft.network.packet.c2s.play.ChatMessageC2SPacket;
import net.minecraft.network.message.MessageSignatureData;
import net.minecraft.network.message.LastSeenMessageList;

import java.time.Instant;

public class QuickCommand extends Module {
    private final Setting<String> command = settings.getDefaultGroup().add(new StringSetting.Builder()
        .name("command")
        .description("Send a command or chat message, supports {CoordX}, {CoordY}, {CoordZ}.")
        .defaultValue("/w Wandelion {CoordX}, {CoordY}, {CoordZ}")
        .build()
    );

    private boolean hasSent;

    public QuickCommand() {
        super(Addon.CATEGORY, "QuickCommand", "Send command or message (supports signed-chat bypass).");
    }

    @Override public void onActivate() { hasSent = false; }

    @EventHandler private void onTick(TickEvent.Post event) {
        if (mc.player == null || mc.world == null || mc.getNetworkHandler() == null) return;
        if (hasSent) return;

        String parsed = parseCommand(command.get());

        if (parsed.startsWith("/")) {
            // Send command packet
            mc.getNetworkHandler().sendPacket(new CommandExecutionC2SPacket(parsed.substring(1)));
        } else {
            // Send unsigned chat packet
            mc.getNetworkHandler().sendChatMessage(parsed);
        }

        hasSent = true;
        toggle();
    }

    private String parseCommand(String input) {
        double x = mc.player.getX(), y = mc.player.getY(), z = mc.player.getZ();
        return input.replace("{CoordX}", String.format("%.1f", x))
                    .replace("{CoordY}", String.format("%.1f", y))
                    .replace("{CoordZ}", String.format("%.1f", z));
    }
}
