package com.milky.hunt.modules;

import com.milky.hunt.Addon;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.systems.modules.Modules;
import meteordevelopment.meteorclient.systems.modules.misc.AutoReconnect;
import meteordevelopment.meteorclient.utils.player.SlotUtils;
import meteordevelopment.meteorclient.utils.world.TickRate;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.network.packet.s2c.common.DisconnectS2CPacket;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockPos;

public class EventLog extends Module {
    private final SettingGroup sgGeneral = settings.getDefaultGroup();

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

    public EventLog() {
        super(Addon.MilkyModCategory, "EventLog", "Provides some additional triggers to log out.");
    }

    private int currPortalTicks = 0;
    private double oldDelay;
    private boolean autoReconnectEnabled;
    private boolean waitingForReconnection = false;

    @Override
    public void onActivate() {
        currPortalTicks = 0;

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

    @EventHandler
    private void onTick(TickEvent.Post event) {
        // If in the 2b2t queue
        if (mc.player == null || mc.player.getAbilities().allowFlying) return;

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

                logOut("Server was not responding for " + serverNotRespondingSecs.get() + " seconds.",
                    !reconnectAfterNotResponding.get());
                return;
            }
        }

        if (logPortal.get() && mc.player.portalManager != null) {
            if (mc.player.portalManager.isInPortal()) {
                currPortalTicks++;
                if (currPortalTicks > portalTicks.get()) {
                    logOut("Player was in a portal for " + currPortalTicks + " ticks.", true);
                    return;
                }
            } else {
                currPortalTicks = 0;
            }
        }

        if (logOnY.get() && mc.player.getY() < yLevel.get()) {
            logOut("Player was at Y=" + mc.player.getY() + " which is below your limit of Y=" + yLevel.get(), true);
            return;
        }

        if (logOnVy.get()) {
            double vyPerTick = mc.player.getVelocity().y;
            double vyPerSecond = vyPerTick * 20.0;

            if (vyPerSecond <= vyThreshold.get()) {
                logOut("Vy=" + String.format("%.2f", vyPerSecond) + " b/s (<= " + vyThreshold.get() + " b/s).", true);
                return;
            }
        }


        if (logArmor.get()) {
            for (int i = 0; i < 4; i++) {
                ItemStack armorPiece = mc.player.getInventory().getStack(SlotUtils.ARMOR_START + i);

                if (ignoreElytra.get() && armorPiece.getItem() == Items.ELYTRA) continue;

                if (armorPiece.isDamageable()) {
                    int max = armorPiece.getMaxDamage();
                    int current = armorPiece.getDamage();
                    double percentUndamaged = 100 - ((double) current / max) * 100;

                    if (percentUndamaged < armorPercent.get()) {
                        logOut("You had low armor", true);
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
                logOut("Player was within " + distanceToTarget + " blocks of the target position.", true);
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

        if (illegalDisconnect.get()) {
            // upstream behavior kept
            mc.player.networkHandler.sendChatMessage(String.valueOf((char) 0));
        } else {
            mc.player.networkHandler.onDisconnect(
                new DisconnectS2CPacket(Text.literal("[EventLog] " + reason))
            );
        }
    }

@Override
public String getInfoString() {
    StringBuilder sb = new StringBuilder();
    if (logOnY.get()) {
        sb.append("y").append(fmt0(yLevel.get()));
    }
    if (logOnVy.get()) {
        if (sb.length() > 0) sb.append(", ");
        sb.append("Vy").append(fmt0(vyThreshold.get())); // b/s
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

private static String fmt0(double v) {
    if (Math.abs(v - Math.rint(v)) < 1e-9) return Long.toString(Math.round(v));
    return String.format(java.util.Locale.ROOT, "%.1f", v);
}


}
