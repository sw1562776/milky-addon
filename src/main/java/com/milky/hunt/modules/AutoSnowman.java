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

        //BlockPos basePos = mc.player.getBlockPos().offset(mc.player.getHorizontalFacing(), 1).up(2);
        
        // 获取玩家面朝方向的单位向量（包含 pitch/yaw，但我们只取水平分量）
        Vec3d dir = mc.player.getRotationVec(1.0f);
        Vec3d horizontal = new Vec3d(dir.x, 0, dir.z).normalize().multiply(2.0);  // 水平方向欧氏距离为2
        // 计算目标位置（偏移后的位置向上2格）
        Vec3d target = mc.player.getPos().add(horizontal).add(0, 2, 0);  // 向上2格
        // 转为整数方块坐标
        BlockPos basePos = new BlockPos(target);
        

        // 雪傀儡结构：两块雪块 + 一块雕刻南瓜
        snowmanBlocks.add(basePos);
        snowmanBlocks.add(basePos.up());
        snowmanBlocks.add(basePos.up(2));

        // 检查材料
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

        // 自动切到雪块
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

        // 循环等待
        if (waitingForNextLoop) {
            loopDelayTimer++;
            if (loopDelayTimer >= loopDelay.get()) {
                waitingForNextLoop = false;
                onActivate();
            }
            return;
        }

        // 雪傀儡完成
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

        for (int i = 0; i < blocksPerTick.get() && index < snowmanBlocks.size(); i++, index++) {
            BlockPos pos = snowmanBlocks.get(index);

            // 清除已有方块
            if (!mc.world.getBlockState(pos).isReplaceable()) {
                if (!waitingForBreak.contains(pos)) {
                    mc.interactionManager.attackBlock(pos, Direction.UP);
                    mc.player.swingHand(Hand.MAIN_HAND);
                    waitingForBreak.add(pos);
                }
                index--;
                return;
            }

            waitingForBreak.remove(pos);

            // 第三块是雕刻南瓜
            Item needed = (index < 2) ? Items.SNOW_BLOCK : Items.CARVED_PUMPKIN;

            // 自动切物品
            boolean foundItem = false;
            for (int slot = 0; slot < 9; slot++) {
                if (mc.player.getInventory().getStack(slot).getItem() == needed) {
                    mc.player.getInventory().selectedSlot = slot;
                    foundItem = true;
                    break;
                }
            }

            if (!foundItem) {
                error("Missing required block: " + needed.getName().getString());
                toggle();
                return;
            }

            if (!(mc.player.getMainHandStack().getItem() instanceof BlockItem)) {
                error("Main hand item is not a block.");
                toggle();
                return;
            }

            BlockHitResult bhr = new BlockHitResult(Vec3d.ofCenter(pos), Direction.UP, pos, false);
            mc.interactionManager.interactBlock(mc.player, Hand.MAIN_HAND, bhr);
            mc.player.swingHand(Hand.MAIN_HAND);
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
