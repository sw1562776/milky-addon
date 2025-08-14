package com.milky.hunt.modules;

import baritone.api.BaritoneAPI;
import baritone.api.IBaritone;
import baritone.api.pathing.goals.GoalBlock;
import com.milky.hunt.Addon;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.player.FindItemResult;
import meteordevelopment.meteorclient.utils.player.InvUtils;
import meteordevelopment.orbit.EventHandler;
import meteordevelopment.orbit.EventPriority;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.network.packet.c2s.play.HandSwingC2SPacket;
import net.minecraft.network.packet.c2s.play.PlayerInteractBlockC2SPacket;
import net.minecraft.screen.GenericContainerScreenHandler;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.screen.slot.Slot;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;

public class ChestDeposit extends Module {
    private enum State { IDLE, PATHING, OPEN_ONCE, WAIT_OPEN, DEPOSITING }

    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    private final Setting<Item> targetItem = sgGeneral.add(new ItemSetting.Builder()
        .name("target-item")
        .description("Item to deposit into the chest.")
        .defaultValue(net.minecraft.item.Items.SEAGRASS)
        .build()
    );

    private final Setting<Integer> keepAtLeast = sgGeneral.add(new IntSetting.Builder()
        .name("keep-at-least")
        .description("Minimum count to keep in your inventory; deposit the rest.")
        .defaultValue(0)
        .min(0)
        .sliderMax(2304)
        .build()
    );

    private final Setting<BlockPos> chestPosSetting = sgGeneral.add(new BlockPosSetting.Builder()
        .name("chest-pos")
        .description("Chest coordinate.")
        .defaultValue(new BlockPos(0, 64, 0))
        .build()
    );

    private final Setting<Double> reachDistance = sgGeneral.add(new DoubleSetting.Builder()
        .name("reach-distance")
        .description("Consider reached when you are within this distance.")
        .defaultValue(3)
        .min(0.5)
        .sliderMax(5.0)
        .build()
    );

    private final Setting<Integer> clicksPerTick = sgGeneral.add(new IntSetting.Builder()
        .name("clicks-per-tick")
        .description("Max quick-move clicks per tick while depositing.")
        .defaultValue(1)
        .min(1)
        .max(8)
        .build()
    );

    private static final int WAIT_OPEN_TICKS_MAX = 10;

    private State state = State.IDLE;
    private BlockPos chestPos = BlockPos.ORIGIN;
    private int waitOpenTicks = 0;

    public ChestDeposit() {
        super(Addon.CATEGORY, "ChestDeposit", "One-shot deposit into a chest using low-level packets.");
    }

    @Override
    public void onActivate() {
        chestPos = chestPosSetting.get();
        if (mc.player == null || mc.world == null) { toggle(); return; }
        if (extraToDeposit() <= 0) { toggle(); return; }
        state = State.IDLE;
        waitOpenTicks = 0;
    }

