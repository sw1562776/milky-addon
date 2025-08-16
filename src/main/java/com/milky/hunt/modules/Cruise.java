package com.milky.hunt.modules;

import com.milky.hunt.Addon;
import com.milky.hunt.Utils;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.EnumSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.systems.modules.Modules;
import meteordevelopment.meteorclient.systems.modules.movement.elytrafly.ElytraFly;
import meteordevelopment.meteorclient.systems.modules.movement.elytrafly.ElytraFlightModes;
import meteordevelopment.meteorclient.utils.player.FindItemResult;
import meteordevelopment.meteorclient.utils.player.InvUtils;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.item.Items;
import net.minecraft.util.Hand;

import static com.milky.hunt.Utils.firework;

public class Cruise extends Module {
    public enum Mode { POWERED, UNPOWERED }

    private final SettingGroup sg = settings.getDefaultGroup();

    private final Setting<Mode> mode = sg.add(new EnumSetting.Builder<Mode>()
        .name("mode")
        .description("")
        .defaultValue(Mode.UNPOWERED)
        .build()
    );
  
    private long lastRocketUse = 0L;
    private boolean launched = false;
    private double yTarget = -1;
    private float targetPitch = 0f;
  
    private int fireworkDelayMs = 4000;
    private boolean useManualY = false;
    private int manualYLevel = 256;
  
    private boolean autoBoundAdjust = true;
    private double boundGap = 60.0;
    private boolean autoFirework = true;
    private double velocityThreshold = -0.05;
    private int fireworkCooldownTicks = 10;

    private final Module elytraFly = Modules.get().get(ElytraFly.class);
    private ElytraFlightModes oldElytraFlyMode = null;
    @SuppressWarnings("unchecked")
    private Setting<ElytraFlightModes> efModeSetting() {
        return elytraFly == null ? null : (Setting<ElytraFlightModes>) elytraFly.settings.get("mode");
    }

    private boolean goingUp = false;
    private int fireworkCooldown = 0;
    private int elytraSwapSlot = -1;

    private Mode lastMode = null;

    public Cruise() {
        super(Addon.CATEGORY, "Cruise", "POWERED: AFKVanillaFly-style climb; UNPOWERED: Pitch40 Pitch glide helper.");
    }

    @Override
    public void onActivate() {
        lastMode = mode.get();
        if (lastMode == Mode.POWERED) {
            launched = false;
            yTarget = -1;
            lastRocketUse = 0;
            targetPitch = 0f;
            if (mc.player != null && !mc.player.isGliding()) {
                info("You should already be gliding before enabling POWERED mode.");
            }
        } else {
            if (elytraFly == null) {
                info("ElytraFly module not found.");
                toggle();
                return;
            }
            Setting<ElytraFlightModes> modeSetting = efModeSetting();
            if (modeSetting == null) {
                info("ElytraFly mode setting not accessible.");
                toggle();
                return;
            }
            oldElytraFlyMode = modeSetting.get();
            modeSetting.set(ElytraFlightModes.Pitch40);
            goingUp = false;
            fireworkCooldown = 0;
            elytraSwapSlot = -1;
            if (mc.player != null) resetBounds();
        }
    }

    @Override
    public void onDeactivate() {
        if (lastMode == Mode.UNPOWERED) {
            if (elytraFly != null && elytraFly.isActive()) elytraFly.toggle();
            Setting<ElytraFlightModes> modeSetting = efModeSetting();
            if (modeSetting != null && oldElytraFlyMode != null) modeSetting.set(oldElytraFlyMode);
        }
    }

    private void handleModeFlipIfAny() {
        Mode current = mode.get();
        if (current == lastMode) return;

        if (lastMode == Mode.UNPOWERED) {
            if (elytraFly != null && elytraFly.isActive()) elytraFly.toggle();
            Setting<ElytraFlightModes> ms = efModeSetting();
            if (ms != null && oldElytraFlyMode != null) ms.set(oldElytraFlyMode);
        }

        lastMode = current;
        onActivate();
    }

    @EventHandler
    private void onTick(TickEvent.Pre e) {
        if (mc.player == null || mc.world == null) return;

        handleModeFlipIfAny();

        if (mode.get() == Mode.POWERED) {
            tickPowered();
        } else {
            tickPitch40();
        }
    }

    private void tickPowered() {
        double currentY = mc.player.getY();

        if (mc.player.isGliding()) {
            if (yTarget == -1 || !launched) {
                yTarget = useManualY ? manualYLevel : currentY;
                launched = true;
            }

            double yDiff = yTarget - currentY;
          
            if (Math.abs(yDiff) > 10.0) {
                // Smooth large corrections: atan2(yDiff, horizontalDistance≈100)
                targetPitch = (float) (Math.atan2(yDiff, 100.0) * (180.0 / Math.PI));
            } else if (yDiff > 2.0) {
                targetPitch = 10f;
            } else if (yDiff < -2.0) {
                targetPitch = -10f;
            } else {
                targetPitch = 0f;
            }
          
            float currentPitch = mc.player.getPitch();
            float pitchDiff = targetPitch - currentPitch;
            mc.player.setPitch(currentPitch + pitchDiff * 0.1f);
          
            if (System.currentTimeMillis() - lastRocketUse > fireworkDelayMs) {
                afkTryUseFirework();
            }
        } else {
            if (!launched) {
                mc.player.jump();
                launched = true;
            } else if (System.currentTimeMillis() - lastRocketUse > 1000) {
                afkTryUseFirework();
            }
            yTarget = -1;
        }
    }

