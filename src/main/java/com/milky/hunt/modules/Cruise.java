package com.milky.hunt.modules;

import com.milky.hunt.Addon;
import com.milky.hunt.Utils;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.systems.modules.Modules;
import meteordevelopment.meteorclient.systems.modules.movement.elytrafly.ElytraFly;
import meteordevelopment.meteorclient.systems.modules.movement.elytrafly.ElytraFlightModes;
import meteordevelopment.meteorclient.utils.player.FindItemResult;
import meteordevelopment.meteorclient.utils.player.InvUtils;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.util.Hand;

public class Cruise extends Module {
    public enum Mode { POWERED, UNPOWERED }

    private final SettingGroup sg = settings.getDefaultGroup();

    private final Setting<Mode> mode = sg.add(new EnumSetting.Builder<Mode>()
        .name("mode")
        .description("POWERED = PoweredFly; UNPOWERED = UnpoweredFly.")
        .defaultValue(Mode.POWERED)
        .build()
    );

    private final Setting<Boolean> autoSwapElytra = sg.add(new BoolSetting.Builder()
        .name("auto-swap-elytra")
        .description("Automatically swap to another Elytra from inventory when durability falls below the threshold.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Integer> minElytraDurability = sg.add(new IntSetting.Builder()
        .name("elytra-min-durability")
        .description("Minimum remaining durability to keep wearing the current Elytra. Below this value, a better Elytra will be auto-equipped if available.")
        .defaultValue(10)
        .min(1)
        .max(432)
        .sliderRange(1, 432)
        .build()
    );

    private final Setting<Integer> powered_fireworkDelayMs = sg.add(new IntSetting.Builder()
        .name("Timed Delay (ms)")
        .description("How long to wait between fireworks when using Timed Delay.")
        .defaultValue(4000)
        .sliderRange(0, 10000)
        .visible(() -> mode.get() == Mode.POWERED)
        .build()
    );

    private final Setting<Boolean> powered_useManualY = sg.add(new BoolSetting.Builder()
        .name("Use Manual Y Level")
        .description("Maintain a manual Y level instead of locking current altitude when starting.")
        .defaultValue(false)
        .visible(() -> mode.get() == Mode.POWERED)
        .build()
    );

    private final Setting<Integer> powered_manualYLevel = sg.add(new IntSetting.Builder()
        .name("Manual Y Level")
        .description("The Y level to maintain when using manual Y level.")
        .defaultValue(256)
        .sliderRange(-64, 320)
        .visible(() -> mode.get() == Mode.POWERED && powered_useManualY.get())
        .build()
    );

    private static final class PoweredFly {
        long lastRocketUse = 0L;
        boolean launched = false;
        double yTarget = -1;
        float targetPitch = 0f;
    }
    private final PoweredFly powered = new PoweredFly();

    private final Setting<Boolean> unpowered_autoBoundAdjust = sg.add(new BoolSetting.Builder()
        .name("Auto Adjust Bounds")
        .description("Adjusts your bounds to keep gaining height; fixes falling on reconnect or lag.")
        .defaultValue(true)
        .visible(() -> mode.get() == Mode.UNPOWERED)
        .build()
    );

    private final Setting<Double> unpowered_boundGap = sg.add(new DoubleSetting.Builder()
        .name("Bound Gap")
        .description("Gap between upper and lower bounds; used on reconnect or on apex if Auto Adjust is enabled.")
        .defaultValue(60.0)
        .sliderRange(50.0, 100.0)
        .visible(() -> mode.get() == Mode.UNPOWERED)
        .build()
    );

    private final Setting<Boolean> unpowered_autoFirework = sg.add(new BoolSetting.Builder()
        .name("Auto Firework")
        .description("Auto-use firework when vertical speed drops too low before reaching the upper bound.")
        .defaultValue(true)
        .visible(() -> mode.get() == Mode.UNPOWERED)
        .build()
    );

    private final Setting<Double> unpowered_velocityThreshold = sg.add(new DoubleSetting.Builder()
        .name("Auto Firework Velocity Threshold")
        .description("If your vertical velocity falls below this while climbing, a firework may be used.")
        .defaultValue(-0.05)
        .sliderRange(-0.50, 0.0)
        .visible(() -> mode.get() == Mode.UNPOWERED && unpowered_autoFirework.get())
        .build()
    );

    private final Setting<Integer> unpowered_fireworkCooldownTicks = sg.add(new IntSetting.Builder()
        .name("Auto Firework Cooldown (ticks)")
        .description("Cooldown after using a firework in ticks.")
        .defaultValue(10)
        .sliderRange(0, 100)
        .visible(() -> mode.get() == Mode.UNPOWERED && unpowered_autoFirework.get())
        .build()
    );

    // Runtime state for UnpoweredFly
    private static final class UnpoweredFly {
        ElytraFly elytraFly;
        ElytraFlightModes oldMode = null;
        boolean goingUp = false;
        int fireworkCooldown = 0;
        int elytraSwapSlot = -1;
    }
    private final UnpoweredFly unpowered = new UnpoweredFly();

    private Mode lastMode = null;

    public Cruise() {
        super(Addon.CATEGORY, "Cruise",
            "POWERED=PoweredFly (timed fireworks to hold Y). UNPOWERED=UnpoweredFly (Pitch40 helper). Includes optional auto-swap Elytra.");
    }

    @Override
    public void onActivate() {
        lastMode = mode.get();
        if (lastMode == Mode.POWERED) {
            powered_onActivate();
        } else {
            if (!unpowered_onActivate()) {
                info("[Cruise] ElytraFly not found or not accessible.");
                toggle();
                return;
            }
        }
        if (autoSwapElytra.get()) equipElytraIfNeeded();
    }

    @Override
    public void onDeactivate() {
        if (lastMode == Mode.UNPOWERED) unpowered_onDeactivate();
    }

    private void handleModeFlipIfAny() {
        Mode current = mode.get();
        if (current == lastMode) return;

        if (lastMode == Mode.UNPOWERED) unpowered_onDeactivate();

        lastMode = current;
        if (current == Mode.POWERED) powered_onActivate();
        else {
            if (!unpowered_onActivate()) {
                info("[Cruise] ElytraFly not found or not accessible.");
                toggle();
            }
        }
    }

    @EventHandler
    private void onTick(TickEvent.Pre e) {
        if (mc.player == null || mc.world == null) return;

        // Elytra auto-swap happens first so both modes benefit from a good wing.
        if (autoSwapElytra.get()) equipElytraIfNeeded();

        handleModeFlipIfAny();

        if (mode.get() == Mode.POWERED) powered_tick();
        else unpowered_tick();
    }

    private void powered_onActivate() {
        powered.launched = false;
        powered.yTarget = -1;
        powered.lastRocketUse = 0L;
        powered.targetPitch = 0f;
    }

    private void powered_tick() {
        double currentY = mc.player.getY();

        if (mc.player.isGliding()) {
            if (powered.yTarget == -1 || !powered.launched) {
                powered.yTarget = powered_useManualY.get() ? powered_manualYLevel.get() : currentY;
                powered.launched = true;
            }

            double yDiff = powered.yTarget - currentY;

            if (Math.abs(yDiff) > 10.0) {
                powered.targetPitch = (float) Math.toDegrees(Math.atan2(yDiff, 100.0));
            } else if (yDiff > 2.0) powered.targetPitch = 10f;
            else if (yDiff < -2.0) powered.targetPitch = -10f;
            else powered.targetPitch = 0f;

            float currentPitch = mc.player.getPitch();
            mc.player.setPitch(currentPitch + (powered.targetPitch - currentPitch) * 0.1f);

            if (System.currentTimeMillis() - powered.lastRocketUse > powered_fireworkDelayMs.get()) {
                powered_tryUseFirework();
            }
        } else {
            if (!powered.launched) {
                mc.player.jump();
                powered.launched = true;
            } else if (System.currentTimeMillis() - powered.lastRocketUse > 1000) {
                powered_tryUseFirework();
            }
            powered.yTarget = -1;
        }
    }

    private void powered_tryUseFirework() {
        FindItemResult hotbar = InvUtils.findInHotbar(Items.FIREWORK_ROCKET);
        if (!hotbar.found()) {
            FindItemResult inv = InvUtils.find(Items.FIREWORK_ROCKET);
            if (inv.found()) {
                int slot = powered_findEmptyHotbarSlot();
                if (slot != -1) InvUtils.move().from(inv.slot()).to(slot);
                else { info("No empty hotbar slot for fireworks."); return; }
            } else { info("No fireworks found."); return; }
        }
        Utils.firework(mc, false);
        powered.lastRocketUse = System.currentTimeMillis();
    }

    private int powered_findEmptyHotbarSlot() {
        for (int i = 0; i < 9; i++) if (mc.player.getInventory().getStack(i).isEmpty()) return i;
        return -1;
    }

    private boolean unpowered_onActivate() {
        unpowered.elytraFly = Modules.get().get(ElytraFly.class);
        if (unpowered.elytraFly == null) return false;

        @SuppressWarnings("unchecked")
        Setting<ElytraFlightModes> modeSetting = (Setting<ElytraFlightModes>) unpowered.elytraFly.settings.get("mode");
        if (modeSetting == null) return false;

        unpowered.oldMode = modeSetting.get();
        modeSetting.set(ElytraFlightModes.Pitch40);

        unpowered.goingUp = false;
        unpowered.fireworkCooldown = 0;
        unpowered.elytraSwapSlot = -1;

        unpowered_resetBounds();
        return true;
    }

    private void unpowered_onDeactivate() {
        if (unpowered.elytraFly != null) {
            if (unpowered.elytraFly.isActive()) unpowered.elytraFly.toggle();
            @SuppressWarnings("unchecked")
            Setting<ElytraFlightModes> modeSetting = (Setting<ElytraFlightModes>) unpowered.elytraFly.settings.get("mode");
            if (modeSetting != null && unpowered.oldMode != null) modeSetting.set(unpowered.oldMode);
        }
    }

    private void unpowered_tick() {
        if (unpowered.elytraFly == null) return;

        if (unpowered.elytraFly.isActive()) {
            if (unpowered.fireworkCooldown > 0) unpowered.fireworkCooldown--;

            if (unpowered.elytraSwapSlot != -1) {
                InvUtils.swap(unpowered.elytraSwapSlot, true);
                mc.interactionManager.interactItem(mc.player, Hand.MAIN_HAND);
                InvUtils.swapBack();
                unpowered.elytraSwapSlot = -1;
            }

            double upper = unpowered_getUpper();
            double lower = unpowered_getLower();

            if (unpowered_autoBoundAdjust.get() && mc.player.getY() <= lower - 10.0) {
                unpowered_resetBounds();
                return;
            }

            if (mc.player.getPitch() == -40f) {
                unpowered.goingUp = true;

                if (unpowered_autoFirework.get()
                    && mc.player.getVelocity().y < unpowered_velocityThreshold.get()
                    && mc.player.getY() < upper) {

                    if (unpowered.fireworkCooldown == 0) {
                        int status = Utils.firework(mc, false);
                        if (status >= 0) {
                            unpowered.fireworkCooldown = unpowered_fireworkCooldownTicks.get();
                            if (status != 200) unpowered.elytraSwapSlot = status;
                        }
                    }
                }
            } else {
                if (unpowered_autoBoundAdjust.get() && unpowered.goingUp && mc.player.getVelocity().y <= 0) {
                    unpowered.goingUp = false;
                    unpowered_resetBounds();
                }
            }
        } else {
            if (!mc.player.getAbilities().allowFlying) {
                unpowered.elytraFly.toggle();
                unpowered_resetBounds();
            }
        }
    }

    @SuppressWarnings("unchecked")
    private double unpowered_getUpper() {
        Setting<Double> upper = (Setting<Double>) unpowered.elytraFly.settings.get("pitch40-upper-bounds");
        return upper != null ? upper.get() : mc.player.getY() + 30.0;
    }

    @SuppressWarnings("unchecked")
    private double unpowered_getLower() {
        Setting<Double> lower = (Setting<Double>) unpowered.elytraFly.settings.get("pitch40-lower-bounds");
        return lower != null ? lower.get() : mc.player.getY() - 30.0 - unpowered_boundGap.get();
    }

    @SuppressWarnings("unchecked")
    private void unpowered_resetBounds() {
        Setting<Double> upper = (Setting<Double>) unpowered.elytraFly.settings.get("pitch40-upper-bounds");
        Setting<Double> lower = (Setting<Double>) unpowered.elytraFly.settings.get("pitch40-lower-bounds");
        if (upper != null) upper.set(mc.player.getY() - 5.0);
        if (lower != null) lower.set(mc.player.getY() - 5.0 - unpowered_boundGap.get());
    }

    private int remainingDurability(ItemStack s) {
        if (s == null || s.isEmpty() || !s.isOf(Items.ELYTRA)) return -1;
        return s.getMaxDamage() - s.getDamage();
    }

    private int findBestElytraSlotAbove(int minRemain) {
        var inv = mc.player.getInventory();
        int size = inv.size();
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

    private void equipElytraIfNeeded() {
        int min = minElytraDurability.get();
        ItemStack chest = mc.player.getInventory().getArmorStack(2);

        // Already wearing a good Elytra
        if (remainingDurability(chest) >= min) return;

        // Otherwise, equip the best surviving Elytra meeting the threshold
        int slot = findBestElytraSlotAbove(min);
        if (slot != -1) {
            InvUtils.move().from(slot).toArmor(2);
            info(String.format("[Cruise] Auto-swapped Elytra (>= %d durability).", min));
        }
    }

    @Override
    public String getInfoString() {
        if (mc.player == null) return null;

        String wing = "";
        if (autoSwapElytra.get()) {
            ItemStack chest = mc.player.getInventory().getArmorStack(2);
            int rem = remainingDurability(chest);
            wing = (rem >= 0) ? String.format(" | Elytra=%d", rem) : " | Elytra=-";
        }

        if (mode.get() == Mode.POWERED) {
            String tag = powered_useManualY.get()
                ? ("Y=" + powered_manualYLevel.get())
                : (powered.yTarget == -1 ? "Y=~" : String.format("Y=%.1f", powered.yTarget));
            return "POWERED • " + tag + wing;
        } else {
            double upper = unpowered_getUpper();
            double lower = unpowered_getLower();
            return String.format("UNPOWERED • [%.1f, %.1f]%s", lower, upper, wing);
        }
    }
}