    @Override
    public void onDeactivate() {
        stopBaritone();
        closeIfOpen();
        state = State.IDLE;
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    private void onTick(TickEvent.Pre e) {
        if (mc.player == null || mc.world == null) { toggle(); return; }

        if (extraToDeposit() <= 0) { finishAndToggle(); return; }

        switch (state) {
            case IDLE -> {
                startBaritone(chestPos);
                state = State.PATHING;
            }
            case PATHING -> {
                if (mc.player.getBlockPos().isWithinDistance(chestPos, reachDistance.get())) {
                    stopBaritone();
                    state = State.OPEN_ONCE;
                }
            }
            case OPEN_ONCE -> {
                tryOpenChestPacket(chestPos);
                state = State.WAIT_OPEN;
                waitOpenTicks = WAIT_OPEN_TICKS_MAX;
            }
            case WAIT_OPEN -> {
                if (isContainerOpen()) {
                    state = State.DEPOSITING;
                    break;
                }
                if (--waitOpenTicks <= 0) {
                    finishAndToggle();
                }
            }
            case DEPOSITING -> {
                if (!isContainerOpen()) { finishAndToggle(); return; }
                int moved = depositSome(targetItem.get(), clicksPerTick.get(), keepAtLeast.get());
                boolean nothingMoved = moved == 0;
                if (extraToDeposit() <= 0 || nothingMoved) {
                    finishAndToggle();
                }
            }
        }
    }

    private void finishAndToggle() {
        closeIfOpen();
        stopBaritone();
        toggle();
    }

    private void startBaritone(BlockPos pos) {
        IBaritone b = BaritoneAPI.getProvider().getPrimaryBaritone();
        b.getCustomGoalProcess().setGoalAndPath(new GoalBlock(pos));
    }

    private void stopBaritone() {
        IBaritone b = BaritoneAPI.getProvider().getPrimaryBaritone();
        b.getPathingBehavior().cancelEverything();
        b.getCustomGoalProcess().setGoal(null);
    }

    private boolean isContainerOpen() {
        ScreenHandler h = mc.player.currentScreenHandler;
        return h != null && h != mc.player.playerScreenHandler;
    }

    private void closeIfOpen() {
        if (mc == null || mc.player == null) return;

        if (isContainerOpen()) {
            mc.player.closeHandledScreen();
        }
        if (mc.currentScreen != null) {
            mc.setScreen(null);
        }
    }

    private void tryOpenChestPacket(BlockPos pos) {
        MinecraftClient m = mc;
        if (m.player == null || m.world == null || m.getNetworkHandler() == null) return;

        Vec3d hitVec = Vec3d.ofCenter(pos).add(0, 0.5, 0);
        BlockHitResult hit = new BlockHitResult(hitVec, Direction.UP, pos, false);

        var pum = ((ClientWorld) m.world).getPendingUpdateManager();
        pum.incrementSequence();
        int sequence = pum.getSequence();

        m.getNetworkHandler().sendPacket(new PlayerInteractBlockC2SPacket(Hand.MAIN_HAND, hit, sequence));
        m.getNetworkHandler().sendPacket(new HandSwingC2SPacket(Hand.MAIN_HAND));
    }

    private int depositSome(Item item, int budget, int keep) {
        ScreenHandler h = mc.player.currentScreenHandler;
        if (h == null) return 0;

        int clicks = 0;
        int chestSlots = Math.max(0, h.slots.size() - 36);
        int playerStart = chestSlots;
        int playerEnd = h.slots.size();

        int currentCount = countInInventory(item);

        if (h instanceof GenericContainerScreenHandler) {
            for (int i = playerStart; i < playerEnd && clicks < budget; i++) {
                Slot s = h.slots.get(i);
                ItemStack st = s.getStack();
                if (!st.isEmpty() && st.getItem() == item) {
                    int stackCount = st.getCount();
                    if (currentCount - stackCount < keep) continue;
                    mc.interactionManager.clickSlot(h.syncId, i, 0, SlotActionType.QUICK_MOVE, mc.player);
                    currentCount -= stackCount;
                    clicks++;
                    if (currentCount <= keep) break;
                }
            }
            return clicks;
        }

        for (int i = playerStart; i < playerEnd && clicks < budget; i++) {
            Slot s = h.slots.get(i);
            ItemStack st = s.getStack();
            if (!st.isEmpty() && st.getItem() == item) {
                int stackCount = st.getCount();
                if (currentCount - stackCount < keep) continue;
                mc.interactionManager.clickSlot(h.syncId, i, 0, SlotActionType.QUICK_MOVE, mc.player);
                currentCount -= stackCount;
                clicks++;
                if (currentCount <= keep) break;
            }
        }
        return clicks;
    }

    private int countInInventory(Item item) {
        int total = 0;
        for (ItemStack st : mc.player.getInventory().main) {
            if (!st.isEmpty() && st.getItem() == item) total += st.getCount();
        }
        ItemStack off = mc.player.getOffHandStack();
        if (!off.isEmpty() && off.getItem() == item) total += off.getCount();
        return total;
    }

    private int extraToDeposit() {
        return Math.max(0, countInInventory(targetItem.get()) - keepAtLeast.get());
    }

    @Override
    public String getInfoString() {
        FindItemResult r = InvUtils.find(targetItem.get());
        int extra = Math.max(0, r.count() - keepAtLeast.get());
        return targetItem.get().getName().getString() + "*" + extra;
    }
}
