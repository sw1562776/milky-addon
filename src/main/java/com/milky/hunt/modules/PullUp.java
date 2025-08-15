package com.milky.hunt.modules;

import com.milky.hunt.Addon;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.player.FindItemResult;
import meteordevelopment.meteorclient.utils.player.InvUtils;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.network.packet.c2s.play.ClientCommandC2SPacket;
import net.minecraft.network.packet.c2s.play.PlayerMoveC2SPacket;
import net.minecraft.util.Hand;

import java.lang.reflect.Method;
import java.util.Objects;

public class PullUp extends Module {
    private final SettingGroup sg = settings.getDefaultGroup();

    // Angles / heights
    private final Setting<Double> startPitch = sg.add(new DoubleSetting.Builder()
        .name("takeoff-pitch")
        .description("Pitch for near-vertical takeoff (degrees).")
        .defaultValue(-88.0).min(-90).max(90).sliderRange(-90, 90).build());

    private final Setting<Double> switchPitchAtY = sg.add(new DoubleSetting.Builder()
        .name("switch-pitch-at-y")
        .description("After reaching this Y, switch to cruise pitch.")
        .defaultValue(320.0).min(0).max(2000).sliderRange(0, 640).build());

    private final Setting<Double> cruisePitch = sg.add(new DoubleSetting.Builder()
        .name("cruise-pitch")
        .description("Pitch after passing the switch Y.")
        .defaultValue(-40.0).min(-90).max(90).sliderRange(-90, 90).build());

    private final Setting<Double> targetY = sg.add(new DoubleSetting.Builder()
        .name("target-y")
        .description("Stop when reaching this Y.")
        .defaultValue(800.0).min(0).max(4096).sliderRange(64, 1200).build());

    // Timing
    private final Setting<Integer> fireworkInterval = sg.add(new IntSetting.Builder()
        .name("firework-interval-ticks")
        .description("Ticks between firework uses while boosting.")
        .defaultValue(12).min(5).max(40).sliderRange(5, 30).build());

    private final Setting<Integer> minAirTicks = sg.add(new IntSetting.Builder()
        .name("min-air-ticks")
        .description("Minimum ticks airborne before attempting to start gliding.")
        .defaultValue(3).min(0).max(20).sliderRange(0, 10).build());

    private final Setting<Integer> minFallTicks = sg.add(new IntSetting.Builder()
        .name("min-fall-ticks")
        .description("Minimum falling ticks before attempting to start gliding.")
        .defaultValue(2).min(0).max(20).sliderRange(0, 10).build());

    private final Setting<Integer> startFlySpamTicks = sg.add(new IntSetting.Builder()
        .name("start-fly-spam-ticks")
        .description("How many consecutive ticks to send START_FALL_FLYING when trying to glide.")
        .defaultValue(8).min(1).max(40).sliderRange(2, 20).build());

    private final Setting<Integer> firstBoostDelay = sg.add(new IntSetting.Builder()
        .name("first-boost-delay")
        .description("Ticks to wait after entering glide before the first firework.")
        .defaultValue(2).min(0).max(20).sliderRange(0, 10).build());

    // Behavior
    private final Setting<Boolean> smoothRotate = sg.add(new BoolSetting.Builder()
        .name("smooth-rotate")
        .description("Gently rotate each tick.")
        .defaultValue(true).build());

    private final Setting<Double> rotateStep = sg.add(new DoubleSetting.Builder()
        .name("rotate-step")
        .description("Max pitch step per tick when smoothing.")
        .defaultValue(4.0).min(0.5).max(20).sliderRange(1, 10).visible(smoothRotate::get).build());

    private final Setting<Boolean> keepMainhandRocket = sg.add(new BoolSetting.Builder()
        .name("always-mainhand-rocket")
        .description("Ensure rockets stay in MAIN hand.")
        .defaultValue(true).build());

    private enum Phase { INIT, EQUIP, ALIGN, JUMP, START_FLY, BOOST, DONE }
    private Phase phase;
    private int ticksInPhase;
    private int fireworkCd;
    private int savedSlot = -1;

