package com.milky.hunt.modules;

import com.milky.hunt.Addon;
import it.unimi.dsi.fastutil.objects.Object2IntMap;
import it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap;
import meteordevelopment.meteorclient.events.packets.PacketEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.friends.Friends;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.systems.modules.Modules;
import meteordevelopment.meteorclient.systems.modules.misc.AutoReconnect;
import meteordevelopment.meteorclient.utils.player.PlayerUtils;
import meteordevelopment.meteorclient.utils.player.SlotUtils;
import meteordevelopment.meteorclient.utils.world.TickRate;
import meteordevelopment.orbit.EventHandler;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.texture.NativeImage;
import net.minecraft.client.util.ScreenshotRecorder;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityStatuses;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.network.packet.s2c.common.DisconnectS2CPacket;
import net.minecraft.network.packet.s2c.play.EntityStatusS2CPacket;
import net.minecraft.registry.Registries;
import net.minecraft.text.MutableText;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockPos;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Locale;
import java.util.Set;

public class EventLog extends Module {
    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    // =========================
    // New triggers (GUI-style toggles)
    // Put BEFORE your existing triggers in GUI order
    // =========================

    // Health mode
    private final Setting<Boolean> logOnHealth = sgGeneral.add(new BoolSetting.Builder()
        .name("log-on-health")
        .description("Logs out when your health is low.")
        .defaultValue(false)
        .build()
    );

    private final Setting<Integer> health = sgGeneral.add(new IntSetting.Builder()
        .name("health")
        .description("Trigger when health <= this value.")
        .defaultValue(6)
        .range(0, 19)
        .sliderMax(19)
        .visible(logOnHealth::get)
        .build()
    );

    private final Setting<Boolean> predictIncomingDamage = sgGeneral.add(new BoolSetting.Builder()
        .name("predict-incoming-damage")
        .description("Also logs out when it predicts you'll take enough damage to go under the 'health' setting.")
        .defaultValue(true)
        .visible(logOnHealth::get)
        .build()
    );

    // Totem mode
    private final Setting<Boolean> logOnTotem = sgGeneral.add(new BoolSetting.Builder()
        .name("log-on-totem")
        .description("Logs out after you pop a specified number of totems.")
        .defaultValue(false)
        .build()
    );

    private final Setting<Integer> totemPops = sgGeneral.add(new IntSetting.Builder()
        .name("totem-pops")
        .description("Trigger when you have popped this many totems.")
        .defaultValue(1)
        .min(1)
        .sliderMax(20)
        .visible(logOnTotem::get)
        .build()
    );

    // Player detection (AutoLog-style + ignore friends)
    private final Setting<Boolean> logOnPlayer = sgGeneral.add(new BoolSetting.Builder()
        .name("log-on-player")
        .description("Logs out when a player appears in render distance.")
        .defaultValue(false)
        .build()
    );

    private final Setting<Boolean> ignoreFriends = sgGeneral.add(new BoolSetting.Builder()
        .name("ignore-friends")
        .description("Ignore friends when checking nearby players.")
        .defaultValue(true)
        .visible(logOnPlayer::get)
        .build()
    );

    // Entities mode
    private final Setting<Boolean> logOnEntities = sgGeneral.add(new BoolSetting.Builder()
        .name("log-on-entities")
        .description("Logs out when selected entities are present within range.")
        .defaultValue(false)
        .build()
    );

    private final Setting<Set<EntityType<?>>> entities = sgGeneral.add(new EntityTypeListSetting.Builder()
        .name("entities")
        .description("Which entities to detect.")
        .defaultValue(EntityType.END_CRYSTAL)
        .visible(logOnEntities::get)
        .build()
    );

    private final Setting<Boolean> useTotalCount = sgGeneral.add(new BoolSetting.Builder()
        .name("use-total-count")
        .description("Count total of all selected entities (true) or each entity type individually (false).")
        .defaultValue(true)
        .visible(() -> logOnEntities.get() && !entities.get().isEmpty())
        .build()
    );

