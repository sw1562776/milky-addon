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

    // -------- Stage split & pitches --------
    private final Setting<Double> stage12Pitch = sg.add(new DoubleSetting.Builder()
        .name("stage12-pitch").description("Pitch for Stage 1 & 2 (vertical climb).")
        .defaultValue(-90.0).min(-90).max(0).sliderRange(-90, 90).build());

    private final Setting<Double> stage3Pitch = sg.add(new DoubleSetting.Builder()
        .name("stage3-pitch").description("Pitch for Stage 3 (slope / angled climb).")
        .defaultValue(-60.0).min(-90).max(0).sliderRange(-90, 90).build());

    private final Setting<Double> stage3StartY = sg.add(new DoubleSetting.Builder()
        .name("stage3-start-y").description("When reaching this Y, switch from Stage 2 to Stage 3 (slope).")
        .defaultValue(320.0).min(0).max(100000000).sliderRange(0, 512).build());

    private final Setting<Double> stage3TargetY = sg.add(new DoubleSetting.Builder()
        .name("stage3-target-y").description("Stop when reaching this Y in Stage 3 (slope).")
        .defaultValue(1000.0).min(0).max(100000000).sliderRange(64, 4096).build());

    // -------- Cadence --------
    private final Setting<Integer> preSpamTicks = sg.add(new IntSetting.Builder()
        .name("pre-spam-ticks").description("Ticks to press fireworks BEFORE jumping.")
        .defaultValue(10).min(0).max(40).sliderRange(0, 20).build());

    private final Setting<Integer> stage1Interval = sg.add(new IntSetting.Builder()
        .name("stage1-interval-ticks").description("Rocket interval (ticks) during Stage 1.")
        .defaultValue(3).min(1).max(30).sliderRange(1, 16).build());

    private final Setting<Double> stage2DeltaY = sg.add(new DoubleSetting.Builder()
        .name("stage2-start-delta-y").description("After rising this many blocks from takeoff, switch to Stage 2 cadence.")
        .defaultValue(50.0).min(5).max(500).sliderRange(10, 128).build());

    private final Setting<Integer> stage2Interval = sg.add(new IntSetting.Builder()
        .name("stage2-interval-ticks").description("Rocket interval (ticks) during Stage 2.")
        .defaultValue(10).min(1).max(60).sliderRange(6, 30).build());

    private final Setting<Integer> stage3Interval = sg.add(new IntSetting.Builder()
        .name("stage3-interval-ticks").description("Rocket interval (ticks) during Stage 3 (slope).")
        .defaultValue(20).min(3).max(500).sliderRange(6, 20).build());

    // -------- Glide reacquire (only when falling) --------
    private final Setting<Integer> reacquireEveryTicks = sg.add(new IntSetting.Builder()
        .name("reacquire-every-ticks").description("When airborne & NOT gliding & falling, try START_FALL_FLYING every N ticks.")
        .defaultValue(2).min(1).max(20).sliderRange(1, 10).build());

    // -------- Elytra durability guard --------
    private final Setting<Integer> minElytraDurability = sg.add(new IntSetting.Builder()
        .name("elytra-min-durability")
        .description("Minimum remaining durability required at takeoff. In flight, auto-swap to another Elytra if current drops below this.")
        .defaultValue(8).min(1).max(432).sliderRange(1, 432).build());

    // -------- Behavior --------
    private final Setting<Boolean> smoothRotate = sg.add(new BoolSetting.Builder()
        .name("smooth-rotate").description("Client-side smooth pitch.")
        .defaultValue(true).build());

    private final Setting<Double> rotateStep = sg.add(new DoubleSetting.Builder()
        .name("rotate-step").description("Max pitch step per tick when smoothing.")
        .defaultValue(3).min(0.5).max(20).sliderRange(1, 10).visible(smoothRotate::get).build());

    private final Setting<Boolean> keepMainhandRocket = sg.add(new BoolSetting.Builder()
        .name("always-mainhand-rocket").description("Ensure rockets stay in MAIN hand.")
        .defaultValue(true).build());

    private final Setting<Boolean> nukeRightClickDelay = sg.add(new BoolSetting.Builder()
        .name("nuke-right-click-delay").description("Force client right-click delay to 0 before firing.")
        .defaultValue(false).build());

    
    private enum Phase { INIT, EQUIP, ALIGN, PRE_SPAM, JUMP, VERTICAL, SLOPE, DONE }
    private Phase phase;
    private int ticksInPhase;
    private int savedSlot = -1;

    private int verticalCd;
    private int slopeCd;
    private int reacquireCd;

    private boolean gliding;
    private boolean glidingPrev;

    // mark Y when entering Stage 1 (VERTICAL)
    private double verticalBaseY;

    public PullUp() {
        super(Addon.MilkyModCategory, "PullUp",
            "Stage 1&2: vertical climb with two cadences; Stage 3: slope (angled) climb. Keeps Elytra open and uses rockets on a sane cadence.");
    }

    @Override
    public void onActivate() {
        phase = Phase.INIT;
        ticksInPhase = 0;
        verticalCd = 0;
        slopeCd = 0;
        reacquireCd = 0;
        gliding = glidingPrev = false;
        verticalBaseY = Double.NaN;
        if (mc.player != null) savedSlot = mc.player.getInventory().selectedSlot;
    }

    @Override
    public void onDeactivate() {
        ticksInPhase = 0;
        verticalCd = 0;
        slopeCd = 0;
        reacquireCd = 0;
        gliding = glidingPrev = false;
        verticalBaseY = Double.NaN;
        if (mc.player != null && !keepMainhandRocket.get() && savedSlot >= 0 && savedSlot < 9) {
            mc.player.getInventory().selectedSlot = savedSlot;
        }
    }

    @EventHandler
    private void onTick(TickEvent.Pre e) {
        if (mc.player == null || mc.world == null) return;

        ticksInPhase++;
        if (verticalCd > 0) verticalCd--;
        if (slopeCd > 0) slopeCd--;
        if (reacquireCd > 0) reacquireCd--;

        glidingPrev = gliding;
        gliding = isPlayerGliding(mc.player);

        switch (phase) {
            case INIT -> {
                if (!hasElytraMeetingThreshold()) { // must meet min durability
                    info("[PullUp] No Elytra meeting min durability (" + minElytraDurability.get() + ").");
                    toggle(); return;
                }
                if (!hasRockets()) { info("[PullUp] No Firework Rocket."); toggle(); return; }
                phase = Phase.EQUIP; ticksInPhase = 0;
            }

            case EQUIP -> {
                equipElytraIfNeeded();          // durability-aware
                ensureRocketInMainHand();
                if (ticksInPhase >= 2) { phase = Phase.ALIGN; ticksInPhase = 0; }
            }

            case ALIGN -> {
                facePitch(stage12Pitch.get()); // Stage 1&2 pitch
                ensureRocketInMainHand();
                equipElytraIfNeeded();

                if (near(mc.player.getPitch(), stage12Pitch.get(), 1.0)) {
                    phase = preSpamTicks.get() > 0 ? Phase.PRE_SPAM : Phase.JUMP;
                    ticksInPhase = 0;
                }
            }

            case PRE_SPAM -> {
                ensureRocketInMainHand();
                equipElytraIfNeeded();
                if (verticalCd == 0) { fireIfReady(); verticalCd = Math.max(1, stage1Interval.get()); }
                if (ticksInPhase >= preSpamTicks.get()) { phase = Phase.JUMP; ticksInPhase = 0; }
            }

            case JUMP -> {
                if (mc.player.isOnGround()) mc.player.jump();
                verticalCd = 0;
                reacquireCd = 0;
                gliding = glidingPrev = false;
                verticalBaseY = mc.player.getY(); // begin Stage 1
                phase = Phase.VERTICAL; ticksInPhase = 0;
            }

            case VERTICAL -> {
                facePitch(stage12Pitch.get()); // Stage 1 & Stage 2
                ensureRocketInMainHand();
                equipElytraIfNeeded();         // auto-swap in flight if below threshold

                boolean airborne = !mc.player.isOnGround();
                double vy = mc.player.getVelocity().y;
                boolean falling = vy < -0.02;

                if (airborne && !gliding && falling && reacquireCd == 0) {
                    sendStartFallFlying();
                    reacquireCd = Math.max(1, reacquireEveryTicks.get());
                }

                if (!glidingPrev && gliding) {
                    fireIfReady();
                    verticalCd = verticalCadence(); // Stage 1 or 2
                } else if (gliding && verticalCd == 0) {
                    fireIfReady();
                    verticalCd = verticalCadence(); // Stage 1 or 2
                }

                if (mc.player.getY() >= stage3StartY.get()) {
                    phase = Phase.SLOPE; ticksInPhase = 0; // enter slope (angled) climb
                }
            }

            case SLOPE -> { // Stage 3: slope / angled climb
                facePitch(stage3Pitch.get());
                ensureRocketInMainHand();
                equipElytraIfNeeded();        // keep swapping if needed

                if (mc.player.getY() >= stage3TargetY.get()) {
                    facePitch(0);
                    phase = Phase.DONE; ticksInPhase = 0;
                    break;
                }

                boolean airborne = !mc.player.isOnGround();
                double vy = mc.player.getVelocity().y;

                if (airborne && !gliding && vy < -0.02 && reacquireCd == 0) {
                    sendStartFallFlying();
                    reacquireCd = Math.max(1, reacquireEveryTicks.get());
                }

                if (gliding && slopeCd == 0) {
                    fireIfReady();
                    slopeCd = Math.max(1, stage3Interval.get());
                }
            }

            case DONE -> {
                if (ticksInPhase >= 5) toggle();
            }
        }
    }

    // --- helpers ---

    private boolean hasRockets() {
        FindItemResult r = InvUtils.find(Items.FIREWORK_ROCKET);
        return r.found() && r.count() > 0;
    }

    // Durability helpers
    private int remainingDurability(ItemStack s) {
        if (s == null || s.isEmpty() || !s.isOf(Items.ELYTRA)) return -1;
        return s.getMaxDamage() - s.getDamage();
    }

    private int findBestElytraSlotAbove(int minRemain) {
        var inv = mc.player.getInventory();
        int size = inv.size(); // main inventory + hotbar
        int best = -1, bestRem = -1;
        for (int i = 0; i < size; i++) {
            ItemStack st = inv.getStack(i);
            if (st.isOf(Items.ELYTRA)) {
                int rem = remainingDurability(st);
                if (rem >= minRemain && rem > bestRem) {
                    best = i; bestRem = rem;
                }
            }
        }
        return best;
    }

    private boolean hasElytraMeetingThreshold() {
        int min = minElytraDurability.get();
        ItemStack chest = mc.player.getInventory().getArmorStack(2);
        if (remainingDurability(chest) >= min) return true;
        return findBestElytraSlotAbove(min) != -1;
    }

    // Durability-aware equip (replaces old logic)
    private void equipElytraIfNeeded() {
        int min = minElytraDurability.get();
        ItemStack chest = mc.player.getInventory().getArmorStack(2);

        // If already wearing a good one, keep it
        if (remainingDurability(chest) >= min) return;

        // Otherwise, equip the best surviving Elytra
        int slot = findBestElytraSlotAbove(min);
        if (slot != -1) InvUtils.move().from(slot).toArmor(2);
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

    // --- cadence & stage helpers ---

    private int verticalCadence() {
        if (Double.isNaN(verticalBaseY)) return Math.max(1, stage1Interval.get());
        double rise = mc.player.getY() - verticalBaseY;
        int interval = (rise >= stage2DeltaY.get()) ? stage2Interval.get() : stage1Interval.get();
        return Math.max(1, interval);
    }

    private int currentStage() {
        if (phase == Phase.SLOPE) return 3;
        if (phase == Phase.VERTICAL) {
            double rise = Double.isNaN(verticalBaseY) ? 0.0 : (mc.player.getY() - verticalBaseY);
            return rise >= stage2DeltaY.get() ? 2 : 1;
        }
        return 0; // not in climb yet / done
    }

    @Override
    public String getInfoString() {
        if (mc.player == null) return null;
        int st = currentStage();
        String s = String.format("Y=%.1f/%.0f", mc.player.getY(), stage3TargetY.get());
        if (st == 1 || st == 2) s += " | S" + st + " int=" + verticalCadence();
        if (st == 3) s += " | S3 int=" + stage3Interval.get();
        return s;
    }
}
