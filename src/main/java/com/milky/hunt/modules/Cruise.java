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
import net.minecraft.network.packet.c2s.play.ClientCommandC2SPacket;
import net.minecraft.util.Hand;

import static com.milky.hunt.Utils.firework;

public class Cruise extends Module {
    private enum Mode { Powered, Unpowered }

    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    private final Module elytraFly = Modules.get().get(ElytraFly.class);
    private ElytraFlightModes oldElytraFlyMode;
    @SuppressWarnings("unchecked")
    private final Setting<ElytraFlightModes> elytraFlyMode =
        elytraFly != null ? (Setting<ElytraFlightModes>) elytraFly.settings.get("mode") : null;

    private final Setting<Mode> mode = sgGeneral.add(new EnumSetting.Builder<Mode>()
        .name("mode")
        .description("Powered = AFKVanillaFly; Unpowered = Pitch40Util.")
        .defaultValue(Mode.Powered)
        .onChanged(m -> {
            if (m == Mode.Unpowered) {
                cacheElytraFlyModeIfNeeded();
                if (elytraFlyMode != null) elytraFlyMode.set(ElytraFlightModes.Pitch40);
            } else {
                if (oldElytraFlyMode != null && elytraFlyMode != null) elytraFlyMode.set(oldElytraFlyMode);
                if (elytraFly != null && elytraFly.isActive()) elytraFly.toggle();
            }
        })
        .build()
    );

    private final Setting<Integer> fireworkDelayMs = sgGeneral.add(new IntSetting.Builder()
        .name("Timed Delay (ms)")
        .description("How long to wait between fireworks when using Timed Delay.")
        .defaultValue(4000)
        .sliderRange(0, 10_000)
        .visible(() -> mode.get() == Mode.Powered)
        .build()
    );

    private final Setting<Boolean> useManualY = sgGeneral.add(new BoolSetting.Builder()
        .name("Use Manual Y Level")
        .description("Use a manually set Y level instead of the Y level when activated.")
        .defaultValue(false)
        .visible(() -> mode.get() == Mode.Powered)
        .build()
    );

    private final Setting<Integer> manualYLevel = sgGeneral.add(new IntSetting.Builder()
        .name("Manual Y Level")
        .description("The Y level to maintain when using manual Y level.")
        .defaultValue(256)
        .sliderRange(-64, 320)
        .visible(() -> mode.get() == Mode.Powered && useManualY.get())
        .onChanged(val -> powered_yTarget = val)
        .build()
    );

    public final Setting<Boolean> autoBoundAdjust = sgGeneral.add(new BoolSetting.Builder()
        .name("Auto Adjust Bounds")
        .description("Adjust bounds to keep climbing (useful after reconnect/lag).")
        .defaultValue(true)
        .visible(() -> mode.get() == Mode.Unpowered)
        .build()
    );

    public final Setting<Double> boundGap = sgGeneral.add(new DoubleSetting.Builder()
        .name("Bound Gap")
        .description("Gap between upper and lower bounds. Used on reconnect or at max height if Auto Adjust Bounds is enabled.")
        .defaultValue(60)
        .sliderRange(50, 100)
        .visible(() -> mode.get() == Mode.Unpowered)
        .build()
    );

    public final Setting<Boolean> autoFirework = sgGeneral.add(new BoolSetting.Builder()
        .name("Auto Firework")
        .description("Automatically uses a firework if upward velocity is too low.")
        .defaultValue(true)
        .visible(() -> mode.get() == Mode.Unpowered)
        .build()
    );

    public final Setting<Double> velocityThreshold = sgGeneral.add(new DoubleSetting.Builder()
        .name("Auto Firework Velocity Threshold")
        .description("Y-velocity must be below this value (while going up) to auto-firework.")
        .defaultValue(-0.05)
        .sliderRange(-0.5, 1)
        .visible(() -> mode.get() == Mode.Unpowered && autoFirework.get())
        .build()
    );

    public final Setting<Integer> fireworkCooldownTicks = sgGeneral.add(new IntSetting.Builder()
        .name("Auto Firework Cooldown (ticks)")
        .description("Cooldown after using a firework.")
        .defaultValue(10)
        .sliderRange(0, 100)
        .visible(() -> mode.get() == Mode.Unpowered && autoFirework.get())
        .build()
    );

