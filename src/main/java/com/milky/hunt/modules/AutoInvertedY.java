package com.milky.hunt.modules;

import meteordevelopment.meteorclient.events.render.Render3DEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.player.PlayerUtils;
import meteordevelopment.meteorclient.utils.render.color.SettingColor;
import meteordevelopment.meteorclient.utils.render.RenderUtils;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.block.Block;
import net.minecraft.block.Blocks;
import net.minecraft.client.gui.screen.ingame.HandledScreens;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.BlockItem;
import net.minecraft.item.Item;
import net.minecraft.item.Items;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;

import java.util.ArrayList;
import java.util.List;

public class AutoInvertedY extends Module {
    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgRender = settings.createGroup("Render");

    private final Setting<Block> block = sgGeneral.add(new BlockSetting.Builder()
        .name("block")
        .description("The block to use for building the Y shape.")
        .defaultValue(Blocks.OBSIDIAN)
        .build()
    );

    private final Setting<Height> height = sgGeneral.add(new EnumSetting.Builder<Height>()
        .name("height")
        .description("Height of the vertical part of the Y.")
        .defaultValue(Height.MEDIUM)
        .build()
    );

    private final Setting<SettingColor> color = sgRender.add(new ColorSetting.Builder()
        .name("color")
        .description("Color of the render.")
        .defaultValue(new SettingColor(255, 255, 255, 75))
        .build()
    );

    private final List<BlockPos> tBlocks = new ArrayList<>();

    public AutoInvertedY() {
        super("auto-inverted-y", "Automatically places an upside-down Y structure.");
    }

    @Override
    public void onActivate() {
        if (mc.player == null || mc.world == null) return;

        tBlocks.clear();

        Vec3d dir = mc.player.getRotationVec(1.0f);
        Vec3d horizontal = new Vec3d(dir.x, 0, dir.z).normalize().multiply(2.0);
        Vec3d target = mc.player.getPos().add(horizontal).add(0, 2, 0);
        BlockPos basePos = BlockPos.ofFloored(target);

        tBlocks.add(basePos);

        boolean eastWest = Math.abs(dir.z) >= Math.abs(dir.x);

        if (eastWest) {
            tBlocks.add(basePos.west().down());
            tBlocks.add(basePos.east().down());
        } else {
            tBlocks.add(basePos.north().down());
            tBlocks.add(basePos.south().down());
        }

        for (int i = 1; i <= height.get().value; i++) {
            tBlocks.add(basePos.up(i));
        }
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (mc.player == null || mc.world == null) return;

        if (tBlocks.isEmpty()) {
            toggle();
            return;
        }

        Item targetItem = block.get().asItem();
        int slot = findBlockInHotbar(targetItem);
        if (slot == -1) {
            warning("[" + block.get().getName().getString() + "] 不足");
            toggle();
            return;
        }

        int prevSlot = mc.player.getInventory().selectedSlot;
        if (prevSlot != slot) PlayerUtils.selectSlot(slot);

        for (BlockPos pos : tBlocks) {
            if (!mc.world.getBlockState(pos).isAir()) continue;

            PlayerUtils.place(pos, event, true, false);
        }

        if (prevSlot != slot) PlayerUtils.selectSlot(prevSlot);
        toggle();
    }

    @EventHandler
    private void onRender(Render3DEvent event) {
        if (tBlocks.isEmpty()) return;

        for (BlockPos pos : tBlocks) {
            RenderUtils.drawBox(event, pos, color.get(), false);
        }
    }

    private int findBlockInHotbar(Item item) {
        PlayerInventory inv = mc.player.getInventory();
        for (int i = 0; i < 9; i++) {
            if (inv.getStack(i).getItem() == item) return i;
        }
        return -1;
    }

    public enum Height {
        MEDIUM(2),
        LARGE(3),
        EXTRA_LARGE(4);

        public final int value;
        Height(int value) { this.value = value; }
    }
}
