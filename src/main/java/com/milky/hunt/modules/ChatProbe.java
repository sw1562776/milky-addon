package com.milky.hunt.modules;

import com.milky.hunt.Addon;
import meteordevelopment.meteorclient.events.game.ReceiveMessageEvent;
import meteordevelopment.meteorclient.events.packets.PacketEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.IntSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.StringSetting;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.orbit.EventHandler;
import meteordevelopment.orbit.EventPriority;

import net.minecraft.client.MinecraftClient;
import net.minecraft.network.packet.Packet;
import net.minecraft.network.packet.c2s.play.ChatMessageC2SPacket;
import net.minecraft.network.packet.c2s.play.CommandExecutionC2SPacket;
import net.minecraft.text.Text;

import java.util.concurrent.ConcurrentLinkedQueue;

public class ChatProbe extends Module {
    private final Setting<Integer> windowMs = settings.getDefaultGroup().add(new IntSetting.Builder()
        .name("window-ms").description("Capture window after enabling.")
        .defaultValue(6000).min(500).max(60000).build());

    private final Setting<Boolean> logOutgoing = settings.getDefaultGroup().add(new BoolSetting.Builder()
        .name("log-outgoing").description("Log outgoing chat/command packets (C2S).")
        .defaultValue(true).build());

    private final Setting<Boolean> logIncoming = settings.getDefaultGroup().add(new BoolSetting.Builder()
        .name("log-incoming").description("Log incoming chat messages (server echo).")
        .defaultValue(true).build());

    private final Setting<Integer> maxLinesPerTick = settings.getDefaultGroup().add(new IntSetting.Builder()
        .name("max-lines-per-tick").description("Max probe lines to post to chat per tick.")
        .defaultValue(4).min(1).max(20).build());

    private final Setting<String> prefix = settings.getDefaultGroup().add(new StringSetting.Builder()
        .name("prefix").description("Prefix for probe messages.")
        .defaultValue("[Probe]").build());

    private long endAtNanos;
    private final ConcurrentLinkedQueue<String> hudQueue = new ConcurrentLinkedQueue<>();

    public ChatProbe() {
        super(Addon.MilkyModCategory, "ChatProbe", "Logs outgoing /w and incoming chat echo for a short window.");
    }

    @Override
    public void onActivate() {
        endAtNanos = System.nanoTime() + (long) windowMs.get() * 1_000_000L;
        queueHud(prefix.get() + " started for " + windowMs.get() + " ms");
    }

    @EventHandler
    private void onTick(TickEvent.Post e) {
        if (System.nanoTime() > endAtNanos) {
            queueHud(prefix.get() + " done");
            flushHud();
            toggle();
            return;
        }
        flushHud();
    }

    // 出站（客户端 -> 服务器）
    @EventHandler(priority = EventPriority.HIGHEST)
    private void onSend(PacketEvent.Send e) {
        if (!logOutgoing.get() || System.nanoTime() > endAtNanos) return;

        Packet<?> p = e.packet;
        try {
            if (p instanceof CommandExecutionC2SPacket cmd) {
                logToChat("[C2S] Command: /" + cmd.command());
            } else if (p instanceof ChatMessageC2SPacket chat) {
                logToChat("[C2S] Chat: " + chat.chatMessage());
            } else {
                String name = p.getClass().getSimpleName();
                if (name.contains("ChatCommand") || name.contains("ChatMessage")) {
                    logToChat("[C2S] " + name + " (details omitted)");
                }
            }
        } catch (Throwable t) {
            logToChat("[C2S][ERR] " + t.getClass().getSimpleName() + ": " + t.getMessage());
        }
    }

    // 入站（服务器 -> 客户端）
    @EventHandler(priority = EventPriority.HIGHEST)
    private void onReceive(ReceiveMessageEvent e) {
        if (!logIncoming.get() || System.nanoTime() > endAtNanos) return;

        try {
            String raw = e.getMessage() != null ? e.getMessage().getString() : "";
            if (raw == null) return;
            raw = raw.trim();
            if (raw.isEmpty()) return;

            String pfx = prefix.get();
            if (!pfx.isEmpty() && raw.startsWith(pfx)) return; // 不要自回声

            logToChat("[S2C] Chat: " + raw);
        } catch (Throwable t) {
            logToChat("[S2C][ERR] " + t.getClass().getSimpleName() + ": " + t.getMessage());
        }
    }

    // 工具
    private void logToChat(String body) {
        if (body == null) return;
        String line = prefix.get() + " " + body;
        queueHud(line);
    }

    private void queueHud(String msg) {
        if (msg != null && !msg.isEmpty()) hudQueue.offer(msg);
    }

    private void flushHud() {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc == null) return;

        int n = 0, max = Math.max(1, maxLinesPerTick.get());
        while (n < max) {
            String s = hudQueue.poll();
            if (s == null) break;
            n++;
            mc.execute(() -> {
                if (mc.inGameHud != null && mc.inGameHud.getChatHud() != null) {
                    mc.inGameHud.getChatHud().addMessage(Text.literal(s));
                }
            });
        }
    }
}