    private final Setting<Integer> combinedEntityThreshold = sgGeneral.add(new IntSetting.Builder()
        .name("combined-entity-threshold")
        .description("Minimum total number of selected entities near you to trigger.")
        .defaultValue(10)
        .min(1)
        .sliderMax(64)
        .visible(() -> logOnEntities.get() && useTotalCount.get() && !entities.get().isEmpty())
        .build()
    );

    private final Setting<Integer> individualEntityThreshold = sgGeneral.add(new IntSetting.Builder()
        .name("individual-entity-threshold")
        .description("Minimum number of any one entity type near you to trigger.")
        .defaultValue(2)
        .min(1)
        .sliderMax(32)
        .visible(() -> logOnEntities.get() && !useTotalCount.get() && !entities.get().isEmpty())
        .build()
    );

    private final Setting<Integer> entityRange = sgGeneral.add(new IntSetting.Builder()
        .name("entity-range")
        .description("How close an entity has to be to trigger.")
        .defaultValue(5)
        .min(1)
        .sliderMax(32)
        .visible(() -> logOnEntities.get() && !entities.get().isEmpty())
        .build()
    );

    // =========================
    // Your existing triggers (unchanged) - keep AFTER new triggers
    // =========================

    private final Setting<Boolean> logOnY = sgGeneral.add(new BoolSetting.Builder()
        .name("log-on-y")
        .description("Logs out if you are below a certain Y level.")
        .defaultValue(false)
        .build()
    );

    private final Setting<Double> yLevel = sgGeneral.add(new DoubleSetting.Builder()
        .name("y-level")
        .defaultValue(256)
        .min(-128)
        .sliderRange(-128, 320)
        .visible(logOnY::get)
        .build()
    );

    private final Setting<Boolean> logOnVy = sgGeneral.add(new BoolSetting.Builder()
        .name("log-on-Vy")
        .description("Logs out if your vertical velocity (Vy) is below a threshold.")
        .defaultValue(false)
        .build()
    );

    private final Setting<Double> vyThreshold = sgGeneral.add(new DoubleSetting.Builder()
        .name("Vy-threshold")
        .description("Trigger when Vy <= this value. Unit: blocks/second (b/s). Example: -50 means fast free-fall.")
        .defaultValue(-50)
        .sliderRange(-100, 0)
        .visible(logOnVy::get)
        .build()
    );

    private final Setting<Boolean> logArmor = sgGeneral.add(new BoolSetting.Builder()
        .name("log-armor")
        .description("Logs out if your armor goes below a certain durability amount.")
        .defaultValue(false)
        .build()
    );

    private final Setting<Boolean> ignoreElytra = sgGeneral.add(new BoolSetting.Builder()
        .name("ignore-elytra")
        .description("Ignores the elytra when checking armor durability.")
        .defaultValue(false)
        .visible(logArmor::get)
        .build()
    );

    private final Setting<Double> armorPercent = sgGeneral.add(new DoubleSetting.Builder()
        .name("armor-percent")
        .defaultValue(5)
        .min(0)
        .sliderRange(0, 100)
        .visible(logArmor::get)
        .build()
    );

    private final Setting<Boolean> logPortal = sgGeneral.add(new BoolSetting.Builder()
        .name("log-on-portal")
        .description("Logs out if you are in a portal for too long.")
        .defaultValue(false)
        .build()
    );

    private final Setting<Integer> portalTicks = sgGeneral.add(new IntSetting.Builder()
        .name("portal-ticks")
        .description("The amount of ticks in a portal before you get kicked (It takes 80 ticks to go through a portal).")
        .defaultValue(30)
        .min(1)
        .sliderMax(70)
        .visible(logPortal::get)
        .build()
    );

    private final Setting<Boolean> logPosition = sgGeneral.add(new BoolSetting.Builder()
        .name("log-position")
        .description("Logs out if you are within x blocks of this position. Y Position is not included.")
        .defaultValue(false)
        .build()
    );

