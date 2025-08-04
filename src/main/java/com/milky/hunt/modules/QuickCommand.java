package com.milky.hunt.modules;

import com.milky.hunt.Addon;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.StringSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.orbit.EventHandler;

public class QuickCommand extends Module {
    private final Setting<String> command = settings.getDefaultGroup().add(new StringSetting.Builder()
        .name("command")
        .description("The command to send when toggled. Use {x}, {y}, {z} for your coordinates.")
        .defaultValue("#stop")
        .build()
    );

    private boolean hasSent = false;

    public QuickCommand() {
        super(Addon.CATEGORY, "quick-command", "Sends a fixed or coordinate-based command when toggled.");
    }

    @EventHandler
    private void onTick(TickEvent.Post event) {
        if (mc.player == null || mc.world == null) return;

        if (!hasSent) {
            String raw = command.get();
            String parsed = parseCommand(raw);
            mc.getNetworkHandler().sendChatMessage(parsed);
            hasSent = true;
            toggle();
        }
    }

    private String parseCommand(String input) {
        double x = mc.player.getX();
        double y = mc.player.getY();
        double z = mc.player.getZ();

        return input
            .replace("{x}", String.format("%.1f", x))
            .replace("{y}", String.format("%.1f", y))
            .replace("{z}", String.format("%.1f", z));
    }

    @Override
    public void onActivate() {
        hasSent = false;
    }
}
