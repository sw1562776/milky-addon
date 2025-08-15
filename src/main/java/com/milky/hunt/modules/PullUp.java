package com.milky.hunt.modules;

import com.milky.hunt.Addon;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.player.FindItemResult;
import meteordevelopment.meteorclient.utils.player.InvUtils;
import meteordevelopment.orbit.EventHandler;
import meteordevelopment.orbit.EventPriority;
import net.minecraft.client.network.ClientPlayNetworkHandler;
import net.minecraft.entity.EntityPose;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.network.packet.c2s.play.ClientCommandC2SPacket;
import net.minecraft.util.Hand;

import java.util.Random;

public class PullUp extends Module {
    // ----- Settings groups -----
    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgTiming  = settings.createGroup("timing");
    private final SettingGroup sgAntiAc  = settings.createGroup("anti-cheat");

    // ----- General -----
    private final Setting<Double> targetY = sgGeneral.add(new DoubleSetting.Builder()
        .name("target-y")
        .description("Stop at this altitude.")
        .defaultValue(512.0)
        .min(65.0).sliderRange(160, 1024)
        .build());

    private final Setting<Integer> switchPitchAtY = sgGeneral.add(new IntSetting.Builder()
        .name("switch-pitch-at-y")
        .description("Above this Y, turn to cruise pitch.")
        .defaultValue(320)
        .min(100).sliderRange(200, 600)
        .build());

    private final Setting<Double> takeoffPitch = sgGeneral.add(new DoubleSetting.Builder()
        .name("takeoff-pitch")
        .description("-90 = straight up.")
        .defaultValue(-89.0).min(-90).max(90)
        .build());

    private final Setting<Double> cruisePitch = sgGeneral.add(new DoubleSetting.Builder()
        .name("cruise-pitch")
        .description("Pitch after switching altitude.")
        .defaultValue(-20.0).min(-90).max(90)
        .build());

    private final Setting<Boolean> autoEquipElytra = sgGeneral.add(new BoolSetting.Builder()
        .name("auto-equip-elytra")
        .description("Move Elytra to chest slot automatically.")
        .defaultValue(true)
        .build());

    private final Setting<Boolean> keepRocketsSelected = sgGeneral.add(new BoolSetting.Builder()
        .name("keep-rockets-selected")
        .description("Keep rockets selected during the whole climb.")
        .defaultValue(true)
        .build());

    // ----- Timing -----
    private final Setting<Integer> rocketInterval = sgTiming.add(new IntSetting.Builder()
        .name("rocket-interval-ticks")
        .description("Ticks between rocket uses while ascending.")
        .defaultValue(14).min(6).sliderRange(6, 40)
        .build());

    private final Setting<Integer> jitter = sgTiming.add(new IntSetting.Builder()
        .name("interval-jitter")
        .description("Random jitter (±ticks).")
        .defaultValue(2).min(0).sliderRange(0, 5)
        .build());

    private final Setting<Integer> startDelay = sgTiming.add(new IntSetting.Builder()
        .name("fall-flying-delay-ticks")
        .description("Ticks after leaving ground before START_FALL_FLYING.")
        .defaultValue(2).min(0).sliderRange(0, 10)
        .build());

    // ----- Anti-cheat-ish -----
    private final Setting<Boolean> spoofStartFallFlying = sgAntiAc.add(new BoolSetting.Builder()
        .name("spoof-start-fall-flying")
        .description("Send START_FALL_FLYING packet.")
        .defaultValue(true)
        .build());

    private final Setting<Integer> rotationSmoothing = sgAntiAc.add(new IntSetting.Builder()
        .name("rotation-smoothing-ticks")
        .description("Smoothly ease to target pitch (0 = snap).")
        .defaultValue(4).min(0).sliderRange(0, 10)
        .build());

    private final Setting<Boolean> levelPitchOnStop = sgAntiAc.add(new BoolSetting.Builder()
        .name("level-pitch-on-stop")
        .description("Set pitch to 0 at target altitude.")
        .defaultValue(true)
        .build());

    // ----- State -----
    private enum Phase { INIT, EQUIP, TAKEOFF_ALIGN, JUMP, START_FLY, BOOST, DONE }
    private Phase phase;

    private int airborneTicks;
    private int sinceRocket;
    private int currentInterval;
    private int savedSlot = -1;

    private float pitchFrom, pitchTo;
    private int  pitchLerpTicks, pitchLerpTotal;

    private final Random rng = new Random();

    public PullUp() {
        super(Addon.CATEGORY, "PullUp",
            "Auto vertical Elytra takeoff with MAIN-HAND rockets and climb to target altitude.");
    }

    @Override
    public void onActivate() {
        if (mc.player == null) { toggle(); return; }
        phase = Phase.INIT;
        airborneTicks = 0;
        sinceRocket   = 1000;
        currentInterval = withJitter(rocketInterval.get(), jitter.get());
        savedSlot = mc.player.getInventory().selectedSlot;
        resetPitchLerp();
    }

    @Override
    public void onDeactivate() {
        if (mc.player != null && !keepRocketsSelected.get() && savedSlot >= 0 && savedSlot < 9) {
            mc.player.getInventory().selectedSlot = savedSlot;
        }
        resetPitchLerp();
    }

    // ----- Helpers -----
    private void resetPitchLerp() { pitchLerpTicks = 0; pitchLerpTotal = 0; }

    private int withJitter(int base, int j) {
        if (j <= 0) return base;
        return base + rng.nextInt(j * 2 + 1) - j;
    }

    private boolean wearingElytra() {
        if (mc.player == null) return false;
        ItemStack chest = mc.player.getEquippedStack(EquipmentSlot.CHEST);
        return chest != null && chest.getItem() == Items.ELYTRA;
    }

