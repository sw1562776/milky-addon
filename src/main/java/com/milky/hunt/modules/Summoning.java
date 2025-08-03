package com.milky.hunt.modules;

import com.milky.hunt.Addon;
import meteordevelopment.meteorclient.events.render.Render3DEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.renderer.ShapeMode;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.player.PlayerUtils;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.block.Block;
import net.minecraft.block.Blocks;
import net.minecraft.util.Hand;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.math.Vec3i;

import java.util.*;

public class Summoning extends Module {
    private enum GolemType { Snowman, Ironman, Wither }

    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    private final Setting<GolemType> type = sgGeneral.add(new EnumSetting.Builder<GolemType>()
        .name("type")
        .description("The type of entity to summon.")
        .defaultValue(GolemType.Snowman)
        .build()
    );

    private final Setting<Boolean> continuous = sgGeneral.add(new BoolSetting.Builder()
        .name("continuous")
        .description("Continuously builds if materials are available.")
        .defaultValue(false)
        .visible(() -> type.get() != GolemType.Wither)
        .build()
    );

    private final Setting<Integer> loopDelay = sgGeneral.add(new IntSetting.Builder()
        .name("loop-delay")
        .description("Delay between spawns.")
        .defaultValue(20)
        .sliderRange(0, 200)
        .visible(() -> type.get() != GolemType.Wither && continuous.get())
        .build()
    );

    private final Setting<Integer> placeDelay = sgGeneral.add(new IntSetting.Builder()
        .name("place-delay")
        .description("Delay between block placements.")
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
        .description("Render the structure.")
        .defaultValue(true)
        .build()
    );

    private final Setting<ShapeMode> shapeMode = sgGeneral.add(new EnumSetting.Builder<ShapeMode>()
        .name("shape-mode")
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

    private final List<BlockPos> structure = new ArrayList<>();
    private final Map<BlockPos, Block> blockMap = new LinkedHashMap<>();
    private int delay, index, loopTimer;

    public Summoning() {
        super(Addon.CATEGORY, "summoning", "Automatically builds snowmen, iron golems, or withers.");
    }

    @Override
    public void onActivate() {
        structure.clear();
        blockMap.clear();
        delay = index = loopTimer = 0;

        BlockPos base = mc.player.getBlockPos().add(0, -1, 0);
        switch (type.get()) {
            case Snowman -> {
                blockMap.put(base.up(), Blocks.SNOW_BLOCK);
                blockMap.put(base.up(2), Blocks.SNOW_BLOCK);
                blockMap.put(base.up(3), Blocks.CARVED_PUMPKIN);
            }
            case Ironman -> {
                blockMap.put(base, Blocks.IRON_BLOCK);
                blockMap.put(base.up(), Blocks.IRON_BLOCK);
                blockMap.put(base.up(2), Blocks.CARVED_PUMPKIN);
                blockMap.put(base.east(), Blocks.IRON_BLOCK);
                blockMap.put(base.west(), Blocks.IRON_BLOCK);
            }
            case Wither -> {
                blockMap.put(base.up(), Blocks.SOUL_SAND);
                blockMap.put(base.up(2), Blocks.SOUL_SAND);
                blockMap.put(base.up(3), Blocks.WITHER_SKELETON_SKULL);
                blockMap.put(base.east(), Blocks.SOUL_SAND);
                blockMap.put(base.west(), Blocks.SOUL_SAND);
            }
        }
        structure.addAll(blockMap.keySet());
    }

    @EventHandler
    private void onTick(TickEvent.Post event) {
        if (index >= structure.size()) {
            if (continuous.get() && type.get() != GolemType.Wither) {
                if (loopTimer++ >= loopDelay.get()) {
                    onActivate();
                }
            } else {
                toggle();
            }
            return;
        }

        if (delay++ < placeDelay.get()) return;
        delay = 0;

        int blocksThisTick = 0;
        while (index < structure.size() && blocksThisTick < blocksPerTick.get()) {
            BlockPos pos = structure.get(index);
            Block block = blockMap.get(pos);

            if (mc.world.getBlockState(pos).isReplaceable()) {
                int slot = PlayerUtils.findBlock(block);
                if (slot != -1) {
                    PlayerUtils.placeBlock(pos, Hand.MAIN_HAND, slot);
                    blocksThisTick++;
                }
            }
            index++;
        }
    }

    @EventHandler
    private void onRender(Render3DEvent event) {
        if (!render.get()) return;

        for (int i = index; i < structure.size(); i++) {
            BlockPos pos = structure.get(i);
            event.renderer.box(pos, sideColor.get(), lineColor.get(), shapeMode.get(), 0);
        }
    }
} 