    private final Setting<BlockPos> position = sgGeneral.add(new BlockPosSetting.Builder()
        .name("position")
        .description("The position to log out at. Y position is ignored.")
        .defaultValue(new BlockPos(0, 0, 0))
        .visible(logPosition::get)
        .build()
    );

    private final Setting<Double> distance = sgGeneral.add(new DoubleSetting.Builder()
        .name("distance")
        .description("The distance from the position to log out at.")
        .defaultValue(100)
        .sliderRange(0, 1000)
        .visible(logPosition::get)
        .build()
    );

    private final Setting<Boolean> serverNotResponding = sgGeneral.add(new BoolSetting.Builder()
        .name("server-not-responding")
        .description("Logs out if the server is not responding.")
        .defaultValue(false)
        .build()
    );

    private final Setting<Double> serverNotRespondingSecs = sgGeneral.add(new DoubleSetting.Builder()
        .name("seconds-not-responding")
        .description("The amount of seconds the server is not responding before you log out.")
        .defaultValue(10)
        .min(1)
        .sliderMax(60)
        .visible(serverNotResponding::get)
        .build()
    );

    private final Setting<Boolean> reconnectAfterNotResponding = sgGeneral.add(new BoolSetting.Builder()
        .name("reconnect-after-not-responding")
        .description("Reconnects after the server is not responding.")
        .defaultValue(false)
        .visible(serverNotResponding::get)
        .build()
    );

    private final Setting<Double> secondsToReconnect = sgGeneral.add(new DoubleSetting.Builder()
        .name("reconnect-seconds")
        .description("The amount of seconds to wait before reconnecting (Will temporarily overwrite Meteor's AutoReconnect delay).")
        .defaultValue(60)
        .min(10)
        .sliderMax(60 * 5)
        .visible(() -> reconnectAfterNotResponding.get() && serverNotResponding.get())
        .build()
    );

    private final Setting<Boolean> illegalDisconnect = sgGeneral.add(new BoolSetting.Builder()
        .name("illegal-disconnect")
        .description("Disconnects from the server using the slot method.")
        .defaultValue(false)
        .build()
    );

    // Screenshot
    private final Setting<Boolean> screenshotPreDisconnect = sgGeneral.add(new BoolSetting.Builder()
        .name("screenshot-pre-disconnect")
        .description("Take a screenshot right before disconnect. Saved to .minecraft/MilkyMod/EventLog/")
        .defaultValue(false)
        .build()
    );

    // ===== runtime state =====
    private int currPortalTicks = 0;
    private double oldDelay;
    private boolean autoReconnectEnabled;
    private boolean waitingForReconnection = false;

    // Totem pop counter
    private int pops = 0;

    // Entity counts map
    private final Object2IntMap<EntityType<?>> entityCounts = new Object2IntOpenHashMap<>();

    public EventLog() {
        super(Addon.MilkyModCategory, "EventLog", "Provides some additional triggers to log out.");
    }

    @Override
    public void onActivate() {
        currPortalTicks = 0;
        pops = 0;

        // onGameJoin event never triggered for some reason so i put it here (kept from upstream)
        if (waitingForReconnection) {
            waitingForReconnection = false;

            AutoReconnect autoReconnect = Modules.get().get(AutoReconnect.class);
            @SuppressWarnings("unchecked")
            Setting<Double> delay = (Setting<Double>) autoReconnect.settings.get("delay");

            if (delay != null) delay.set(oldDelay);

            if (!autoReconnectEnabled && autoReconnect.isActive()) {
                autoReconnect.toggle();
            }
        }
    }

