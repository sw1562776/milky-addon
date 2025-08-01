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

public class AutoSnowman extends Module {
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

    private final Setting<Boolean> continuous = sgGeneral.add(new BoolSetting.Builder()
        .name("continuous")
        .description("Continuously builds snow golems if materials are available.")
        .defaultValue(false)
        .build()
    );

    private final Setting<Integer> loopDelay = sgGeneral.add(new IntSetting.Builder()
        .name("loop-delay")
        .description("Ticks to wait between snow golems when in continuous mode.")
        .defaultValue(20)
        .sliderRange(0, 200)
        .visible(continuous::get)
        .build()
    );

    private final Setting<Boolean> render = sgGeneral.add(new BoolSetting.Builder()
        .name("render")
        .description("Render the snowman frame while placing.")
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

    private final List<BlockPos> snowmanBlocks = new ArrayList<>();
    private final List<BlockPos> waitingForBreak = new ArrayList<>();
    private int delay = 0;
    private int index = 0;

    private boolean waitingForNextLoop = false;
    private int loopDelayTimer = 0;

    public AutoSnowman() {
        super(Addon.CATEGORY, "AutoSnowman", "Automatically builds a snow golem.");
    }

    @Override
    public void onActivate() {
        snowmanBlocks.clear();
        waitingForBreak.clear();
        index = 0;
        delay = 0;
        loopDelayTimer = 0;
        waitingForNextLoop = false;

        Vec3d dir = mc.player.getRotationVec(1.0f);
        Vec3d horizontal = new Vec3d(dir.x, 0, dir.z).normalize().multiply(2.0);
        Vec3d target = mc.player.getPos().add(horizontal).add(0, 2, 0);
        BlockPos basePos = BlockPos.ofFloored(target);

        snowmanBlocks.add(basePos);
        snowmanBlocks.add(basePos.up());
        snowmanBlocks.add(basePos.up(2));

        int snowBlockCount = 0;
        int pumpkinCount = 0;
        for (int i = 0; i < 36; i++) {
            Item item = mc.player.getInventory().getStack(i).getItem();
            if (item == Items.SNOW_BLOCK) snowBlockCount += mc.player.getInventory().getStack(i).getCount();
            if (item == Items.CARVED_PUMPKIN) pumpkinCount += mc.player.getInventory().getStack(i).getCount();
        }

        if (snowBlockCount < 2) {
            error("Not enough snow blocks (need at least 2).");
            toggle();
            return;
        }

        if (pumpkinCount < 1) {
            error("Need at least 1 carved pumpkin.");
            toggle();
            return;
        }

        // 预选雪块槽放主手（便于先放雪块）
        for (int i = 0; i < 9; i++) {
            Item item = mc.player.getInventory().getStack(i).getItem();
            if (item == Items.SNOW_BLOCK) {
                mc.player.getInventory().selectedSlot = i;
                break;
            }
        }
    }

    @Override
    public void onDeactivate() {
        snowmanBlocks.clear();
        waitingForBreak.clear();
        index = 0;
        delay = 0;
        loopDelayTimer = 0;
        waitingForNextLoop = false;
    }

    @EventHandler
    private void onTick(TickEvent.Post event) {
        if (mc.player == null || mc.world == null) return;

        if (waitingForNextLoop) {
            loopDelayTimer++;
            if (loopDelayTimer >= loopDelay.get()) {
                waitingForNextLoop = false;
                onActivate();
            }
            return;
        }

        if (index >= snowmanBlocks.size()) {
            info("Snowman complete.");
            if (continuous.get()) {
                waitingForNextLoop = true;
                loopDelayTimer = 0;
            } else {
                toggle();
            }
            return;
        }

        delay++;
        if (delay < placeDelay.get()) return;

        // 放雪块部分，0 和 1
        while (index < 2 && blocksPerTick.get() > 0) {
            BlockPos pos = snowmanBlocks.get(index);

            if (!mc.world.getBlockState(pos).isReplaceable()) {
                if (!waitingForBreak.contains(pos)) {
                    mc.interactionManager.attackBlock(pos, Direction.UP);
                    mc.player.swingHand(Hand.MAIN_HAND);
                    waitingForBreak.add(pos);
                }
                // 不移动 index，等待下一 tick
                delay = 0;
                return;
            }

            waitingForBreak.remove(pos);

            // 找雪块槽
            int snowSlot = -1;
            for (int slot = 0; slot < 9; slot++) {
                if (mc.player.getInventory().getStack(slot).getItem() == Items.SNOW_BLOCK) {
                    snowSlot = slot;
                    break;
                }
            }

            if (snowSlot == -1) {
                error("Missing required block: snow block");
                toggle();
                return;
            }

            mc.player.getInventory().selectedSlot = snowSlot;

            // 副手也放雪块
            // 交换主副手物品保证副手持雪块
            mc.player.networkHandler.sendPacket(new PlayerActionC2SPacket(
                PlayerActionC2SPacket.Action.SWAP_ITEM_WITH_OFFHAND, BlockPos.ORIGIN, Direction.DOWN));

            BlockHitResult bhr = new BlockHitResult(Vec3d.ofCenter(pos), Direction.UP, pos, false);
            mc.player.networkHandler.sendPacket(new PlayerInteractBlockC2SPacket(
                Hand.OFF_HAND, bhr, mc.player.currentScreenHandler.getRevision() + 2));

            // 再交换回主副手物品，保持主手持雪块
            mc.player.networkHandler.sendPacket(new PlayerActionC2SPacket(
                PlayerActionC2SPacket.Action.SWAP_ITEM_WITH_OFFHAND, BlockPos.ORIGIN, Direction.DOWN));

            mc.player.swingHand(Hand.MAIN_HAND);

            index++;
            blocksPerTick.set(blocksPerTick.get() - 1); // 消耗一个放置次数
        }

        if (index == 2 && blocksPerTick.get() > 0) {
            // 放南瓜部分（顶层）

            BlockPos pos = snowmanBlocks.get(index);

            if (!mc.world.getBlockState(pos).isReplaceable()) {
                if (!waitingForBreak.contains(pos)) {
                    mc.interactionManager.attackBlock(pos, Direction.UP);
                    mc.player.swingHand(Hand.MAIN_HAND);
                    waitingForBreak.add(pos);
                }
                delay = 0;
                return;
            }

            waitingForBreak.remove(pos);

            // 找南瓜槽副手
            int pumpkinSlot = -1;
            for (int slot = 0; slot < 9; slot++) {
                if (mc.player.getInventory().getStack(slot).getItem() == Items.CARVED_PUMPKIN) {
                    pumpkinSlot = slot;
                    break;
                }
            }

            if (pumpkinSlot == -1) {
                error("Missing required block: carved pumpkin");
                toggle();
                return;
            }

            // 找主手槽，避免南瓜（随便找非南瓜方块）
            int mainHandSlot = -1;
            for (int slot = 0; slot < 9; slot++) {
                Item item = mc.player.getInventory().getStack(slot).getItem();
                if (item instanceof BlockItem && item != Items.CARVED_PUMPKIN) {
                    mainHandSlot = slot;
                    break;
                }
            }
            if (mainHandSlot == -1) mainHandSlot = 0;

            mc.player.getInventory().selectedSlot = mainHandSlot;

            // 确保副手持南瓜，交换主副手物品
            if (mc.player.getInventory().selectedSlot != pumpkinSlot) {
                mc.player.networkHandler.sendPacket(new PlayerActionC2SPacket(
                    PlayerActionC2SPacket.Action.SWAP_ITEM_WITH_OFFHAND, BlockPos.ORIGIN, Direction.DOWN));
            }

            BlockHitResult bhr = new BlockHitResult(Vec3d.ofCenter(pos), Direction.UP, pos, false);
            mc.player.networkHandler.sendPacket(new PlayerInteractBlockC2SPacket(
                Hand.OFF_HAND, bhr, mc.player.currentScreenHandler.getRevision() + 2));

            // 再交换回主副手物品，保持主手不变
            mc.player.networkHandler.sendPacket(new PlayerActionC2SPacket(
                PlayerActionC2SPacket.Action.SWAP_ITEM_WITH_OFFHAND, BlockPos.ORIGIN, Direction.DOWN));

            mc.player.swingHand(Hand.MAIN_HAND);

            index++;
            blocksPerTick.set(blocksPerTick.get() - 1);
        }

        delay = 0;
    }

    @EventHandler
    private void onRender(Render3DEvent event) {
        if (!render.get()) return;
        for (int i = index; i < snowmanBlocks.size(); i++) {
            BlockPos pos = snowmanBlocks.get(i);
            event.renderer.box(pos, sideColor.get(), lineColor.get(), shapeMode.get(), 0);
        }
    }
}