    // Air/glide tracking
    private int airTicks;
    private int fallTicks;
    private int startFlySpamLeft;
    private int afterGlideDelay;

    public PullUp() {
        super(Addon.CATEGORY, "PullUp", "Vertical Elytra auto-takeoff using MAIN-HAND rockets, climb, then level out.");
    }

    @Override
    public void onActivate() {
        phase = Phase.INIT;
        ticksInPhase = 0;
        fireworkCd = 0;
        airTicks = 0;
        fallTicks = 0;
        startFlySpamLeft = 0;
        afterGlideDelay = 0;
        if (mc.player != null) savedSlot = mc.player.getInventory().selectedSlot;
    }

    @Override
    public void onDeactivate() {
        ticksInPhase = 0;
        fireworkCd = 0;
        airTicks = 0;
        fallTicks = 0;
        startFlySpamLeft = 0;
        afterGlideDelay = 0;
        if (mc.player != null && !keepMainhandRocket.get() && savedSlot >= 0 && savedSlot < 9) {
            mc.player.getInventory().selectedSlot = savedSlot;
        }
    }

    @EventHandler
    private void onTick(TickEvent.Pre e) {
        if (mc.player == null || mc.world == null) return;

        ticksInPhase++;
        if (fireworkCd > 0) fireworkCd--;

        switch (phase) {
            case INIT -> {
                if (!hasElytra()) { info("[PullUp] No Elytra."); toggle(); return; }
                if (!hasRockets()) { info("[PullUp] No Firework Rocket."); toggle(); return; }
                phase = Phase.EQUIP; ticksInPhase = 0;
            }

            case EQUIP -> {
                equipElytraIfNeeded();
                ensureRocketInMainHand();
                if (ticksInPhase >= 2) { phase = Phase.ALIGN; ticksInPhase = 0; }
            }

            case ALIGN -> {
                facePitch(targetPitchForNow());
                if (near(mc.player.getPitch(), startPitch.get(), 1.0)) { phase = Phase.JUMP; ticksInPhase = 0; }
            }

            case JUMP -> {
                // Track airborne/falling
                if (mc.player.isOnGround()) {
                    mc.player.jump();
                    airTicks = 0;
                    fallTicks = 0;
                } else {
                    airTicks++;
                    if (mc.player.getVelocity().y < -0.02) fallTicks++; else fallTicks = 0;
                    if (airTicks >= minAirTicks.get() && fallTicks >= minFallTicks.get()) {
                        phase = Phase.START_FLY; ticksInPhase = 0;
                        startFlySpamLeft = startFlySpamTicks.get();
                    }
                }
            }

            case START_FLY -> {
                // Re-check air/fall; if we accidentally touched ground, go back to JUMP
                if (mc.player.isOnGround()) { phase = Phase.JUMP; ticksInPhase = 0; break; }

                // Spam START_FALL_FLYING for a few ticks while falling
                if (!isPlayerGliding(mc.player)) {
                    if (mc.player.getVelocity().y < -0.02 && startFlySpamLeft > 0) {
                        sendStartFallFlying();
                        startFlySpamLeft--;
                    }
                }

                if (isPlayerGliding(mc.player)) {
                    afterGlideDelay = firstBoostDelay.get();
                    phase = Phase.BOOST; ticksInPhase = 0;
                } else if (ticksInPhase > 40) { // 2s fallback
                    phase = Phase.JUMP; ticksInPhase = 0;
                }
            }

            case BOOST -> {
                facePitch(targetPitchForNow());
                ensureRocketInMainHand();

                if (mc.player.getY() >= targetY.get()) {
                    facePitch(0);
                    phase = Phase.DONE; ticksInPhase = 0;
                    break;
                }

                if (!isPlayerGliding(mc.player)) {
                    // try to recover gliding if we lost it
                    if (!mc.player.isOnGround()) sendStartFallFlying();
                    break;
                }

                if (afterGlideDelay > 0) {
                    afterGlideDelay--;
                } else {
                    if (fireworkCd == 0) {
                        useFireworkMainHand();
                        fireworkCd = fireworkInterval.get();
                    }
                }
            }

            case DONE -> {
                if (ticksInPhase >= 5) toggle();
            }
        }
    }

