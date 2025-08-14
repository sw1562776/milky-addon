package com.milky.hunt.modules;

import com.milky.hunt.Addon;
import meteordevelopment.meteorclient.events.entity.player.InteractItemEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.IntSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.player.InvUtils;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.network.packet.c2s.play.ClientCommandC2SPacket;
import net.minecraft.util.Hand;

public class InfiniteElytra extends Module {
    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    private final Setting<Integer> elytraOnTicks = sgGeneral.add(new IntSetting.Builder()
        .name("elytra-on-ticks")
        .description("Number of ticks to keep Elytra equipped (on).")
        .defaultValue(15)
        .min(1)
        .sliderRange(1, 200)
        .build()
    );

    private final Setting<Integer> elytraOffTicks = sgGeneral.add(new IntSetting.Builder()
        .name("elytra-off-ticks")
        .description("Number of ticks to keep Elytra unequipped (off).")
        .defaultValue(2)
        .min(1)
        .sliderRange(1, 200)
        .build()
    );

    private final Setting<Boolean> fireRockets = sgGeneral.add(new BoolSetting.Builder()
        .name("auto-fire-rockets")
        .description("Auto-use rockets while gliding.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Integer> timebetweenfires = sgGeneral.add(new IntSetting.Builder()
        .name("time-between-rockets")
        .description("Ticks between rocket uses.")
        .defaultValue(40)
        .min(1)
        .sliderRange(1, 200)
        .build()
    );

    private int tickCounter = 0;
    private boolean playerWasFlying = false;
    private boolean glidingTime = false;
    private int ticksSinceLastRocket = 0;

    public InfiniteElytra() {
        super(Addon.CATEGORY, "InfiniteElytra", "Toggle Elytra on/off to save durability and auto-use rockets to maintain flight.");
    }

    @EventHandler
    private void onTick(TickEvent.Post event) {
        if (mc.player == null || mc.world == null || mc.interactionManager == null) return;

        if (!playerWasFlying) playerWasFlying = mc.player.isGliding();
        if (!playerWasFlying) return;

        tickCounter++;
        if (fireRockets.get()) ticksSinceLastRocket++;

        int totalCycle = elytraOnTicks.get() + elytraOffTicks.get();
        int phaseTick = tickCounter % totalCycle;
        if (phaseTick == 0) tickCounter = 0;

        if (phaseTick < elytraOnTicks.get()) {
            glidingTime = true;

            ItemStack chestNow = mc.player.getEquippedStack(EquipmentSlot.CHEST);
            if (chestNow.getItem() != Items.ELYTRA) {
                for (int i = 0; i < mc.player.getInventory().size(); i++) {
                    ItemStack stack = mc.player.getInventory().getStack(i);
                    if (stack.getItem() == Items.ELYTRA) {
                        InvUtils.move().from(i).toArmor(2);
                        break;
                    }
                }
                chestNow = mc.player.getEquippedStack(EquipmentSlot.CHEST);
            }

            if (chestNow.getItem() == Items.ELYTRA && !mc.player.isOnGround() && !mc.player.isGliding()) {
                if (mc.getNetworkHandler() != null) {
                    mc.getNetworkHandler().sendPacket(new ClientCommandC2SPacket(mc.player, ClientCommandC2SPacket.Mode.START_FALL_FLYING));
                }
            }
        } else {
            glidingTime = false;
            ItemStack chestNow = mc.player.getEquippedStack(EquipmentSlot.CHEST);
            if (chestNow.getItem() == Items.ELYTRA) {
                int empty = -1;
                for (int i = 0; i < 36; i++) {
                    if (mc.player.getInventory().getStack(i).isEmpty()) {
                        empty = i;
                        break;
                    }
                }
                if (empty != -1) InvUtils.move().fromArmor(2).to(empty);
            }
        }

        if (glidingTime && fireRockets.get()) {
            int needGap = timebetweenfires.get();
            if (ticksSinceLastRocket >= needGap) {
                int rocketSlot = -1;
                for (int i = 0; i < 9; i++) {
                    ItemStack stack = mc.player.getInventory().getStack(i);
                    if (stack.getItem() == Items.FIREWORK_ROCKET) {
                        rocketSlot = i;
                        break;
                    }
                }
                if (rocketSlot != -1) {
                    int prev = mc.player.getInventory().selectedSlot;
                    if (rocketSlot != prev) {
                        mc.player.getInventory().selectedSlot = rocketSlot;
                        mc.interactionManager.interactItem(mc.player, Hand.MAIN_HAND);
                        mc.player.getInventory().selectedSlot = prev;
                    } else {
                        mc.interactionManager.interactItem(mc.player, Hand.MAIN_HAND);
                    }
                    ticksSinceLastRocket = 0;
                }
            }
        }
    }

    @EventHandler
    private void onInteractItem(InteractItemEvent event) {
        if (mc.player == null) return;
        ItemStack stack = mc.player.getStackInHand(event.hand);
        if (stack != null && stack.getItem() == Items.FIREWORK_ROCKET) {
            ticksSinceLastRocket = 0;
        }
    }
}
