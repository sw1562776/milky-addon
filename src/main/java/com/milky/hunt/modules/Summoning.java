package com.milky.hunt.modules;

import com.milky.hunt.Addon;
import meteordevelopment.meteorclient.events.render.Render3DEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.renderer.ShapeMode;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.player.InvUtils;
import meteordevelopment.meteorclient.utils.render.RenderUtils;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.block.Block;
import net.minecraft.block.Blocks;
import net.minecraft.item.Items;
import net.minecraft.util.Hand;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;

import java.util.ArrayList;
import java.util.List;

public class AutoGolemBuilder extends Module {
    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    private final EnumSetting<GolemType> type = sgGeneral.add(new EnumSetting.Builder<GolemType>()
        .name("type")
        .description("The type of golem to build.")
        .defaultValue(GolemType.Snowman)
        .build()
    );

    private final BoolSetting loop = sgGeneral.add(new BoolSetting.Builder()
        .name("loop")
        .description("Continuously builds golems.")
        .defaultValue(false)
        .build()
    );

    private final IntSetting loopDelay = sgGeneral.add(new IntSetting.Builder()
        .name("loop-delay")
        .description("Delay between golems (ticks).")
        .defaultValue(20)
        .min(1)
        .sliderMax(100)
        .visible(loop::get)
        .build()
    );

    private final BoolSetting render = sgGeneral.add(new BoolSetting.Builder()
        .name("render")
        .description("Render the blocks to be placed.")
        .defaultValue(true)
        .build()
    );

    // 通用状态字段
    private final List<BlockPos> golemBlocks = new ArrayList<>();
    private boolean hasBaseBlock = false;
    private boolean hasHeadBlock = false;
    private int currentIndex = 0;
    private long lastPlaceTime = 0;

    private boolean waitingForNextLoop = false;
    private int loopDelayTimer = 0;

    private boolean waitingForSlotSync = false;

    public AutoGolemBuilder() {
        super(Addon.CATEGORY, "auto-golem-builder", "Automatically builds golems of different types.");
    }

    @Override
    public void onActivate() {
        switch (type.get()) {
            case Snowman -> activateSnowman();
            case Iron -> activateIronGolem();
            case Wither -> activateWither();
        }
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        switch (type.get()) {
            case Snowman -> tickSnowman();
            case Iron -> tickIronGolem();
            case Wither -> tickWither();
        }
    }

    @EventHandler
    private void onRender(Render3DEvent event) {
        if (!render.get()) return;
        for (int i = 0; i < golemBlocks.size(); i++) {
            RenderUtils.drawBox(event.matrixStack, golemBlocks.get(i), i == currentIndex ? 1 : 0.5, 1, 1, 0.5, ShapeMode.Lines, 0);
        }
    }

    private void activateSnowman() {
        golemBlocks.clear();
        currentIndex = 0;
        hasBaseBlock = InvUtils.findInHotbar(item -> item.getItem() == Items.SNOW_BLOCK).found();
        hasHeadBlock = InvUtils.findInHotbar(item -> item.getItem() == Items.CARVED_PUMPKIN).found();

        if (!hasBaseBlock || !hasHeadBlock) {
            warning("Missing required blocks.");
            toggle();
            return;
        }

        BlockPos basePos = mc.player.getBlockPos().up();
        golemBlocks.add(basePos);
        golemBlocks.add(basePos.up());
        golemBlocks.add(basePos.up(2));
    }

    private void tickSnowman() {
        if (waitingForNextLoop) {
            loopDelayTimer--;
            if (loopDelayTimer <= 0) {
                waitingForNextLoop = false;
                activateSnowman();
            }
            return;
        }

        if (currentIndex >= golemBlocks.size()) {
            if (loop.get()) {
                waitingForNextLoop = true;
                loopDelayTimer = loopDelay.get();
            } else toggle();
            return;
        }

        BlockPos pos = golemBlocks.get(currentIndex);
        Block targetBlock = mc.world.getBlockState(pos).getBlock();

        if (!targetBlock.isReplaceable()) {
            info("Block already placed. Skipping.");
            currentIndex++;
            return;
        }

        if (waitingForSlotSync) {
            waitingForSlotSync = false;
            return;
        }

        boolean isPumpkin = currentIndex == 2;
        boolean hasItem = InvUtils.swap().to(isPumpkin ? Items.CARVED_PUMPKIN : Items.SNOW_BLOCK);
        if (!hasItem) {
            warning("Required item missing during build.");
            toggle();
            return;
        }

        waitingForSlotSync = true;
        mc.interactionManager.interactBlock(mc.player, Hand.MAIN_HAND, new net.minecraft.util.hit.BlockHitResult(mc.player.getPos(), Direction.UP, pos.down(), false));
        currentIndex++;
    }

    private void activateIronGolem() {
        // TODO: Add iron golem placement pattern here
    }

    private void tickIronGolem() {
        // TODO: Add iron golem tick logic
    }

    private void activateWither() {
        // TODO: Add wither placement pattern here
    }

    private void tickWither() {
        // TODO: Add wither tick logic
    }

    public enum GolemType {
        Snowman,
        Iron,
        Wither
    }
}