    // Totem pops
    @EventHandler
    private void onReceivePacket(PacketEvent.Receive event) {
        if (!logOnTotem.get()) return;
        if (!(event.packet instanceof EntityStatusS2CPacket p)) return;
        if (p.getStatus() != EntityStatuses.USE_TOTEM_OF_UNDYING) return;

        if (mc.player == null || mc.world == null) return;

        Entity e = p.getEntity(mc.world);
        if (e == null || !e.equals(mc.player)) return;

        pops++;
        if (pops >= totemPops.get()) {
            logOut(buildTotemReason(pops), true);
        }
    }

    @EventHandler
    private void onTick(TickEvent.Post event) {
        // If in the 2b2t queue
        if (mc.player == null || mc.player.getAbilities().allowFlying) return;

        // ===== Health mode =====
        if (logOnHealth.get()) {
            float hp = mc.player.getHealth();
            if (hp > 0) {
                if (hp <= health.get()) {
                    logOut(buildHealthReason(hp), true);
                    return;
                }

                if (predictIncomingDamage.get()) {
                    float incoming = PlayerUtils.possibleHealthReductions();
                    float abs = mc.player.getAbsorptionAmount();
                    float effective = hp + abs - incoming;

                    if (effective < health.get()) {
                        logOut(buildHealthPredictedReason(hp, abs, incoming, effective), true);
                        return;
                    }
                }
            }
        }

        // ===== Player detection (AutoLog-style) =====
        if (logOnPlayer.get() && mc.world != null) {
            for (Entity entity : mc.world.getEntities()) {
                if (!(entity instanceof PlayerEntity p)) continue;

                // exclude self (AutoLog-style by UUID)
                if (p.getUuid().equals(mc.player.getUuid())) continue;

                // ignore friends optionally
                if (ignoreFriends.get() && Friends.get().isFriend(p)) continue;

                logOut(buildPlayerReason(p), true);
                return;
            }
        }

        // ===== Entities detection =====
        if (logOnEntities.get() && mc.world != null && !entities.get().isEmpty()) {
            int total = 0;
            entityCounts.clear();

            Entity nearest = null;
            double nearestDist = Double.POSITIVE_INFINITY;

            for (Entity entity : mc.world.getEntities()) {
                if (!entities.get().contains(entity.getType())) continue;

                double d = mc.player.distanceTo(entity);
                if (d <= entityRange.get()) {
                    total++;
                    entityCounts.put(entity.getType(), entityCounts.getOrDefault(entity.getType(), 0) + 1);

                    if (d < nearestDist) {
                        nearestDist = d;
                        nearest = entity;
                    }
                }
            }

            if (total == 0) return;

            if (useTotalCount.get()) {
                if (total >= combinedEntityThreshold.get()) {
                    logOut(buildEntitiesTotalReason(total, nearest, nearestDist), true);
                    return;
                }
            } else {
                for (Object2IntMap.Entry<EntityType<?>> entry : entityCounts.object2IntEntrySet()) {
                    if (entry.getIntValue() >= individualEntityThreshold.get()) {
                        logOut(buildEntitiesIndividualReason(entry.getKey(), entry.getIntValue(), total, nearest, nearestDist), true);
                        return;
                    }
                }
            }
        }

        // ===== Your existing triggers =====

        if (serverNotResponding.get() && !waitingForReconnection) {
            if (TickRate.INSTANCE.getTimeSinceLastTick() > serverNotRespondingSecs.get()) {
                if (reconnectAfterNotResponding.get()) {
                    AutoReconnect autoReconnect = Modules.get().get(AutoReconnect.class);
                    autoReconnectEnabled = autoReconnect.isActive();

                    @SuppressWarnings("unchecked")
                    Setting<Double> delay = (Setting<Double>) autoReconnect.settings.get("delay");
                    if (delay != null) {
                        oldDelay = delay.get();
                        delay.set(secondsToReconnect.get());
                    }

                    if (!autoReconnectEnabled) autoReconnect.toggle();
                    waitingForReconnection = true;
                }

                logOut("SNR " + fmt1(TickRate.INSTANCE.getTimeSinceLastTick()) + "s (limit " + fmt1(serverNotRespondingSecs.get()) + "s).",
                    !reconnectAfterNotResponding.get());
                return;
            }
        }

        if (logPortal.get() && mc.player.portalManager != null) {
            if (mc.player.portalManager.isInPortal()) {
                currPortalTicks++;
                if (currPortalTicks > portalTicks.get()) {
                    logOut("Portal ticks=" + currPortalTicks + " (limit " + portalTicks.get() + ").", true);
                    return;
                }
            } else {
                currPortalTicks = 0;
            }
        }

        if (logOnY.get() && mc.player.getY() < yLevel.get()) {
            logOut("Y=" + fmt1(mc.player.getY()) + " (< " + fmt1(yLevel.get()) + ").", true);
            return;
        }

        if (logOnVy.get()) {
            double vyPerTick = mc.player.getVelocity().y;
            double vyPerSecond = vyPerTick * 20.0;

            if (vyPerSecond <= vyThreshold.get()) {
                logOut("Vy=" + fmt2(vyPerSecond) + " b/s (<= " + fmt1(vyThreshold.get()) + ").", true);
                return;
            }
        }

        if (logArmor.get()) {
            for (int i = 0; i < 4; i++) {
                ItemStack armorPiece = mc.player.getInventory().getStack(SlotUtils.ARMOR_START + i);

                if (ignoreElytra.get() && armorPiece.getItem() == Items.ELYTRA) continue;

                if (armorPiece.isDamageable()) {
                    int max = armorPiece.getMaxDamage();
                    int dmg = armorPiece.getDamage();
                    int left = Math.max(0, max - dmg);
                    double percentUndamaged = 100.0 - ((double) dmg / (double) max) * 100.0;

                    if (percentUndamaged < armorPercent.get()) {
                        logOut(buildArmorReason(armorPiece, percentUndamaged, left, max), true);
                        return;
                    }
                }
            }
        }

        if (logPosition.get()) {
            double distanceToTarget = mc.player.getPos()
                .multiply(1, 0, 1)
                .distanceTo(position.get().toCenterPos().multiply(1, 0, 1));

            if (distanceToTarget < distance.get()) {
                BlockPos p = position.get();
                logOut("Near pos(" + p.getX() + "," + p.getZ() + "), dist=" + fmt1(distanceToTarget) + " (< " + fmt1(distance.get()) + ").", true);
                return;
            }
        }
    }

