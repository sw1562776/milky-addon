package com.stash.hunt.modules;

import com.mojang.authlib.GameProfile;
import com.stash.hunt.Addon;
import meteordevelopment.meteorclient.events.game.GameLeftEvent;
import meteordevelopment.meteorclient.events.game.ReceiveMessageEvent;
import meteordevelopment.meteorclient.events.packets.PacketEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.network.packet.s2c.play.SubtitleS2CPacket;
import net.minecraft.text.Text;
import net.minecraft.text.TextColor;

import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

import static com.stash.hunt.Utils.sendWebhook;


public class DiscordNotifs extends Module
{
    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    private final Setting<String> webhookURL = sgGeneral.add(new StringSetting.Builder()
        .name("Webhook Link")
        .description("The discord webhook to use, looks like this: https://discord.com/api/webhooks/webhookUserId/webHookTokenOrSomething")
        .defaultValue("")
        .build()
    );

    private final Setting<Integer> delay = sgGeneral.add(new IntSetting.Builder()
        .name("Message Delay (MS)")
        .description("The delay between messages in milliseconds.")
        .defaultValue(2000)
        .build()
    );

    private final Setting<Boolean> queueMessages = sgGeneral.add(new BoolSetting.Builder()
        .name("Queue Messages")
        .description("Will queue messages if they are sent too quickly. This could result in a long delay between messages being logged if the queue gets too big.")
        .defaultValue(false)
        .build()
    );

    private final Setting<Boolean> timestamp = sgGeneral.add(new BoolSetting.Builder()
        .name("Timestamp")
        .description("If the message should have a timestamp.")
        .defaultValue(false)
        .build()
    );

    private final Setting<Boolean> logAll = sgGeneral.add(new BoolSetting.Builder()
        .name("All Messages")
        .description("Logs all messages.")
        .defaultValue(false)
        .build()
    );

    private final Setting<Boolean> connections = sgGeneral.add(new BoolSetting.Builder()
        .name("Disconnect")
        .description("If a message should be logged when leaving.")
        .defaultValue(false)
        .visible(() -> !logAll.get())
        .build()
    );

    private final Setting<Boolean> playerRange = sgGeneral.add(new BoolSetting.Builder()
        .name("Player Range")
        .description("If a message should be logged when players enter/exit your render distance.")
        .defaultValue(false)
        .visible(() -> !logAll.get())
        .build()
    );

    private final Setting<Boolean> queue = sgGeneral.add(new BoolSetting.Builder()
        .name("2b2t Queue")
        .description("If your position in queue should be logged.")
        .defaultValue(false)
        .visible(() -> !logAll.get())
        .build()
    );

    private final Setting<Boolean> whisper = sgGeneral.add(new BoolSetting.Builder()
        .name("Whisper")
        .description("If whispers should be logged.")
        .defaultValue(false)
        .visible(() -> !logAll.get())
        .build()
    );

    private final Setting<Boolean> chat = sgGeneral.add(new BoolSetting.Builder()
        .name("Logs chat messages.")
        .description("Logs all chat messages")
        .defaultValue(false)
        .visible(() -> !logAll.get())
        .build()
    );

    private final Setting<Boolean> commands = sgGeneral.add(new BoolSetting.Builder()
        .name("Commands/Client Info")
        .description("Logs commands and most messages from clients.")
        .defaultValue(false)
        .visible(() -> !logAll.get())
        .build()
    );

    private final Setting<Boolean> deathMessages = sgGeneral.add(new BoolSetting.Builder()
        .name("Death Messages")
        .description("Logs death messages.")
        .defaultValue(false)
        .visible(() -> !logAll.get())
        .build()
    );

    public DiscordNotifs()
    {
        super(Addon.CATEGORY, "DiscordNotifs", "Sends notifications to a discord webhook.");
    }

    @Override
    public void onActivate()
    {
        messageQueue.clear();
        playersInRange.clear();
        delayTimer = 0;
    }

    private long delayTimer = 0;
    private int lastQueuePos;
    private final Queue<String> messageQueue = new LinkedList<String>();
    private final Set<GameProfile> playersInRange = new HashSet<>();

