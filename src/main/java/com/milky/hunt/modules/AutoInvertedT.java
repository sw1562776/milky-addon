package com.milky.hunt.modules;

import com.milky.hunt.Addon;
import meteordevelopment.meteorclient.events.render.Render3DEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.player.InvUtils;
import meteordevelopment.meteorclient.utils.player.Rotations;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.block.Block;
import net.minecraft.block.Blocks;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.item.Item;
import net.minecraft.item.Items;
import net.minecraft.network.packet.c2s.play.PlayerInteractBlockC2SPacket;
import net.minecraft.network.packet.c2s.play.PlayerSwingC2SPacket;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.math.Vec3i;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

public class AutoInvertedT extends Module {
    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    private final Setting<Boolean> rotate = sgGeneral.add(new BoolSetting.Builder()
        .name("rotate")
        .description("Faces the blocks before placing them.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> swingHand = sgGeneral.add(new BoolSetting.Builder()
        .name("swing-hand")
        .description("Swings your hand when placing blocks.")
        .defaultValue(true)
        .build()
    );

    private final List<BlockPos> structure = new ArrayList<>();
    private int index;
    private boolean firstRun;

    public AutoInvertedT() {
        super(Addon.CATEGORY, "auto-inverted-t", "Automatically places upside-down T made of obsidian.");
    }

    @Override
    public void onActivate() {
        structure.clear();
        index = 0;
        firstRun = true;

        BlockPos base = mc.player.getBlockPos().up();

        // T upside-down shape: top block first, then sides
        structure.add(base.up(2)); // top obsidian
        structure.add(base.up()); // center
        structure.add(base.east()); // right arm
        structure.add(base.west()); // left arm
    }

    @EventHandler
    private void onTick(TickEvent.Post event) {
        if (index >= structure.size()) return;

        int obsidianSlot = InvUtils.findInHotbar(Items.OBSIDIAN).getSlot();
        if (obsidianSlot == -1) return;

        BlockPos pos = structure.get(index);
        if (!mc.world.getBlockState(pos).isReplaceable()) {
            index++;
            return;
        }

        placeBlock(pos, obsidianSlot);
        index++;
    }

    private void placeBlock(BlockPos pos, int slot) {
        int prevSlot = mc.player.getInventory().selectedSlot;
        mc.player.getInventory().selectedSlot = slot;

        Vec3d eyePos = mc.player.getEyePos();
        for (Direction dir : Direction.values()) {
            BlockPos neighbor = pos.offset(dir);
            if (!mc.world.getBlockState(neighbor).isReplaceable()) {
                Vec3d hitVec = Vec3d.ofCenter(neighbor).add(Vec3d.of(dir.getVector()).multiply(0.5));
                if (rotate.get()) Rotations.rotate(Rotations.getYaw(hitVec), Rotations.getPitch(hitVec));

                mc.player.networkHandler.sendPacket(new PlayerInteractBlockC2SPacket(Hand.MAIN_HAND,
                    new BlockHitResult(hitVec, dir.getOpposite(), neighbor, false), 0));

                if (swingHand.get()) mc.player.networkHandler.sendPacket(new PlayerSwingC2SPacket(Hand.MAIN_HAND));

                break;
            }
        }

        mc.player.getInventory().selectedSlot = prevSlot;
    }

    @EventHandler
    private void onRender(Render3DEvent event) {
        MatrixStack matrices = event.matrices;
        structure.forEach(pos -> event.renderer.box(pos, 0, 255, 255, 35));
    }
}
