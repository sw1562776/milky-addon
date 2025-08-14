package com.milky.hunt.modules;

import baritone.api.BaritoneAPI;
import baritone.api.pathing.goals.GoalBlock;
import com.milky.hunt.Addon;
import com.milky.hunt.mixin.ClientWorldPendingUpdateAccessor;
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
import net.minecraft.network.packet.c2s.play.CloseHandledScreenC2SPacket;
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

public class ChestRestock extends Module {
    public enum InputMode { Simple, String }
    private enum State { IDLE, PATHING, OPENING, LOOTING, COOLDOWN }

    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    private final Setting<Item> targetItem = sgGeneral.add(new ItemSetting.Builder()
        .name("target-item")
        .description("Item to keep stocked in your inventory.")
        .defaultValue(net.minecraft.item.Items.SEAGRASS)
        .build()
    );

    private final Setting<Integer> restockUntil = sgGeneral.add(new IntSetting.Builder()
        .name("restock-until")
        .description("Target total count in inventory.")
        .defaultValue(64)
        .min(1)
        .sliderMax(1024)
        .build()
    );

    private final Setting<InputMode> inputMode = sgGeneral.add(new EnumSetting.Builder<InputMode>()
        .name("input-mode")
        .description("Choose coordinate input mode.")
        .defaultValue(InputMode.Simple)
        .build()
    );

    private final Setting<BlockPos> chestPosSetting = sgGeneral.add(new BlockPosSetting.Builder()
        .name("chest-pos")
        .description("Chest coordinate.")
        .defaultValue(new BlockPos(8, 64, 8))
        .visible(() -> inputMode.get() == InputMode.Simple)
        .build()
    );

    private final Setting<String> chestPosString = sgGeneral.add(new StringSetting.Builder()
        .name("chest-pos-str")
        .description("Chest coordinate in string: x,y,z")
        .defaultValue("8,64,8")
        .visible(() -> inputMode.get() == InputMode.String)
        .build()
    );

    private final Setting<Double> reachDistance = sgGeneral.add(new DoubleSetting.Builder()
        .name("reach-distance")
        .description("Consider reached when you are within this distance.")
        .defaultValue(1.5)
        .min(0.5)
        .sliderMax(5.0)
        .build()
    );

    private final Setting<Integer> clicksPerTick = sgGeneral.add(new IntSetting.Builder()
        .name("clicks-per-tick")
        .description("Max quick-move clicks per tick while looting.")
        .defaultValue(8)
        .min(1)
        .max(64)
        .build()
    );

    private final Setting<Integer> reopenCooldown = sgGeneral.add(new IntSetting.Builder()
        .name("reopen-cooldown-ticks")
        .description("Cooldown before retrying open if it fails.")
        .defaultValue(10)
        .min(0)
        .max(100)
        .build()
    );

    private State state = State.IDLE;
    private int cd = 0;
    private BlockPos chestPos = BlockPos.ORIGIN;

    public ChestRestock() {
        super(Addon.CATEGORY, "ChestRestock", "Restock a chosen item from a chest at a configured coordinate using low-level packets.");
    }

    @Override
    public void onActivate() {
        chestPos = resolveChestPos();
        if (chestPos == null) {
            info("Invalid chest coordinate.");
            toggle();
            return;
        }
        state = State.IDLE;
        cd = 0;
    }