    private final Setting<Boolean> autoReplaceElytra = sgGeneral.add(new BoolSetting.Builder()
        .name("Auto Replace Elytra")
        .description("Automatically replace Elytra when durability falls below threshold.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Integer> minElytraDurability = sgGeneral.add(new IntSetting.Builder()
        .name("Min Elytra Durability")
        .description("If current Elytra has less than this remaining durability, attempt to replace it.")
        .defaultValue(10)
        .sliderRange(1, 432)
        .visible(autoReplaceElytra::get)
        .build()
    );

    private long powered_lastRocketUse = 0;
    private boolean powered_launched = false;
    private double powered_yTarget = -1;
    private float powered_targetPitch = 0;
    private int unpowered_fireworkCooldown = 0;
    private boolean unpowered_goingUp = true;
    private int unpowered_elytraSwapSlot = -1;
    private int reopenTicks = 0;

    public Cruise() {
        super(Addon.MilkyModCategory, "Cruise",
            "Hybrid Elytra cruise: Powered (AFKVanillaFly) or Unpowered (Pitch40 helper).");
    }
    
    @Override
    public void onActivate() {
        powered_launched = false;
        powered_yTarget = -1;

        if (mode.get() == Mode.Powered) {
            if (mc.player == null) return;
            if (!mc.player.isGliding()) {
                info("You must be flying before enabling Cruise (Powered).");
            }
        } else {
            cacheElytraFlyModeIfNeeded();
            if (elytraFlyMode != null) elytraFlyMode.set(ElytraFlightModes.Pitch40);
            unpowered_fireworkCooldown = 0;
            unpowered_goingUp = true;
            unpowered_elytraSwapSlot = -1;
        }
    }

    @Override
    public void onDeactivate() {
        if (mode.get() == Mode.Unpowered) {
            if (elytraFly != null && elytraFly.isActive()) elytraFly.toggle();
            if (oldElytraFlyMode != null && elytraFlyMode != null) elytraFlyMode.set(oldElytraFlyMode);
        }
    }

    private void cacheElytraFlyModeIfNeeded() {
        if (oldElytraFlyMode == null && elytraFlyMode != null) {
            oldElytraFlyMode = elytraFlyMode.get();
        }
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (mc.player == null) return;

        if (autoReplaceElytra.get()) {
            maybeReplaceElytra();
        }

        if (reopenTicks > 0) {
            tryStartFallFlying();
            reopenTicks--;
        }

        if (mode.get() == Mode.Powered) tickPowered();
        else tickUnpowered();
    }

    private void tickPowered() {
        double currentY = mc.player.getY();

        if (mc.player.isGliding()) {
            if (powered_yTarget == -1 || !powered_launched) {
                powered_yTarget = useManualY.get() ? manualYLevel.get() : currentY;
                powered_launched = true;
            }

            if (!useManualY.get()) {
                double yDiffFromLock = currentY - powered_yTarget;
                if (Math.abs(yDiffFromLock) > 10.0) {
                    powered_yTarget = currentY;
                    info("Y-lock reset due to altitude deviation.");
                }
            }

            double yDiff = currentY - powered_yTarget;

            if (Math.abs(yDiff) > 10.0) {
                powered_targetPitch = (float) (Math.atan2(yDiff, 100) * (180 / Math.PI));
            } else if (yDiff > 2.0) {
                powered_targetPitch = 10f;
            } else if (yDiff < -2.0) {
                powered_targetPitch = -10f;
            } else {
                powered_targetPitch = 0f;
            }

            float currentPitch = mc.player.getPitch();
            float pitchDiff = powered_targetPitch - currentPitch;
            mc.player.setPitch(currentPitch + pitchDiff * 0.1f);

            if (System.currentTimeMillis() - powered_lastRocketUse > fireworkDelayMs.get()) {
                powered_tryUseFirework();
            }
        } else {
            if (!hasEligibleElytraAvailable()) {
                powered_yTarget = -1;
                return;
            }

            if (!powered_launched) {
                mc.player.jump();
                powered_launched = true;
            } else if (System.currentTimeMillis() - powered_lastRocketUse > 1000) {
                powered_tryUseFirework();
            }
            powered_yTarget = -1;
        }
    }

    private void powered_tryUseFirework() {
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
        Utils.firework(mc, false);
        powered_lastRocketUse = System.currentTimeMillis();
    }

    private int findEmptyHotbarSlot() {
        for (int i = 0; i < 9; i++) {
            if (mc.player.getInventory().getStack(i).isEmpty()) return i;
        }
        return -1;
    }

    private void tickUnpowered() {
        if (elytraFly != null && elytraFly.isActive()) {
            if (unpowered_fireworkCooldown > 0) unpowered_fireworkCooldown--;

            if (unpowered_elytraSwapSlot != -1) {
                InvUtils.swap(unpowered_elytraSwapSlot, true);
                mc.interactionManager.interactItem(mc.player, Hand.MAIN_HAND);
                InvUtils.swapBack();
                unpowered_elytraSwapSlot = -1;
            }

            if (autoBoundAdjust.get() && mc.player.getY() <= getLowerBounds() - 10) {
                resetBoundsToCurrent();
                return;
            }

            if (Math.abs(mc.player.getPitch() + 40f) <= 0.2f) {
                unpowered_goingUp = true;

                if (autoFirework.get()
                    && mc.player.getVelocity().y < velocityThreshold.get()
                    && mc.player.getY() < getUpperBounds()) {

                    if (unpowered_fireworkCooldown == 0) {
                        int launchStatus = firework(mc, false);
                        if (launchStatus >= 0) {
                            unpowered_fireworkCooldown = fireworkCooldownTicks.get();
                            // If Utils.firework swapped to a hotbar slot, we need to swap back next tick.
                            if (launchStatus != 200) unpowered_elytraSwapSlot = launchStatus;
                        }
                    }
                }
            }
            else if (autoBoundAdjust.get() && unpowered_goingUp && mc.player.getVelocity().y <= 0) {
                unpowered_goingUp = false;
                resetBoundsToCurrent();
            }
        } else {
            if (!mc.player.getAbilities().allowFlying) {
                if (!hasEligibleElytraAvailable()) return;

                if (elytraFly != null) {
                    elytraFly.toggle();
                    resetBoundsToCurrent();
                }
            }
        }
    }

    @SuppressWarnings("unchecked")
    private double getUpperBounds() {
        return elytraFly != null
            ? (double) ((Setting<Double>) elytraFly.settings.get("pitch40-upper-bounds")).get()
            : Double.MAX_VALUE;
    }

    @SuppressWarnings("unchecked")
    private double getLowerBounds() {
        return elytraFly != null
            ? (double) ((Setting<Double>) elytraFly.settings.get("pitch40-lower-bounds")).get()
            : -Double.MAX_VALUE;
    }

    @SuppressWarnings("unchecked")
    private void resetBoundsToCurrent() {
        if (elytraFly == null) return;
        double base = mc.player.getY() - 5;
        ((Setting<Double>) elytraFly.settings.get("pitch40-upper-bounds")).set(base);
        ((Setting<Double>) elytraFly.settings.get("pitch40-lower-bounds")).set(base - boundGap.get());
    }

    private boolean hasEligibleElytraAvailable() {
        ItemStack chest = mc.player.getInventory().getArmorStack(2);
        if (isHealthyElytra(chest)) return true;
        return findBestElytraSlot() != -1;
    }

    private boolean isHealthyElytra(ItemStack stack) {
        if (stack == null || stack.isEmpty() || !stack.isOf(Items.ELYTRA)) return false;
        int remaining = stack.getMaxDamage() - stack.getDamage();
        return remaining >= minElytraDurability.get();
    }

    private int findBestElytraSlot() {
        int bestSlot = -1;
        int bestRemain = -1;

        for (int i = 0; i < mc.player.getInventory().size(); i++) {
            ItemStack s = mc.player.getInventory().getStack(i);
            if (s.isOf(Items.ELYTRA)) {
                int remain = s.getMaxDamage() - s.getDamage();
                if (remain >= minElytraDurability.get() && remain > bestRemain) {
                    bestRemain = remain;
                    bestSlot = i;
                }
            }
        }
        return bestSlot;
    }

    private void maybeReplaceElytra() {
        ItemStack chest = mc.player.getInventory().getArmorStack(2);
        if (isHealthyElytra(chest)) return;

        int slot = findBestElytraSlot();
        if (slot == -1) return;

        InvUtils.move().from(slot).toArmor(2);

        reopenTicks = 3;
    }

    private void tryStartFallFlying() {
        if (mc.player == null) return;
        if (mc.getNetworkHandler() != null) {
            mc.getNetworkHandler().sendPacket(new ClientCommandC2SPacket(mc.player, ClientCommandC2SPacket.Mode.START_FALL_FLYING));
        }
    }

    public void resetYLock() {
        powered_yTarget = -1;
        powered_launched = false;
    }
}
