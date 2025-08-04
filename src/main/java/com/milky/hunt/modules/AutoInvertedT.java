package com.milky.hunt.modules;

import com.milky.hunt.Addon;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.player.PlayerUtils;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.block.Block;
import net.minecraft.block.Blocks;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.item.Item;
import net.minecraft.item.Items;
import net.minecraft.network.packet.c2s.play.PlayerInteractBlockC2SPacket;
import net.minecraft.network.packet.c2s.play.PlayerInteractItemC2SPacket;
import net.minecraft.network.packet.c2s.play.PlayerMoveC2SPacket;
import net.minecraft.network.packet.c2s.play.PlayerSwingC2SPacket;
import net.minecraft.network.packet.c2s.play.UpdateSelectedSlotC2SPacket;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;

public class AutoInvertedT extends Module {
    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    private final Setting<Integer> blocksPerTick = sgGeneral.add(new IntSetting.Builder()
        .name("blocks-per-tick")
        .description("How many blocks to place per tick.")
        .defaultValue(1)
        .min(1)
        .sliderMax(5)
        .build()
    );

    private final Setting<Integer> delay = sgGeneral.add(new IntSetting.Builder()
        .name("place-delay")
        .description("Delay between placement attempts in ticks.")
        .defaultValue(2)
        .min(0)
        .sliderMax(20)
        .build()
    );

    private BlockPos base;
    private int ticks;
    private int index;
    private final BlockPos[] offsets = new BlockPos[] {
        new BlockPos(0, 0, 0),
        new BlockPos(1, 0, 0),
        new BlockPos(-1, 0, 0),
        new BlockPos(0, 0, 1),
        new BlockPos(0, 1, 0)
    };

    public AutoInvertedT() {
        super(Addon.CATEGORY, "AutoInvertedT", "Automatically places an upside-down T of obsidian blocks.");
    }

    @Override
    public void onActivate() {
        ClientPlayerEntity player = mc.player;
        if (player == null) return;

        // Center base position 2 blocks ahead
        Vec3d look = player.getRotationVec(1.0F);
        BlockPos center = player.getBlockPos().add(look.x * 2, 0, look.z * 2);

        base = center;
        ticks = 0;
        index = 0;
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (mc.player == null || mc.world == null) return;

        ticks++;
        if (ticks < delay.get()) return;
        ticks = 0;

        int placed = 0;
        while (index < offsets.length && placed < blocksPerTick.get()) {
            BlockPos target = base.add(offsets[index]);
            if (!mc.world.getBlockState(target).isReplaceable()) {
                index++;
                continue;
            }

            int slot = findObsidianInHotbar();
            if (slot == -1) {
                error("No obsidian found in hotbar.");
                toggle();
                return;
            }

            mc.player.getInventory().selectedSlot = slot;

            placeBlock(target);
            index++;
            placed++;
        }

        if (index >= offsets.length) toggle();
    }

    private int findObsidianInHotbar() {
        for (int i = 0; i < 9; i++) {
            Item item = mc.player.getInventory().getStack(i).getItem();
            if (item == Items.OBSIDIAN) return i;
        }
        return -1;
    }

    private void placeBlock(BlockPos pos) {
        Vec3d hitPos = Vec3d.ofCenter(pos);
        BlockHitResult hitResult = new BlockHitResult(hitPos, Direction.UP, pos, false);

        // Swap to offhand
        mc.player.networkHandler.sendPacket(new UpdateSelectedSlotC2SPacket(40));

        // Use item in offhand (bypass anticheat)
        mc.player.networkHandler.sendPacket(new PlayerInteractBlockC2SPacket(Hand.OFF_HAND, hitResult, 0));

        // Swap back
        mc.player.networkHandler.sendPacket(new UpdateSelectedSlotC2SPacket(mc.player.getInventory().selectedSlot));

        // Swing main hand to fake legit
        mc.player.networkHandler.sendPacket(new PlayerSwingC2SPacket(Hand.MAIN_HAND));
    }
}