    @Override
    public void onDeactivate() {
        stopBaritone();
        closeIfOpen();
        state = State.IDLE;
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    private void onTick(TickEvent.Pre e) {
        if (mc.player == null || mc.world == null) return;

        int have = countInInventory(targetItem.get());
        if (have >= restockUntil.get()) {
            stopBaritone();
            closeIfOpen();
            state = State.IDLE;
            return;
        }

        switch (state) {
            case IDLE -> {
                startBaritone(chestPos);
                state = State.PATHING;
            }
            case PATHING -> {
                if (mc.player.getBlockPos().isWithinDistance(chestPos, reachDistance.get())) {
                    stopBaritone();
                    state = State.OPENING;
                }
            }
            case OPENING -> {
                if (!isContainerOpen()) {
                    if (cd > 0) { cd--; break; }
                    tryOpenChestPacket(chestPos);
                    cd = reopenCooldown.get();
                    break;
                }
                state = State.LOOTING;
            }
            case LOOTING -> {
                if (!isContainerOpen()) {
                    state = State.OPENING;
                    break;
                }
                int did = lootSome(targetItem.get(), clicksPerTick.get());
                int now = countInInventory(targetItem.get());
                boolean chestNoMoreTarget = (did == 0);
                if (now >= restockUntil.get() || chestNoMoreTarget || !hasSpaceFor(targetItem.get())) {
                    closeIfOpen();
                    state = State.COOLDOWN;
                    cd = 5;
                }
            }
            case COOLDOWN -> {
                if (cd > 0) cd--;
                else state = State.IDLE;
            }
        }
    }

    // ---- helpers ----

    private BlockPos resolveChestPos() {
        if (inputMode.get() == InputMode.Simple) return chestPosSetting.get();
        try {
            String[] p = chestPosString.get().split(",");
            if (p.length != 3) return null;
            return new BlockPos(
                Integer.parseInt(p[0].trim()),
                Integer.parseInt(p[1].trim()),
                Integer.parseInt(p[2].trim())
            );
        } catch (Exception e) {
            return null;
        }
    }

    private void startBaritone(BlockPos pos) {
        BaritoneAPI.getProvider().getPrimaryBaritone().getCustomGoalProcess().setGoalAndPath(new GoalBlock(pos));
    }

    private void stopBaritone() {
        var b = BaritoneAPI.getProvider().getPrimaryBaritone();
        b.getPathingBehavior().cancelEverything();
        b.getCustomGoalProcess().setGoal(null);
    }

    private boolean isContainerOpen() {
        ScreenHandler h = mc.player.currentScreenHandler;
        return h != null && h != mc.player.playerScreenHandler;
    }

    private void closeIfOpen() {
        if (isContainerOpen()) {
            mc.getNetworkHandler().sendPacket(new CloseHandledScreenC2SPacket(mc.player.currentScreenHandler.syncId));
        }
    }

    private void tryOpenChestPacket(BlockPos pos) {
        MinecraftClient m = mc;
        if (m.player == null || m.world == null || m.getNetworkHandler() == null) return;

        Vec3d hitVec = Vec3d.ofCenter(pos).add(0, 0.5, 0);
        BlockHitResult hit = new BlockHitResult(hitVec, Direction.UP, pos, false);

        var pum = ((ClientWorldPendingUpdateAccessor) (ClientWorld) m.world).milky$getPendingUpdateManager();
        pum.incrementSequence();
        int sequence = pum.getSequence();

        m.getNetworkHandler().sendPacket(new PlayerInteractBlockC2SPacket(Hand.MAIN_HAND, hit, sequence));
        m.getNetworkHandler().sendPacket(new HandSwingC2SPacket(Hand.MAIN_HAND));
    }

    private int lootSome(Item item, int budget) {
        ScreenHandler h = mc.player.currentScreenHandler;
        if (h == null) return 0;

        int clicks = 0;
        int chestSlots = Math.max(0, h.slots.size() - 36);

        if (h instanceof GenericContainerScreenHandler) {
            for (int i = 0; i < chestSlots && clicks < budget; i++) {
                Slot s = h.slots.get(i);
                ItemStack st = s.getStack();
                if (!st.isEmpty() && st.getItem() == item) {
                    mc.interactionManager.clickSlot(h.syncId, i, 0, SlotActionType.QUICK_MOVE, mc.player);
                    clicks++;
                }
            }
            return clicks;
        }

        for (int i = 0; i < chestSlots && clicks < budget; i++) {
            Slot s = h.slots.get(i);
            ItemStack st = s.getStack();
            if (!st.isEmpty() && st.getItem() == item) {
                mc.interactionManager.clickSlot(h.syncId, i, 0, SlotActionType.QUICK_MOVE, mc.player);
                clicks++;
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

    private boolean hasSpaceFor(Item item) {
        int max = new ItemStack(item).getMaxCount();
        for (ItemStack st : mc.player.getInventory().main) {
            if (st.isEmpty()) return true;
            if (st.getItem() == item && st.getCount() < max) return true;
        }
        ItemStack off = mc.player.getOffHandStack();
        return off.isEmpty() || (off.getItem() == item && off.getCount() < max);
    }

    @Override
    public String getInfoString() {
        FindItemResult r = InvUtils.find(targetItem.get());
        return targetItem.get().getName().getString() + "*" + r.count();
    }
}
