package com.milky.hunt.modules;

import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.Settings;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.systems.modules.ModuleCategory;
import meteordevelopment.meteorclient.utils.player.PlayerUtils;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.block.Blocks;
import net.minecraft.item.Items;
import net.minecraft.item.ItemStack;
import net.minecraft.network.packet.c2s.play.PlayerInteractItemC2SPacket;
import net.minecraft.network.packet.c2s.play.PlayerInteractBlockC2SPacket;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;

public class AutoSnowman extends Module {
    private enum Phase {
        PLACE_SNOW_1, PLACE_SNOW_2, DELAY_BEFORE_PUMPKIN, PLACE_PUMPKIN
    }

    private final Settings settings = this.settings;
    private final BoolSetting autoDisable = settings.get(new BoolSetting.Builder()
        .name("auto-disable")
        .description("Disable after placing one snowman.")
        .defaultValue(true)
        .build());

    private Phase phase = null;
    private BlockPos basePos = null;
    private int delayTicks = 0;

    public AutoSnowman() {
        super(new ModuleCategory("Hunt"), "AutoSnowman", "Automatically places a snowman (2 snow blocks + carved pumpkin).");
    }

    @Override
    public void onActivate() {
        BlockPos playerPos = PlayerUtils.getBlockPos();
        basePos = playerPos.up();
        phase = Phase.PLACE_SNOW_1;
        delayTicks = 0;
    }

    @Override
    public void onDeactivate() {
        basePos = null;
        phase = null;
        delayTicks = 0;
    }

    @EventHandler
    private void onTick(TickEvent.Post event) {
        if (mc.player == null || mc.world == null || basePos == null) return;

        switch (phase) {
            case PLACE_SNOW_1 -> {
                if (placeBlock(basePos, Items.SNOW_BLOCK)) {
                    phase = Phase.PLACE_SNOW_2;
                }
            }

            case PLACE_SNOW_2 -> {
                if (placeBlock(basePos.up(), Items.SNOW_BLOCK)) {
                    phase = Phase.DELAY_BEFORE_PUMPKIN;
                    delayTicks = 1; // 延迟1tick，等主手切换到雕刻南瓜
                }
            }

            case DELAY_BEFORE_PUMPKIN -> {
                if (delayTicks > 0) {
                    delayTicks--;
                } else {
                    phase = Phase.PLACE_PUMPKIN;
                }
            }

            case PLACE_PUMPKIN -> {
                if (placeBlockWithOffhand(basePos.up(2), Items.CARVED_PUMPKIN)) {
                    if (autoDisable.get()) toggle();
                    else {
                        basePos = basePos.add(1, 0, 0); // 可选：继续自动放下一个
                        phase = Phase.PLACE_SNOW_1;
                    }
                }
            }
        }
    }

    private boolean placeBlock(BlockPos pos, net.minecraft.item.Item requiredItem) {
        int slot = findHotbarSlot(requiredItem);
        if (slot == -1) return false;

        mc.player.getInventory().selectedSlot = slot;

        Vec3d hitPos = Vec3d.ofCenter(pos.down()); // 点击方块上面
        BlockHitResult bhr = new BlockHitResult(hitPos, Direction.UP, pos.down(), false);

        mc.player.networkHandler.sendPacket(new PlayerInteractBlockC2SPacket(Hand.MAIN_HAND, bhr, mc.player.getWorld().getSyncId()));
        return true;
    }

    private boolean placeBlockWithOffhand(BlockPos pos, net.minecraft.item.Item requiredItem) {
        ItemStack offhand = mc.player.getOffHandStack();
        if (offhand == null || offhand.getItem() != requiredItem) return false;

        Vec3d hitPos = Vec3d.ofCenter(pos.down());
        BlockHitResult bhr = new BlockHitResult(hitPos, Direction.UP, pos.down(), false);

        mc.player.networkHandler.sendPacket(new PlayerInteractBlockC2SPacket(Hand.OFF_HAND, bhr, mc.player.getWorld().getSyncId()));
        return true;
    }

    private int findHotbarSlot(net.minecraft.item.Item item) {
        for (int i = 0; i < 9; i++) {
            if (mc.player.getInventory().getStack(i).getItem() == item) return i;
        }
        return -1;
    }
}