    private void logOut(String reason, boolean turnOffReconnect) {
        if (mc.player == null) return;

        if (turnOffReconnect) {
            AutoReconnect ar = Modules.get().get(AutoReconnect.class);
            if (ar.isActive()) ar.toggle();
        }

        // Optional pre-disconnect screenshot
        if (screenshotPreDisconnect.get()) {
            MinecraftClient client = MinecraftClient.getInstance();

            if (client.isOnThread()) {
                EventLogShots.capturePreDisconnect(client);
            } else {
                client.execute(() -> EventLogShots.capturePreDisconnect(client));
            }
        } else {
            EventLogShots.lastPreScreenshotFileName = null;
        }

        if (illegalDisconnect.get()) {
            // upstream behavior kept
            mc.player.networkHandler.sendChatMessage(String.valueOf((char) 0));
        } else {
            mc.player.networkHandler.onDisconnect(
                new DisconnectS2CPacket(buildDisconnectText(reason))
            );
        }
    }

    private Text buildDisconnectText(String reason) {
        MutableText t = Text.literal("[EventLog] ").append(Text.literal(reason));

        if (screenshotPreDisconnect.get()) {
            String fn = EventLogShots.lastPreScreenshotFileName;
            if (fn != null && !fn.isBlank()) {
                t.append(Text.literal("\nScreenshot: " + fn));
            }
        }

        return t;
    }

    // =========================
    // Compact reason builders (ONE line)
    // =========================

