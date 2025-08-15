package com.milky.hunt.modules;

import com.milky.hunt.Addon;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.player.FindItemResult;
import meteordevelopment.meteorclient.utils.player.InvUtils;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.network.packet.c2s.play.ClientCommandC2SPacket;
import net.minecraft.util.Hand;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Objects;

public class PullUp extends Module {
    private final SettingGroup sg = settings.getDefaultGroup();

    // Angles / heights
    private final Setting<Double> startPitch = sg.add(new DoubleSetting.Builder()
        .name("takeoff-pitch").description("Pitch for near-vertical takeoff.")
        .defaultValue(-86.0).min(-90).max(90).sliderRange(-90, 90).build());

    private final Setting<Double> switchPitchAtY = sg.add(new DoubleSetting.Builder()
        .name("switch-pitch-at-y").description("After reaching this Y, switch to angled climb.")
        .defaultValue(320.0).min(0).max(2000).sliderRange(0, 640).build());

    private final Setting<Double> cruisePitch = sg.add(new DoubleSetting.Builder()
        .name("angled-pitch").description("Pitch during angled climb (after switch height).")
        .defaultValue(-40.0).min(-90).max(90).sliderRange(-90, 90).build());

    private final Setting<Double> targetY = sg.add(new DoubleSetting.Builder()
        .name("target-y").description("Stop when reaching this Y.")
        .defaultValue(800.0).min(0).max(4096).sliderRange(64, 10000).build());

    // Vertical & angled-climb cadence (no hyper-spam)
    private final Setting<Integer> preSpamTicks = sg.add(new IntSetting.Builder()
        .name("pre-spam-ticks").description("Ticks to press fireworks BEFORE jumping.")
        .defaultValue(0).min(0).max(40).sliderRange(0, 20).build());

    private final Setting<Integer> verticalInterval = sg.add(new IntSetting.Builder()
        .name("vertical-interval-ticks").description("Ticks between fireworks during vertical climb (< switch Y).")
        .defaultValue(8).min(1).max(30).sliderRange(4, 16).build());

    private final Setting<Integer> cruiseInterval = sg.add(new IntSetting.Builder()
        .name("angled-interval-ticks").description("Ticks between fireworks during angled climb (≥ switch Y).")
        .defaultValue(12).min(3).max(500).sliderRange(6, 20).build());

    // Reacquire gliding strictly when falling
    private final Setting<Integer> reacquireEveryTicks = sg.add(new IntSetting.Builder()
        .name("reacquire-every-ticks").description("When airborne & NOT gliding & falling, try START_FALL_FLYING every N ticks.")
        .defaultValue(2).min(1).max(20).sliderRange(1, 10).build());

    // Behavior
    private final Setting<Boolean> smoothRotate = sg.add(new BoolSetting.Builder()
        .name("smooth-rotate").description("Client-side smooth pitch.")
        .defaultValue(true).build());

    private final Setting<Double> rotateStep = sg.add(new DoubleSetting.Builder()
        .name("rotate-step").description("Max pitch step per tick when smoothing.")
        .defaultValue(3.5).min(0.5).max(20).sliderRange(1, 10).visible(smoothRotate::get).build());

    private final Setting<Boolean> keepMainhandRocket = sg.add(new BoolSetting.Builder()
        .name("always-mainhand-rocket").description("Ensure rockets stay in MAIN hand.")
        .defaultValue(true).build());

    private final Setting<Boolean> nukeRightClickDelay = sg.add(new BoolSetting.Builder()
        .name("nuke-right-click-delay").description("Force client right-click delay to 0 before firing.")
        .defaultValue(false).build());

    private enum Phase { INIT, EQUIP, ALIGN, PRE_SPAM, JUMP, VERTICAL, CRUISE, DONE }
    private Phase phase;
    private int ticksInPhase;
    private int savedSlot = -1;

    private int verticalCd;
    private int cruiseCd;
    private int reacquireCd;

    private boolean gliding;      // current
    private boolean glidingPrev;  // previous tick

    public PullUp() {
        super(Addon.CATEGORY, "PullUp", "Jump, keep Elytra open, use rockets on a sane cadence to climb vertically, then transition to an angled climb.");
    }

    @Override
    public void onActivate() {
        phase = Phase.INIT;
        ticksInPhase = 0;
        verticalCd = 0;
        cruiseCd = 0;
        reacquireCd = 0;
        gliding = glidingPrev = false;
        if (mc.player != null) savedSlot = mc.player.getInventory().selectedSlot;
    }

    @Override
    public void onDeactivate() {
        ticksInPhase = 0;
        verticalCd = 0;
        cruiseCd = 0;
        reacquireCd = 0;
        gliding = glidingPrev = false;
        if (mc.player != null && !keepMainhandRocket.get() && savedSlot >= 0 && savedSlot < 9) {
            mc.player.getInventory().selectedSlot = savedSlot;
        }
    }

