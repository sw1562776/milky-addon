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
        .defaultValue("/w Wandelion {CoordX} {CoordY} {CoordZ}")
        .build()
    );

    private final Setting<String> infoText = settings.getDefaultGroup().add(new StringSetting.Builder()
    .name("The command to send when toggled. Use {CoordX}, {CoordY}, {CoordZ} for your coordinates.")
    .defaultValue("")
    .visible(() -> false)
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
        double coordx = mc.player.getX();
        double coordy = mc.player.getY();
        double coordz = mc.player.getZ();

        return input
            .replace("{CoordX}", String.format("%.1f", coordx))
            .replace("{CoordY}", String.format("%.1f", coordy))
            .replace("{CoordZ}", String.format("%.1f", coordz));
    }

    @Override
    public void onActivate() {
        hasSent = false;
    }
}
