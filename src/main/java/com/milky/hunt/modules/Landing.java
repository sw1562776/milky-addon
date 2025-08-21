package com.milky.hunt.modules;

import com.milky.hunt.Addon;
import meteordevelopment.meteorclient.events.entity.player.ItemUseCrosshairTargetEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.pathing.PathManagers;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.systems.modules.Categories;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.Utils;
import meteordevelopment.meteorclient.utils.player.InvUtils;
import meteordevelopment.meteorclient.utils.player.FindItemResult;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;

import static meteordevelopment.meteorclient.MeteorClient.mc;

public class Landing extends Module {
    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    private final Setting<Boolean> pauseBaritone = sgGeneral.add(new BoolSetting.Builder()
        .name("pause-baritone")
        .description("Pause Baritone pathing while eating chorus fruit.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> onlyWhenAirborne = sgGeneral.add(new BoolSetting.Builder()
        .name("only-when-airborne")
        .description("Only eat when you are not on the ground.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> autoDisableOnLand = sgGeneral.add(new BoolSetting.Builder()
        .name("auto-disable-on-land")
        .description("Automatically turn this module off after you successfully land (only if eating was attempted).")
        .defaultValue(true)
        .build()
    );

    private boolean eating = false;
    private int slot = -1;
    private int prevSlot = -1;
    private boolean wasBaritone = false;
    private boolean attemptedThisEnable = false;

    public Landing() {
        super(Addon.CATEGORY, "landing", "Keeps eating chorus fruit until you land on the ground.");
    }

    @Override
    public void onActivate() {
        eating = false;
        slot = -1;
        prevSlot = -1;
        wasBaritone = false;
        attemptedThisEnable = false;
    }

    @Override
    public void onDeactivate() {
        if (eating) stopEating();
        eating = false;
        attemptedThisEnable = false;
    }

    @EventHandler
    private void onItemUseCrosshairTarget(ItemUseCrosshairTargetEvent event) {
        if (eating) event.target = null;
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (mc.player == null) return;

        // Landed: stop and (optionally) auto-disable
        if (isLanded()) {
            if (eating) stopEating();

            if (autoDisableOnLand.get() && attemptedThisEnable) {
                toggle(); // onDeactivate() handles cleanup
                return;
            }
            if (onlyWhenAirborne.get()) return;
        }

        if (eating) {
            // Ensure still holding chorus fruit
            if (!isChorus(mc.player.getInventory().getStack(slot))) {
                int newSlot = ensureChorusInHotbar();
                if (newSlot == -1) {
                    stopEating();
                    return;
                }
                changeSlot(newSlot);
            }
            eatOnce();
            return;
        }

        // Not currently eating: decide whether to start
        if (!onlyWhenAirborne.get() || !isLanded()) {
            int chorusSlot = ensureChorusInHotbar();
            if (chorusSlot != -1) startEating(chorusSlot);
        }
    }

    /* ---------------------- internals ---------------------- */

    private boolean isLanded() {
        return mc.player.isOnGround();
    }

    private boolean isChorus(ItemStack stack) {
        return stack != null && stack.getItem() == Items.CHORUS_FRUIT;
    }
    private int ensureChorusInHotbar() {
        for (int i = 0; i < 9; i++) {
            if (isChorus(mc.player.getInventory().getStack(i))) return i;
        }
        FindItemResult found = InvUtils.find(Items.CHORUS_FRUIT);
        if (found.found()) {
            int selected = mc.player.getInventory().selectedSlot;
            InvUtils.move().from(found.slot()).toHotbar(selected);
            return selected;
        }
        return -1;
    }

    private void startEating(int chorusSlot) {
        prevSlot = mc.player.getInventory().selectedSlot;
        changeSlot(chorusSlot);

        wasBaritone = false;
        if (pauseBaritone.get() && PathManagers.get().isPathing()) {
            wasBaritone = true;
            PathManagers.get().pause();
        }

        attemptedThisEnable = true;
        eatOnce();
        eating = true;
    }

    private void eatOnce() {
        changeSlot(slot);
        setUsePressed(true);
        if (!mc.player.isUsingItem()) {
            Utils.rightClick();
        }
    }

    private void stopEating() {
        setUsePressed(false);
        if (prevSlot >= 0) changeSlot(prevSlot);
        eating = false;

        if (pauseBaritone.get() && wasBaritone) {
            PathManagers.get().resume();
        }
    }

    private void setUsePressed(boolean pressed) {
        mc.options.useKey.setPressed(pressed);
    }

    private void changeSlot(int newSlot) {
        if (newSlot < 0) return;
        if (mc.player.getInventory().selectedSlot != newSlot) {
            InvUtils.swap(newSlot, false);
        }
        slot = newSlot;
    }
}