    private String buildHealthReason(float hp) {
        float abs = mc.player.getAbsorptionAmount();
        float max = mc.player.getMaxHealth();
        return "Health low: hp=" + fmt1(hp) + "/" + fmt1(max) + ", abs=" + fmt1(abs) + ", thr=" + health.get() + ".";
    }

    private String buildHealthPredictedReason(float hp, float abs, float incoming, float effective) {
        float max = mc.player.getMaxHealth();
        return "Health predicted: hp=" + fmt1(hp) + "/" + fmt1(max) + ", abs=" + fmt1(abs) + ", inc~" + fmt1(incoming)
            + ", eff=" + fmt1(effective) + " (< " + health.get() + ").";
    }

    private String buildTotemReason(int pops) {
        return "Totem pops: " + pops + " (thr " + totemPops.get() + ").";
    }

    private String buildPlayerReason(PlayerEntity p) {
        double d = mc.player.distanceTo(p);
        return "Player: " + p.getName().getString() + ", uuid=" + p.getUuid() + ", dist=" + fmt1(d)
            + (ignoreFriends.get() ? " (ignore-friends ON)." : ".");
    }

    private String buildEntitiesTotalReason(int total, Entity nearest, double nearestDist) {
        String near = (nearest != null) ? (", near=" + Registries.ENTITY_TYPE.getId(nearest.getType()) + "@" + fmt1(nearestDist)) : "";
        return "Entities: total=" + total + " (>= " + combinedEntityThreshold.get() + "), r=" + entityRange.get()
            + ", top=" + topEntityBreakdown(2) + near + ".";
    }

    private String buildEntitiesIndividualReason(EntityType<?> type, int count, int total, Entity nearest, double nearestDist) {
        String id = Registries.ENTITY_TYPE.getId(type).toString();
        String near = (nearest != null) ? (", near=" + Registries.ENTITY_TYPE.getId(nearest.getType()) + "@" + fmt1(nearestDist)) : "";
        return "Entities: " + id + " x" + count + " (>= " + individualEntityThreshold.get() + "), total=" + total + ", r=" + entityRange.get()
            + ", top=" + topEntityBreakdown(2) + near + ".";
    }

    private String buildArmorReason(ItemStack armorPiece, double percentUndamaged, int left, int max) {
        String item = armorPiece.getItem().toString(); // e.g. minecraft:diamond_chestplate
        return "Armor low: " + item + " " + fmt1(percentUndamaged) + "% (" + left + "/" + max + "), thr=" + fmt1(armorPercent.get()) + "%.";
    }

    private String topEntityBreakdown(int maxItems) {
        if (entityCounts.isEmpty()) return "(none)";

        var list = new ArrayList<Object2IntMap.Entry<EntityType<?>>>();
        for (Object2IntMap.Entry<EntityType<?>> e : entityCounts.object2IntEntrySet()) list.add(e);

        list.sort(Comparator.comparingInt(Object2IntMap.Entry<EntityType<?>>::getIntValue).reversed());

        StringBuilder sb = new StringBuilder();
        int n = Math.min(maxItems, list.size());
        for (int i = 0; i < n; i++) {
            var e = list.get(i);
            String id = Registries.ENTITY_TYPE.getId(e.getKey()).toString();
            if (i > 0) sb.append(",");
            sb.append(id).append("x").append(e.getIntValue());
        }
        return sb.toString();
    }

    // =========================
    // HUD info
    // =========================