    @EventHandler
    private void onTick(TickEvent.Post event)
    {
        if (delayTimer > 0)
        {
            delayTimer--;
        }
        else if (queueMessages.get() && !messageQueue.isEmpty())
        {
            sendWebhookMessage(messageQueue.poll());
        }

        Set<UUID> uuidsCurrentlyInRange = new HashSet<>();

        // Check for players entering range
        if (playerRange.get() && mc.world != null)
        {
            for (Entity entity : mc.world.getEntities())
            {
                if (entity.getUuid().equals(mc.player.getUuid())) continue;
                if (entity instanceof PlayerEntity playerEntity)
                {
                    uuidsCurrentlyInRange.add(playerEntity.getUuid());
                    if (!playersInRange.contains(playerEntity.getGameProfile()))
                    {
                        playersInRange.add(playerEntity.getGameProfile());
                        handleMessage(playerEntity.getGameProfile().getName() + " has entered visual range!", MessageType.PLAYER_RANGE);
                    }
                }
            }
        }

        // Check for players leaving range
        for (GameProfile profile : playersInRange)
        {
            if (!uuidsCurrentlyInRange.contains(profile.getId()))
            {
                playersInRange.remove(profile);
                handleMessage(profile.getName() + " has left visual range!", MessageType.PLAYER_RANGE);
            }
        }
    }

    // For getting the queue position
    @EventHandler(priority = 999)
    private void onReceivePacket(PacketEvent.Receive event) {
        if (event.packet instanceof SubtitleS2CPacket) {
            SubtitleS2CPacket packet = (SubtitleS2CPacket) event.packet;
            // Position in queue: 287
            String message = packet.text().getString();
            int queueIndex = message.indexOf("Position in queue: ");
            if (queueIndex != -1)
            {
                int queuePos = Integer.parseInt(message.substring(queueIndex + 19));
                if (queuePos != lastQueuePos)
                {
                    handleMessage(message, MessageType.QUEUE);
                    lastQueuePos = queuePos;
                }
            }
        }
    }

    // do it first so we avoid mods altering the message, like meteors antispam
    @EventHandler(priority = 999)
    private void onMessageReceive(ReceiveMessageEvent event)
    {
        Text message = event.getMessage();
        for (Text sibling : message.getSiblings())
        {
            TextColor color = sibling.getStyle().getColor();
            if (color != null && color.getRgb() == 11141120)
            {
                handleMessage(message.getString(), MessageType.DEATH);
                return;
            }
        }
        handleMessage(message.getString(), MessageType.NORMAL);
    }

    public void handleMessage(String message, MessageType messageType)
    {
        if (webhookURL.get().isBlank()) return;

        if (logAll.get())
        {
            sendWebhookMessage(message);
        }
        else if (connections.get() && messageType.equals(MessageType.DISCONNECT))
        {
            sendWebhookMessage(message);
        }
        else if (playerRange.get() && messageType.equals(MessageType.PLAYER_RANGE))
        {
            sendWebhookMessage(message);
        }
        else if (queue.get() && messageType.equals(MessageType.QUEUE))
        {
            sendWebhookMessage(message);
        }
        else if (whisper.get()
            && !message.startsWith("<")
            && message.contains("whispers: ") // from player
            || message.startsWith("to ")) // to player
        {
            sendWebhookMessage(message);
        }
        else if (chat.get() && message.startsWith("<"))
        {
            sendWebhookMessage(message);
        }
        else if (deathMessages.get() && messageType.equals(MessageType.DEATH))
        {
            sendWebhookMessage(message);
        }
        else if (commands.get() && !message.startsWith("<")
            && !message.startsWith("to ")
            && messageType.equals(MessageType.NORMAL))
        {
            sendWebhookMessage(message);
        }
  }

    @EventHandler
    private void onDisconnect(GameLeftEvent event)
    {
        handleMessage("Disconnected", MessageType.DISCONNECT);
    }

    private void sendWebhookMessage(String message)
    {
        if (delayTimer > 0)
        {
            if (queueMessages.get()) messageQueue.offer(message);
            return;
        }
        delayTimer = delay.get() / 1000 * 20;
        if (timestamp.get())
        {
            LocalTime now = LocalTime.now();
            DateTimeFormatter formatter = DateTimeFormatter.ofPattern("HH:mm");
            String timestamp = now.format(formatter);
            message = "[" + timestamp + "] " + message;
        }
        String json = "{\n" +
            "\"content\": \"```" + message + "```\"\n" +
            "}";
        // use threads so the game doesnt lag when sending a ton of webhooks
        new Thread(() -> sendWebhook(webhookURL.get(), json, null)).start();
    }

    public enum MessageType
    {
        NORMAL,
        DEATH,
        QUEUE,
        DISCONNECT,
        PLAYER_RANGE
    }
}
