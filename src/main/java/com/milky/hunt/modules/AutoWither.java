package com.milky.hunt.modules;

import com.milky.hunt.Addon;
import meteordevelopment.meteorclient.events.render.Render3DEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.renderer.ShapeMode;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.render.color.SettingColor;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.item.BlockItem;
import net.minecraft.item.Item;
import net.minecraft.item.Items;
import net.minecraft.network.packet.c2s.play.PlayerActionC2SPacket;
import net.minecraft.network.packet.c2s.play.PlayerInteractBlockC2SPacket;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;

import java.util.ArrayList;
import java.util.List;

public class AutoWither extends Module {
    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    private final Setting<Integer> placeDelay = sgGeneral.add(new IntSetting.Builder()
        .name("place-delay")
        .description("Ticks between each block placement.")
        .defaultValue(1)
        .sliderRange(1, 20)
        .build()
    );

    private final Setting<Integer> blocksPerTick = sgGeneral.add(new IntSetting.Builder()
        .name("blocks-per-tick")
        .description("How many blocks to place each tick.")
        .defaultValue(1)
        .sliderRange(1, 5)
        .build()
    );

    private final Setting<Boolean> render = sgGeneral.add(new BoolSetting.Builder()
        .name("render")
        .description("Render the wither frame while placing.")
        .defaultValue(true)
        .build()
    );

    private final Setting<ShapeMode> shapeMode = sgGeneral.add(new EnumSetting.Builder<ShapeMode>()
        .name("shape-mode")
        .description("How the box is rendered.")
        .defaultValue(ShapeMode.Both)
        .build()
    );

    private final Setting<SettingColor> sideColor = sgGeneral.add(new ColorSetting.Builder()
        .name("side-color")
        .defaultValue(new SettingColor(255, 255, 255, 20))
        .build()
    );

    private final Setting<SettingColor> lineColor = sgGeneral.add(new ColorSetting.Builder()
        .name("line-color")
        .defaultValue(new SettingColor(255, 255, 255, 200))
        .build()
    );

    private final List<BlockPos> witherBlocks = new ArrayList<>();
    private final List<BlockPos> waitingForBreak = new ArrayList<>();
    private int delay = 0;
    private int index = 0;

    private boolean waitingForSlotSync = false;

    public AutoWither() {
        super(Addon.CATEGORY, "AutoWither", "Automatically builds a Wither.");
    }

    @Override
    public void onActivate() {
        witherBlocks.clear();
        waitingForBreak.clear();
        index = 0;
        delay = 0;
        waitingForSlotSync = false;

        Vec3d dir = mc.player.getRotationVec(1.0f);
        Vec3d horizontal = new Vec3d(dir.x, 0, dir.z).normalize().multiply(2.0);
        Vec3d target = mc.player.getPos().add(horizontal).add(0, 2, 0);
        BlockPos basePos = BlockPos.ofFloored(target);

        // Wither body structure
        witherBlocks.add(basePos);
        witherBlocks.add(basePos.west());
        witherBlocks.add(basePos.east());
        witherBlocks.add(basePos.down());
        witherBlocks.add(basePos.up().west());
        witherBlocks.add(basePos.up());
        witherBlocks.add(basePos.up().east());

        int soulCount = 0;
        int skullCount = 0;
        for (int i = 0; i < 36; i++) {
            Item item = mc.player.getInventory().getStack(i).getItem();
            if (item == Items.SOUL_SAND) soulCount += mc.player.getInventory().getStack(i).getCount();
            if (item == Items.WITHER_SKELETON_SKULL) skullCount += mc.player.getInventory().getStack(i).getCount();
        }

        if (soulCount < 4) {
            error("Not enough soul sand (need at least 4).");
            toggle();
            return;
        }

        if (skullCount < 3) {
            error("Need at least 3 Wither Skeleton Skulls.");
            toggle();
            return;
        }

        for (int i = 0; i < 9; i++) {
            Item item = mc.player.getInventory().getStack(i).getItem();
            if (item == Items.SOUL_SAND) {
                mc.player.getInventory().selectedSlot = i;
                break;
            }
        }
    }

    @Override
    public void onDeactivate() {
        witherBlocks.clear();
        waitingForBreak.clear();
        index = 0;
        delay = 0;
        waitingForSlotSync = false;
    }

    @EventHandler
    private void onTick(TickEvent.Post event) {
        if (mc.player == null || mc.world == null) return;

        if (waitingForSlotSync) {
            waitingForSlotSync = false;
            return;
        }

        if (index >= witherBlocks.size()) {
            info("Wither complete.");
            toggle();
            return;
        }

        delay++;
        if (delay < placeDelay.get()) return;

        for (int i = 0; i < blocksPerTick.get() && index < witherBlocks.size(); i++) {
            BlockPos pos = witherBlocks.get(index);

            if (!mc.world.getBlockState(pos).isReplaceable()) {
                if (!waitingForBreak.contains(pos)) {
                    mc.interactionManager.attackBlock(pos, Direction.UP);
                    mc.player.swingHand(Hand.MAIN_HAND);
                    waitingForBreak.add(pos);
                }
                return;
            }

            waitingForBreak.remove(pos);

            Item needed = (index < 4) ? Items.SOUL_SAND : Items.WITHER_SKELETON_SKULL;

            int slotToSelect = -1;
            boolean foundItem = false;
            for (int slot = 0; slot < 9; slot++) {
                if (mc.player.getInventory().getStack(slot).getItem() == needed) {
                    slotToSelect = slot;
                    foundItem = true;
                    break;
                }
            }

            if (!foundItem) {
                error("Missing required block: " + needed.getName().getString());
                toggle();
                return;
            }

            if (mc.player.getInventory().selectedSlot != slotToSelect) {
                mc.player.getInventory().selectedSlot = slotToSelect;
                waitingForSlotSync = true;
                return;
            }

            if (!(mc.player.getMainHandStack().getItem() instanceof BlockItem)) {
                error("Main hand item is not a block.");
                toggle();
                return;
            }

            BlockPos placeOn = pos;
            Direction direction = Direction.UP;

            if (needed == Items.WITHER_SKELETON_SKULL) {
                 // 头颅必须对准下面的灵魂沙顶部
                 placeOn = pos.down();
                direction = Direction.UP;
            }

BlockHitResult bhr = new BlockHitResult(Vec3d.ofCenter(placeOn), direction, placeOn, false);


            mc.player.networkHandler.sendPacket(new PlayerActionC2SPacket(
                PlayerActionC2SPacket.Action.SWAP_ITEM_WITH_OFFHAND, BlockPos.ORIGIN, Direction.DOWN));
            mc.player.networkHandler.sendPacket(new PlayerInteractBlockC2SPacket(
                Hand.OFF_HAND, bhr, mc.player.currentScreenHandler.getRevision() + 2));
            mc.player.networkHandler.sendPacket(new PlayerActionC2SPacket(
                PlayerActionC2SPacket.Action.SWAP_ITEM_WITH_OFFHAND, BlockPos.ORIGIN, Direction.DOWN));
            mc.player.swingHand(Hand.MAIN_HAND);

            index++;
        }

        delay = 0;
    }

    @EventHandler
    private void onRender(Render3DEvent event) {
        if (!render.get()) return;
        for (int i = index; i < witherBlocks.size(); i++) {
            BlockPos pos = witherBlocks.get(i);
            event.renderer.box(pos, sideColor.get(), lineColor.get(), shapeMode.get(), 0);
        }
    }
}
