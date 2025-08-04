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

public class AutoInvertedT extends Module {
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
        .description("Render the T shape while placing.")
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

    private final List<BlockPos> tBlocks = new ArrayList<>();
    private int delay = 0;
    private int index = 0;

    public AutoInvertedT() {
        super(Addon.CATEGORY, "AutoInvertedT", "Places an inverted T shape (e.g. obsidian) using offhand spoofing.");
    }

    @Override
    public void onActivate() {
        tBlocks.clear();
        index = 0;
        delay = 0;

        Vec3d dir = mc.player.getRotationVec(1.0f);
        Vec3d horizontal = new Vec3d(dir.x, 0, dir.z).normalize().multiply(2.0);
        Vec3d target = mc.player.getPos().add(horizontal);
        BlockPos basePos = BlockPos.ofFloored(target);

        // Inverted T structure: middle-bottom block, then left/right above it, and center above that
        tBlocks.add(basePos);                             // bottom of T
        tBlocks.add(basePos.up());                        // vertical center
        tBlocks.add(basePos.up().west());                 // left arm
        tBlocks.add(basePos.up().east());                 // right arm

        // Check for enough blocks
        int count = 0;
        for (int i = 0; i < 36; i++) {
            if (mc.player.getInventory().getStack(i).getItem() == Items.OBSIDIAN) {
                count += mc.player.getInventory().getStack(i).getCount();
            }
        }

        if (count < tBlocks.size()) {
            error("Not enough obsidian (need " + tBlocks.size() + ").");
            toggle();
            return;
        }

        // Pre-select first hotbar slot with obsidian
        for (int i = 0; i < 9; i++) {
            if (mc.player.getInventory().getStack(i).getItem() == Items.OBSIDIAN) {
                mc.player.getInventory().selectedSlot = i;
                break;
            }
        }
    }

    @Override
    public void onDeactivate() {
        tBlocks.clear();
        index = 0;
        delay = 0;
    }

    @EventHandler
    private void onTick(TickEvent.Post event) {
        if (mc.player == null || mc.world == null) return;

        if (index >= tBlocks.size()) {
            info("T shape complete.");
            toggle();
            return;
        }

        delay++;
        if (delay < placeDelay.get()) return;

        for (int i = 0; i < blocksPerTick.get() && index < tBlocks.size(); i++) {
            BlockPos pos = tBlocks.get(index);

            if (!mc.world.getBlockState(pos).isReplaceable()) return;

            // Find obsidian in hotbar
            int slotToUse = -1;
            for (int s = 0; s < 9; s++) {
                if (mc.player.getInventory().getStack(s).getItem() == Items.OBSIDIAN) {
                    slotToUse = s;
                    break;
                }
            }

            if (slotToUse == -1) {
                error("No obsidian in hotbar.");
                toggle();
                return;
            }

            mc.player.getInventory().selectedSlot = slotToUse;

            if (!(mc.player.getMainHandStack().getItem() instanceof BlockItem)) {
                error("Main hand is not a block.");
                toggle();
                return;
            }

            BlockHitResult bhr = new BlockHitResult(Vec3d.ofCenter(pos), Direction.UP, pos, false);

            // Spoof offhand to bypass 2b2t anticheat
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
        for (int i = index; i < tBlocks.size(); i++) {
            event.renderer.box(tBlocks.get(i), sideColor.get(), lineColor.get(), shapeMode.get(), 0);
        }
    }
}
