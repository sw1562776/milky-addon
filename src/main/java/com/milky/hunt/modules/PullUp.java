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
import net.minecraft.util.Hand;

import java.lang.reflect.Method;
import java.util.Objects;

public class PullUp extends Module {
    private final SettingGroup sg = settings.getDefaultGroup();

    // Angles / heights
    private final Setting<Double> startPitch = sg.add(new DoubleSetting.Builder()
        .name("takeoff-pitch").description("Pitch for near-vertical takeoff.")
        .defaultValue(-88.0).min(-90).max(90).sliderRange(-90, 90).build());

    private final Setting<Double> switchPitchAtY = sg.add(new DoubleSetting.Builder()
        .name("switch-pitch-at-y").description("After reaching this Y, switch to cruise pitch and normal boosting.")
        .defaultValue(320.0).min(0).max(2000).sliderRange(0, 640).build());

    private final Setting<Double> cruisePitch = sg.add(new DoubleSetting.Builder()
        .name("cruise-pitch").description("Pitch after passing the switch Y.")
        .defaultValue(-40.0).min(-90).max(90).sliderRange(-90, 90).build());

    private final Setting<Double> targetY = sg.add(new DoubleSetting.Builder()
        .name("target-y").description("Stop when reaching this Y.")
        .defaultValue(800.0).min(0).max(4096).sliderRange(64, 1200).build());

    // High-frequency spam (vertical phase)
    private final Setting<Integer> preSpamTicks = sg.add(new IntSetting.Builder()
        .name("pre-spam-ticks").description("Ticks to spam fireworks BEFORE jumping.")
        .defaultValue(10).min(0).max(60).sliderRange(0, 30).build());

    private final Setting<Integer> spamEveryTicks = sg.add(new IntSetting.Builder()
        .name("spam-every-ticks").description("Firework press period during vertical phase.")
        .defaultValue(2).min(1).max(10).sliderRange(1, 5).build());

    private final Setting<Boolean> spamStartFallFlying = sg.add(new BoolSetting.Builder()
        .name("spam-start-fall-flying").description("Also spam START_FALL_FLYING while airborne.")
        .defaultValue(true).build());

    private final Setting<Integer> startFlySpamTicks = sg.add(new IntSetting.Builder()
        .name("start-fly-spam-ticks").description("Extra consecutive ticks to spam START_FALL_FLYING right after jump.")
        .defaultValue(12).min(0).max(60).sliderRange(0, 30).visible(spamStartFallFlying::get).build());

    // Cruise (post-320)
    private final Setting<Integer> cruiseInterval = sg.add(new IntSetting.Builder()
        .name("cruise-interval-ticks").description("Ticks between fireworks after switch Y.")
        .defaultValue(12).min(5).max(40).sliderRange(5, 30).build());

    // Behavior
    private final Setting<Boolean> smoothRotate = sg.add(new BoolSetting.Builder()
        .name("smooth-rotate").description("Client-side smooth pitch.")
        .defaultValue(true).build());

    private final Setting<Double> rotateStep = sg.add(new DoubleSetting.Builder()
        .name("rotate-step").description("Max pitch step per tick when smoothing.")
        .defaultValue(4.0).min(0.5).max(20).sliderRange(1, 10).visible(smoothRotate::get).build());

    private final Setting<Boolean> keepMainhandRocket = sg.add(new BoolSetting.Builder()
        .name("always-mainhand-rocket").description("Ensure rockets stay in MAIN hand.")
        .defaultValue(true).build());

    private enum Phase { INIT, EQUIP, ALIGN, PRE_SPAM, JUMP, SPAM_ASCENT, CRUISE, DONE }
    private Phase phase;
    private int ticksInPhase;
    private int cd;                // cooldown for cruise phase
    private int savedSlot = -1;
    private int startFlySpamLeft;

    public PullUp() {
        super(Addon.CATEGORY, "PullUp", "Pre-spam fireworks, jump, keep spamming until Y=320, then cruise to target.");
    }

    @Override
    public void onActivate() {
        phase = Phase.INIT;
        ticksInPhase = 0;
        cd = 0;
        startFlySpamLeft = 0;
        if (mc.player != null) savedSlot = mc.player.getInventory().selectedSlot;
    }

    @Override
    public void onDeactivate() {
        ticksInPhase = 0;
        cd = 0;
        startFlySpamLeft = 0;
        if (mc.player != null && !keepMainhandRocket.get() && savedSlot >= 0 && savedSlot < 9) {
            mc.player.getInventory().selectedSlot = savedSlot;
        }
    }

    @EventHandler
    private void onTick(TickEvent.Pre e) {
        if (mc.player == null || mc.world == null) return;

        ticksInPhase++;
        if (cd > 0) cd--;

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
                if (near(mc.player.getPitch(), startPitch.get(), 1.0)) { phase = Phase.PRE_SPAM; ticksInPhase = 0; }
            }

            case PRE_SPAM -> {
                ensureRocketInMainHand();
                if (ticksInPhase % Math.max(1, spamEveryTicks.get()) == 0) pressFirework();
                if (ticksInPhase >= preSpamTicks.get()) {
                    phase = Phase.JUMP; ticksInPhase = 0;
                }
            }

            case JUMP -> {
                if (mc.player.isOnGround()) mc.player.jump();
                startFlySpamLeft = spamStartFallFlying.get() ? startFlySpamTicks.get() : 0;
                phase = Phase.SPAM_ASCENT; ticksInPhase = 0;
            }

            case SPAM_ASCENT -> {
                facePitch(startPitch.get());
                ensureRocketInMainHand();

                boolean gliding = isPlayerGliding(mc.player);

                // Keep trying to enter/maintain gliding while airborne
                if (spamStartFallFlying.get() && !mc.player.isOnGround()) {
                    if (!gliding) {
                        sendStartFallFlying(); // continuous recovery attempts
                    } else if (startFlySpamLeft > 0) {
                        // optional extra spam window right after jump
                        sendStartFallFlying();
                        startFlySpamLeft--;
                    }
                }

                // Only use fireworks if actually gliding to ensure thrust applies
                if (gliding && ticksInPhase % Math.max(1, spamEveryTicks.get()) == 0) {
                    pressFirework();
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

                // Maintain gliding in cruise too; if lost, try to recover, and pause boosting until gliding resumes
                boolean gliding = isPlayerGliding(mc.player);
                if (!gliding && !mc.player.isOnGround() && spamStartFallFlying.get()) {
                    sendStartFallFlying();
                }

                if (gliding && cd == 0) {
                    pressFirework();
                    cd = cruiseInterval.get();
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

    private void pressFirework() {
        if (!mc.player.getMainHandStack().isOf(Items.FIREWORK_ROCKET)) return;
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
        if (phase == Phase.PRE_SPAM || phase == Phase.JUMP || phase == Phase.SPAM_ASCENT || phase == Phase.CRUISE) {
            s += " | spam=" + (phase == Phase.CRUISE ? ("every " + cruiseInterval.get() + "t") : (spamEveryTicks.get() + "t"));
        }
        return s;
    }
}
