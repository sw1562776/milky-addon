package com.milky.hunt.modules;

import com.milky.hunt.Addon;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.StringSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.network.packet.c2s.play.ChatMessageC2SPacket;
import net.minecraft.text.Text;

public class QuickCommand extends Module {
    private final Setting<String> command = settings.getDefaultGroup().add(new StringSetting.Builder()
        .name("command")
        .description("Command to send when toggled. Supports placeholders like {x}, {y}, {z}.")
        .defaultValue("/w tifmaid 123")
        .build()
    );

    private boolean hasSent = false;

    public QuickCommand() {
        super(Addon.CATEGORY, "QuickCommand", "Sends a fixed or coordinate-based command when toggled.");
    }

    @EventHandler
    private void onTick(TickEvent.Post event) {
        if (mc.player == null || mc.world == null) return;

        if (!hasSent) {
            String raw = command.get();
            String parsed = parseCommand(raw);

            // 使用 Text.literal() 适配 ChatMessageC2SPacket 的构造函数
            mc.getNetworkHandler().sendPacket(new ChatMessageC2SPacket(Text.literal(parsed)));

            hasSent = true;
            toggle();
        }
    }

    @Override
    public void onActivate() {
        hasSent = false;
    }

    //private String parseCommand(String raw) {
    //    return raw.replace("{x}", String.valueOf((int) mc.player.getX()))
    //               .replace("{y}", String.valueOf((int) mc.player.getY()))
    //             .replace("{z}", String.valueOf((int) mc.player.getZ()));
    //}
}