    @Override
    public String getInfoString() {
        StringBuilder sb = new StringBuilder();

        if (logOnHealth.get()) {
            if (sb.length() > 0) sb.append(", ");
            sb.append("hp<=").append(health.get());
            if (predictIncomingDamage.get()) sb.append("(pred)");
        }

        if (logOnTotem.get()) {
            if (sb.length() > 0) sb.append(", ");
            sb.append("totem").append(totemPops.get());
        }

        if (logOnPlayer.get()) {
            if (sb.length() > 0) sb.append(", ");
            sb.append("player");
            if (ignoreFriends.get()) sb.append("(noF)");
        }

        if (logOnEntities.get()) {
            if (sb.length() > 0) sb.append(", ");
            sb.append("ent");
            int n = entities.get() == null ? 0 : entities.get().size();
            sb.append(n);
            if (useTotalCount.get()) sb.append(" tot>=").append(combinedEntityThreshold.get());
            else sb.append(" each>=").append(individualEntityThreshold.get());
            sb.append(" r").append(entityRange.get());
        }

        if (logOnY.get()) {
            if (sb.length() > 0) sb.append(", ");
            sb.append("y").append(fmt0(yLevel.get()));
        }
        if (logOnVy.get()) {
            if (sb.length() > 0) sb.append(", ");
            sb.append("Vy").append(fmt0(vyThreshold.get()));
        }
        if (logPortal.get()) {
            if (sb.length() > 0) sb.append(", ");
            sb.append("portal").append(portalTicks.get());
        }
        if (logArmor.get()) {
            if (sb.length() > 0) sb.append(", ");
            sb.append("armor<").append(fmt0(armorPercent.get())).append("%");
            if (ignoreElytra.get()) sb.append("(noE)");
        }
        if (serverNotResponding.get()) {
            if (sb.length() > 0) sb.append(", ");
            sb.append("snr").append(fmt0(serverNotRespondingSecs.get())).append("s");
            if (reconnectAfterNotResponding.get()) sb.append("->rc").append(fmt0(secondsToReconnect.get())).append("s");
        }
        if (logPosition.get()) {
            if (sb.length() > 0) sb.append(", ");
            BlockPos p = position.get();
            sb.append("pos(").append(p.getX()).append(",").append(p.getZ()).append(")");
            sb.append(" r").append(fmt0(distance.get()));
        }

        return sb.length() == 0 ? null : sb.toString();
    }

    // =========================
    // Formatting helpers
    // =========================

    private static String fmt0(double v) {
        if (Math.abs(v - Math.rint(v)) < 1e-9) return Long.toString(Math.round(v));
        return String.format(Locale.ROOT, "%.1f", v);
    }

    private static String fmt1(double v) {
        return String.format(Locale.ROOT, "%.1f", v);
    }

    private static String fmt2(double v) {
        return String.format(Locale.ROOT, "%.2f", v);
    }

    // =========================
    // Screenshot helper
    // =========================

    public static final class EventLogShots {
        private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH.mm.ss");
        public static volatile String lastPreScreenshotFileName = null;

        /** .minecraft root (prefer MinecraftClient.runDirectory; fallback FabricLoader gameDir) */
        public static File getGameDir() {
            try {
                File runDir = MinecraftClient.getInstance().runDirectory;
                if (runDir != null) return runDir;
            } catch (Throwable ignored) {}

            try {
                return FabricLoader.getInstance().getGameDir().toFile();
            } catch (Throwable ignored) {}

            return new File(".");
        }

        /** .minecraft/MilkyMod/EventLog */
        public static File getEventLogDir() {
            File dir = getGameDir().toPath()
                .resolve("MilkyMod")
                .resolve("EventLog")
                .toFile();
            dir.mkdirs();
            return dir;
        }

        public static void capturePreDisconnect(MinecraftClient mc) {
            try {
                File dirFile = getEventLogDir(); // ensure folder exists
                Path dir = dirFile.toPath();

                String base = "pre_disconnect_" + LocalDateTime.now().format(FMT);
                Path out = dir.resolve(base + ".png");
                int i = 1;
                while (Files.exists(out) && i < 10_000) {
                    out = dir.resolve(base + "_" + i + ".png");
                    i++;
                }

                try (NativeImage img = ScreenshotRecorder.takeScreenshot(mc.getFramebuffer())) {
                    img.writeTo(out);
                }

                lastPreScreenshotFileName = "MilkyMod/EventLog/" + out.getFileName().toString();
            } catch (Throwable t) {
                lastPreScreenshotFileName = null;
            }
        }
    }
}