    // helpers

    private boolean hasElytra() {
        return InvUtils.find(Items.ELYTRA).found();
    }

    private boolean hasRockets() {
        FindItemResult r = InvUtils.find(Items.FIREWORK_ROCKET);
        return r.found() && r.count() > 0;
    }

    private void equipElytraIfNeeded() {
        ItemStack chest = mc.player.getInventory().getArmorStack(2);
        if (chest.isOf(Items.ELYTRA)) return;
        FindItemResult ely = InvUtils.find(Items.ELYTRA);
        if (ely.found()) InvUtils.move().from(ely.slot()).toArmor(2); // 0 boots, 1 legs, 2 chest, 3 head
    }

    private void ensureRocketInMainHand() {
        if (!keepMainhandRocket.get()) return;
        if (mc.player.getMainHandStack().isOf(Items.FIREWORK_ROCKET)) return;

        FindItemResult hotbar = InvUtils.findInHotbar(Items.FIREWORK_ROCKET);
        if (hotbar.found()) {
            InvUtils.swap(hotbar.slot(), false);
            return;
        }

        FindItemResult any = InvUtils.find(Items.FIREWORK_ROCKET);
        if (any.found()) {
            InvUtils.move().from(any.slot()).toHotbar(mc.player.getInventory().selectedSlot);
        }
    }

    private void facePitch(double wantedPitch) {
        float yaw = mc.player.getYaw();
        if (!smoothRotate.get()) {
            sendLookPacket(yaw, (float) wantedPitch);
            return;
        }
        float cur = mc.player.getPitch();
        double step = rotateStep.get();
        double next = Math.abs(wantedPitch - cur) <= step ? wantedPitch : cur + Math.copySign(step, wantedPitch - cur);
        sendLookPacket(yaw, (float) next);
    }

    private double targetPitchForNow() {
        double y = mc.player.getY();
        return (y >= switchPitchAtY.get() ? cruisePitch.get() : startPitch.get());
    }

    private void useFireworkMainHand() {
        if (!mc.player.getMainHandStack().isOf(Items.FIREWORK_ROCKET)) return;
        mc.interactionManager.interactItem(mc.player, Hand.MAIN_HAND);
    }

    private void sendStartFallFlying() {
        mc.getNetworkHandler().sendPacket(new ClientCommandC2SPacket(mc.player, ClientCommandC2SPacket.Mode.START_FALL_FLYING));
    }

    private void sendLookPacket(float yaw, float pitch) {
        boolean onGround = mc.player.isOnGround();
        boolean horizontalCollision = mc.player.horizontalCollision;
        mc.getNetworkHandler().sendPacket(new PlayerMoveC2SPacket.LookAndOnGround(yaw, pitch, onGround, horizontalCollision));
        mc.player.setYaw(yaw);
        mc.player.setPitch(pitch);
    }

    private static boolean near(double a, double b, double eps) { return Math.abs(a - b) <= eps; }

    // Version-agnostic gliding check
    private boolean isPlayerGliding(ClientPlayerEntity p) {
        try {
            Method m = p.getClass().getMethod("isGliding");
            Object r = m.invoke(p);
            if (r instanceof Boolean) return (Boolean) r;
        } catch (Throwable ignored) {}
        try {
            Method m = p.getClass().getMethod("isFallFlying");
            Object r = m.invoke(p);
            if (r instanceof Boolean) return (Boolean) r;
        } catch (Throwable ignored) {}
        try {
            String pose = Objects.toString(p.getPose().name(), "");
            return pose.equals("GLIDING") || pose.equals("FALL_FLYING");
        } catch (Throwable ignored) {}
        return false;
    }

    @Override
    public String getInfoString() {
        if (mc.player == null) return null;
        String s = String.format("Y=%.1f/%.0f", mc.player.getY(), targetY.get());
        if (phase == Phase.ALIGN || phase == Phase.JUMP || phase == Phase.START_FLY || phase == Phase.BOOST) {
            s += " | cd=" + fireworkCd + "t";
        }
        return s;
    }
}