    @EventHandler
    private void onTick(TickEvent.Pre e) {
        if (mc.player == null || mc.world == null) return;

        ticksInPhase++;
        if (verticalCd > 0) verticalCd--;
        if (cruiseCd > 0) cruiseCd--;
        if (reacquireCd > 0) reacquireCd--;

        glidingPrev = gliding;
        gliding = isPlayerGliding(mc.player);

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
                facePitch(startPitch.get());
                ensureRocketInMainHand();
                if (near(mc.player.getPitch(), startPitch.get(), 1.0)) {
                    phase = preSpamTicks.get() > 0 ? Phase.PRE_SPAM : Phase.JUMP;
                    ticksInPhase = 0;
                }
            }

            case PRE_SPAM -> {
                ensureRocketInMainHand();
                // light pre-spam (not required; default 0)
                if (verticalCd == 0) { fireIfReady(); verticalCd = Math.max(1, verticalInterval.get()); }
                if (ticksInPhase >= preSpamTicks.get()) { phase = Phase.JUMP; ticksInPhase = 0; }
            }

            case JUMP -> {
                if (mc.player.isOnGround()) mc.player.jump();
                // reset counters entering vertical
                verticalCd = 0;
                reacquireCd = 0;
                gliding = glidingPrev = false;
                phase = Phase.VERTICAL; ticksInPhase = 0;
            }

            case VERTICAL -> {
                facePitch(startPitch.get());
                ensureRocketInMainHand();

                boolean airborne = !mc.player.isOnGround();
                double vy = mc.player.getVelocity().y;
                boolean falling = vy < -0.02;

                // reacquire only when airborne, not gliding, and FALLING
                if (airborne && !gliding && falling && reacquireCd == 0) {
                    sendStartFallFlying();
                    reacquireCd = Math.max(1, reacquireEveryTicks.get());
                }

                // on first successful glide, fire immediately to lock state,
                // then continue with a sane vertical cadence
                if (!glidingPrev && gliding) {
                    fireIfReady();
                    verticalCd = Math.max(1, verticalInterval.get());
                } else if (gliding && verticalCd == 0) {
                    fireIfReady();
                    verticalCd = Math.max(1, verticalInterval.get());
                }

                if (mc.player.getY() >= switchPitchAtY.get()) {
                    phase = Phase.CRUISE; ticksInPhase = 0;
                }
            }

            case CRUISE -> {
                facePitch(cruisePitch.get());
                ensureRocketInMainHand();

                if (mc.player.getY() >= targetY.get()) {
                    facePitch(0);
                    phase = Phase.DONE; ticksInPhase = 0;
                    break;
                }

                boolean airborne = !mc.player.isOnGround();
                double vy = mc.player.getVelocity().y;

                // reacquire in angled climb only when FALLING
                if (airborne && !gliding && vy < -0.02 && reacquireCd == 0) {
                    sendStartFallFlying();
                    reacquireCd = Math.max(1, reacquireEveryTicks.get());
                }

                if (gliding && cruiseCd == 0) {
                    fireIfReady();
                    cruiseCd = Math.max(1, cruiseInterval.get());
                }
            }

            case DONE -> {
                if (ticksInPhase >= 5) toggle();
            }
        }
    }

    // --- helpers ---

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
        float current = mc.player.getPitch();
        float next;
        if (!smoothRotate.get()) {
            next = (float) wantedPitch;
        } else {
            double step = rotateStep.get();
            next = (float) (Math.abs(wantedPitch - current) <= step ? wantedPitch
                : current + Math.copySign(step, wantedPitch - current));
        }
        mc.player.setPitch(next); // client-side only
    }

    private void fireIfReady() {
        ItemStack stack = mc.player.getMainHandStack();
        if (!stack.isOf(Items.FIREWORK_ROCKET)) return;

        if (nukeRightClickDelay.get()) {
            try {
                Field f = MinecraftClient.class.getDeclaredField("itemUseCooldown");
                f.setAccessible(true);
                f.setInt(mc, 0);
            } catch (Throwable ignored) {}
        }

        // No ItemCooldownManager check: let the server decide
        mc.interactionManager.interactItem(mc.player, Hand.MAIN_HAND);
    }

    private void sendStartFallFlying() {
        mc.getNetworkHandler().sendPacket(new ClientCommandC2SPacket(mc.player, ClientCommandC2SPacket.Mode.START_FALL_FLYING));
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
        if (phase == Phase.VERTICAL) s += " | vInt=" + verticalInterval.get();
        if (phase == Phase.CRUISE) s += " | aInt=" + cruiseInterval.get();
        return s;
    }
}