    private boolean ensureElytraEquipped() {
        if (wearingElytra()) return true;
        FindItemResult ely = InvUtils.find(Items.ELYTRA);
        if (!ely.found()) return false;
        InvUtils.move().from(ely.slot()).toArmor(2);
        return wearingElytra();
    }

    private boolean haveAnyRockets() {
        return InvUtils.find(Items.FIREWORK_ROCKET).found();
    }

    private boolean ensureMainHandRockets() {
        if (mc.player.getMainHandStack().getItem() == Items.FIREWORK_ROCKET) return true;

        FindItemResult hotbar = InvUtils.findInHotbar(Items.FIREWORK_ROCKET);
        if (hotbar.found()) {
            InvUtils.swap(hotbar.slot(), false);
            return mc.player.getMainHandStack().getItem() == Items.FIREWORK_ROCKET;
        }

        FindItemResult any = InvUtils.find(Items.FIREWORK_ROCKET);
        if (any.found()) {
            InvUtils.move().from(any.slot()).toHotbar(mc.player.getInventory().selectedSlot);
            return mc.player.getMainHandStack().getItem() == Items.FIREWORK_ROCKET;
        }
        return false;
    }

    private void smoothPitchTo(double targetPitchDeg) {
        float current = mc.player.getPitch();
        float dest    = (float) targetPitchDeg;
        if (rotationSmoothing.get() <= 0) {
            mc.player.setPitch(dest);
            resetPitchLerp();
            return;
        }
        pitchFrom = current;
        pitchTo   = dest;
        pitchLerpTicks = 0;
        pitchLerpTotal = Math.max(1, rotationSmoothing.get());
    }

    private void tickPitchLerp() {
        if (pitchLerpTotal <= 0 || pitchLerpTicks >= pitchLerpTotal) return;
        float t = (pitchLerpTicks + 1) / (float) pitchLerpTotal;
        float v = pitchFrom + (pitchTo - pitchFrom) * t;
        mc.player.setPitch(v);
        pitchLerpTicks++;
    }

    private boolean isGliding() {
        return mc.player.getPose() == EntityPose.FALL_FLYING;
    }

    private boolean useRocketMainHand() {
        if (!ensureMainHandRockets()) return false;
        mc.interactionManager.interactItem(mc.player, Hand.MAIN_HAND);
        sinceRocket = 0;
        currentInterval = withJitter(rocketInterval.get(), jitter.get());
        return true;
    }

    private void tryStartFallFlying() {
        if (!spoofStartFallFlying.get()) return;
        ClientPlayNetworkHandler nh = mc.getNetworkHandler();
        if (nh != null) {
            nh.sendPacket(new ClientCommandC2SPacket(mc.player, ClientCommandC2SPacket.Mode.START_FALL_FLYING));
        }
    }

    // ----- Tick -----
    @EventHandler(priority = EventPriority.HIGHEST)
    private void onTick(TickEvent.Pre e) {
        if (mc.player == null || mc.world == null) { toggle(); return; }

        if (!haveAnyRockets()) { info("[PullUp] No rockets."); toggle(); return; }

        tickPitchLerp();
        double y = mc.player.getY();

        switch (phase) {
            case INIT -> {
                phase = autoEquipElytra.get() ? Phase.EQUIP : Phase.TAKEOFF_ALIGN;
            }
            case EQUIP -> {
                if (!ensureElytraEquipped()) { info("[PullUp] No Elytra."); toggle(); return; }
                phase = Phase.TAKEOFF_ALIGN;
            }
            case TAKEOFF_ALIGN -> {
                smoothPitchTo(takeoffPitch.get());
                if (!ensureMainHandRockets()) { info("[PullUp] No rockets to main hand."); toggle(); return; }
                phase = Phase.JUMP;
            }
            case JUMP -> {
                if (mc.player.isOnGround()) {
                    mc.player.jump();
                    airborneTicks = 0;
                } else {
                    airborneTicks++;
                }
                if (airborneTicks >= startDelay.get()) phase = Phase.START_FLY;
            }
            case START_FLY -> {
                if (!isGliding()) tryStartFallFlying();
                if (isGliding()) {
                    if (sinceRocket > 3) useRocketMainHand();
                    phase = Phase.BOOST;
                }
            }
            case BOOST -> {
                if (y >= switchPitchAtY.get()) smoothPitchTo(cruisePitch.get());
                else if (Math.abs(mc.player.getPitch() - takeoffPitch.get().floatValue()) > 2f) smoothPitchTo(takeoffPitch.get());

                if (isGliding()) {
                    sinceRocket++;
                    if (sinceRocket >= currentInterval) {
                        if (!useRocketMainHand()) { info("[PullUp] Out of rockets or not in main hand."); toggle(); return; }
                        if (!keepRocketsSelected.get() && savedSlot >= 0 && savedSlot < 9) {
                            InvUtils.swap(savedSlot, false);
                        }
                    }
                } else {
                    airborneTicks++;
                    if (airborneTicks >= startDelay.get()) tryStartFallFlying();
                }

                if (y >= targetY.get()) {
                    if (levelPitchOnStop.get()) smoothPitchTo(0.0);
                    phase = Phase.DONE;
                }
            }
            case DONE -> {
                if (rotationSmoothing.get() <= 0 || pitchLerpTicks >= pitchLerpTotal) toggle();
            }
        }
    }

    @Override
    public String getInfoString() {
        if (mc.player == null) return null;
        String h = String.format("Y=%.1f/%.0f", mc.player.getY(), targetY.get());
        if (phase == Phase.TAKEOFF_ALIGN || phase == Phase.JUMP || phase == Phase.START_FLY || phase == Phase.BOOST) {
            h += " | ⏱" + Math.max(0, currentInterval - sinceRocket) + "t";
        }
        return h;
    }
}
