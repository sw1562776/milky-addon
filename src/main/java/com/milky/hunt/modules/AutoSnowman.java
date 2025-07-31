package com.milky.hunt.modules;

import net.minecraft.block.Blocks;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.item.Items;
import net.minecraft.item.ItemStack;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.math.Vec3i;

public class AutoSnowman {
    private final MinecraftClient mc = MinecraftClient.getInstance();

    public void spawnSnowman() {
        if (mc.player == null || mc.world == null) return;

        ClientPlayerEntity player = mc.player;

        // 获取玩家朝向的单位向量
        Vec3d lookVec = player.getRotationVecClient().normalize();

        // 向朝向方向偏移2格
        Vec3d offsetPos = player.getPos().add(lookVec.multiply(2));

        // 再往上偏移2格
        BlockPos basePos = new BlockPos(offsetPos).up(2);

        placeBlock(basePos, Items.SNOW_BLOCK);
        placeBlock(basePos.up(), Items.SNOW_BLOCK);
        placeBlock(basePos.up(2), Items.CARVED_PUMPKIN);
    }

    private void placeBlock(BlockPos pos, net.minecraft.item.Item item) {
        if (mc.player == null || mc.world == null || mc.interactionManager == null) return;

        int slot = findItemInHotbar(item);
        if (slot == -1) return;

        mc.player.getInventory().selectedSlot = slot;

        // 必须提供点击的面和位置，我们选择点击 block 下方
        BlockPos clickPos = pos.down();
        BlockHitResult hitResult = new BlockHitResult(
                Vec3d.ofCenter(clickPos),
                Direction.UP,
                clickPos,
                false
        );

        ActionResult result = mc.interactionManager.interactBlock(
                mc.player,
                mc.world,
                Hand.MAIN_HAND,
                hitResult
        );

        if (result == ActionResult.SUCCESS) {
            mc.player.swingHand(Hand.MAIN_HAND);
        }
    }

    private int findItemInHotbar(net.minecraft.item.Item item) {
        for (int i = 0; i < 9; i++) {
            ItemStack stack = mc.player.getInventory().getStack(i);
            if (stack.getItem() == item) return i;
        }
        return -1;
    }
}