    private void afkTryUseFirework() {
        FindItemResult hotbar = InvUtils.findInHotbar(Items.FIREWORK_ROCKET);
        if (!hotbar.found()) {
            FindItemResult inv = InvUtils.find(Items.FIREWORK_ROCKET);
            if (inv.found()) {
                int hotbarSlot = findEmptyHotbarSlot();
                if (hotbarSlot != -1) {
                    InvUtils.move().from(inv.slot()).to(hotbarSlot);
                } else {
                    info("No empty hotbar slot available to move fireworks.");
                    return;
                }
            } else {
                info("No fireworks found in hotbar or inventory.");
                return;
            }
        }
        Utils.firework(mc, false); // return value is ignored in AFK logic
        lastRocketUse = System.currentTimeMillis();
    }

    private int findEmptyHotbarSlot() {
        for (int i = 0; i < 9; i++) {
            if (mc.player.getInventory().getStack(i).isEmpty()) return i;
        }
        return -1;
    }

    private void tickPitch40() {
        if (elytraFly == null) return;

        if (elytraFly.isActive()) {
            // Handle one-tick delayed swap-back if firework used inventory slot
            if (fireworkCooldown > 0) fireworkCooldown--;

            if (elytraSwapSlot != -1) {
                InvUtils.swap(elytraSwapSlot, true);
                mc.interactionManager.interactItem(mc.player, Hand.MAIN_HAND);
                InvUtils.swapBack();
                elytraSwapSlot = -1;
            }

            // Bounds & firework helper while in Pitch40
            double upper = getPitch40Upper();
            double lower = getPitch40Lower();

            // If we somehow fell below the lower bound (lag/reconnect), optionally re-anchor bounds
            if (autoBoundAdjust && mc.player.getY() <= lower - 10.0) {
                resetBounds();
                return;
            }

            // When pitched up (-40) we consider ourselves "climbing"
            if (mc.player.getPitch() == -40f) {
                goingUp = true;

                // Auto firework if velocity is too low and we haven't reached the upper bound yet
                if (autoFirework && mc.player.getVelocity().y < velocityThreshold && mc.player.getY() < upper) {
                    if (fireworkCooldown == 0) {
                        int launchStatus = firework(mc, false);
                        if (launchStatus >= 0) {
                            fireworkCooldown = fireworkCooldownTicks;
                            // Non-200 means we swapped to an Elytra slot and must swap back next tick.
                            if (launchStatus != 200) elytraSwapSlot = launchStatus;
                        }
                    }
                }
            } else {
                // Once we peak (vy <= 0), optionally re-anchor the bounds to the apex
                if (autoBoundAdjust && goingUp && mc.player.getVelocity().y <= 0) {
                    goingUp = false;
                    resetBounds();
                }
            }
        } else {
            // Not active: enable ElytraFly once you're out of the queue (i.e., survival flight allowed)
            if (!mc.player.getAbilities().allowFlying) {
                elytraFly.toggle();
                resetBounds();
            }
        }
    }

    // Access ElytraFly Pitch40 upper/lower bound settings
    @SuppressWarnings("unchecked")
    private double getPitch40Upper() {
        Setting<Double> upper = (Setting<Double>) elytraFly.settings.get("pitch40-upper-bounds");
        return upper != null ? upper.get() : mc.player.getY() + 30.0;
    }

    @SuppressWarnings("unchecked")
    private double getPitch40Lower() {
        Setting<Double> lower = (Setting<Double>) elytraFly.settings.get("pitch40-lower-bounds");
        return lower != null ? lower.get() : mc.player.getY() - 30.0 - boundGap;
    }

    // Reset Pitch40 bounds around current Y
    @SuppressWarnings("unchecked")
    private void resetBounds() {
        Setting<Double> upper = (Setting<Double>) elytraFly.settings.get("pitch40-upper-bounds");
        Setting<Double> lower = (Setting<Double>) elytraFly.settings.get("pitch40-lower-bounds");
        if (upper != null) upper.set(mc.player.getY() - 5.0);
        if (lower != null) lower.set(mc.player.getY() - 5.0 - boundGap);
    }

    @Override
    public String getInfoString() {
        if (mc.player == null) return null;
        if (mode.get() == Mode.POWERED) {
            String tag = useManualY ? ("Y=" + manualYLevel) : (yTarget == -1 ? "Y=~" : String.format("Y=%.1f", yTarget));
            return "POWERED • " + tag;
        } else {
            double upper = getPitch40Upper();
            double lower = getPitch40Lower();
            return String.format("UNPOWERED • [%.1f, %.1f]", lower, upper);
        }
    }
}
