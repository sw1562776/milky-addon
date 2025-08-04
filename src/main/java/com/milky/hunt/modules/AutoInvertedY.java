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
import meteordevelopment.meteorclient.settings.BlockSetting;
import net.minecraft.block.Block;
import net.minecraft.block.Blocks;

import java.util.ArrayList;
import java.util.List;

public class AutoInvertedY extends Module {
    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    private enum THeight {
        Medium, Large, Extra_Large
    }

    private final Setting<Block> block = sgGeneral.add(new BlockSetting.Builder()
    .name("block")
    .description("The block to use when placing the inverted Y.")
    .defaultValue(Blocks.)
    .build()
    );

    private final Setting<THeight> height = sgGeneral.add(new EnumSetting.Builder<THeight>()
        .name("height")
        .description("Height of the inverted T.")
        .defaultValue(THeight.Medium)
        .build()
    );

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
        .description("Render the Y shape while placing.")
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

    public AutoInvertedY() {
        super(Addon.CATEGORY, "AutoInvertedY", "Places an inverted Y shape using your favorite block.");
    }

    @Override
    public void onActivate() {
        tBlocks.clear();
        index = 0;
        delay = 0;

        Vec3d dir = mc.player.getRotationVec(1.0f);
        Vec3d horizontal = new Vec3d(dir.x, 0, dir.z).normalize().multiply(2.0);
        Vec3d target = mc.player.getPos().add(horizontal).add(0, 2, 0);
        BlockPos basePos = BlockPos.ofFloored(target);

        // Horizontal bar
        boolean eastWest = Math.abs(dir.z) >= Math.abs(dir.x);  // true = wings on west/east
        // Horizontal bar
        tBlocks.add(basePos);
        if (eastWest) {
            // Player is facing mostly north/south → use west/east wings
            tBlocks.add(basePos.west().down());
            tBlocks.add(basePos.east().down());
        } else {
            // Player is facing mostly east/west → use north/south wings
            tBlocks.add(basePos.north().down());
            tBlocks.add(basePos.south().down());
        }

        // Vertical stem upward
        int stemHeight = switch (height.get()) {
            case Medium -> 1;
            case Large -> 2;
            case Extra_Large -> 3;
        };

        for (int i = 1; i <= stemHeight; i++) {
            tBlocks.add(basePos.up(i));
        }
        
        Item targetItem = block.get().asItem();
        
        int count = 0;
        for (int i = 0; i < 36; i++) {
            if (mc.player.getInventory().getStack(i).getItem() == block.get().asItem()) {
                count += mc.player.getInventory().getStack(i).getCount();
            }
        }

        if (count < tBlocks.size()) {
            error("Not enough " + block.get().asItem().getName().getString() + " (need " + tBlocks.size() + ").");
            toggle();
            return;
        }

        // Pre-select blocks in hotbar
        for (int i = 0; i < 9; i++) {
            if (mc.player.getInventory().getStack(i).getItem() == block.get().asItem()) {
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
            info("Y shape complete.");
            toggle();
            return;
        }

        delay++;
        if (delay < placeDelay.get()) return;

        for (int i = 0; i < blocksPerTick.get() && index < tBlocks.size(); i++) {
            BlockPos pos = tBlocks.get(index);

            if (!mc.world.getBlockState(pos).isReplaceable()) return;

            // Find block
            int slotToUse = -1;
            for (int s = 0; s < 9; s++) {
                if (mc.player.getInventory().getStack(s).getItem() == Items.block.get().asItem()) {
                    slotToUse = s;
                    break;
                }
            }

            if (slotToUse == -1) {
                error("No "+ block.get().asItem().getName().getString() + " in hotbar.");
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

            // Spoof offhand to bypass anti-cheat
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
