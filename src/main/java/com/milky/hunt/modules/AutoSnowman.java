package com.milky.hunt.modules;

import meteordevelopment.meteorclient.settings.Settings;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.player.PlayerUtils;
import net.minecraft.block.Blocks;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.item.Item;
import net.minecraft.item.Items;
import net.minecraft.item.ItemStack;
import net.minecraft.util.Hand;
import net.minecraft.util.math.*;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.Direction;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientInteractionManager;

import com.milky.hunt.MainAddon;

public class AutoSnowman extends Module {
    private final MinecraftClient mc = MinecraftClient.getInstance();

    public AutoSnowman() {
        super(MainAddon.CATEGORY, "auto-snowman", "Automatically builds snow golems in front of the player.");
    }

    @Override
    public void onActivate() {
        if (mc.player == null || mc.world == null) {
            toggle();
            return;
        }

        BlockPos base = getTargetPos();
        placeBlockByInteraction(base, Items.SNOW_BLOCK);
        placeBlockByInteraction(base.up(), Items.SNOW_BLOCK);
        placeBlockByInteraction(base.up(2), Items.CARVED_PUMPKIN);

        toggle(); // Auto disable after building
    }

    private BlockPos getTargetPos() {
        ClientPlayerEntity player = mc.player;
        Vec3d playerPos = player.getPos();
        Vec2f rotation = player.getRotationClient(); // (pitch, yaw)
        float yawRad = (float) Math.toRadians(rotation.y);

        // 朝向两格远
        double dx = -Math.sin(yawRad) * 2;
        double dz = Math.cos(yawRad) * 2;

        Vec3d offset = new Vec3d(dx, 2, dz); // 向上偏移2格
        Vec3d target = playerPos.add(offset);

        return new BlockPos(target);
    }

    private void placeBlockByInteraction(BlockPos pos, Item item) {
        if (mc.player == null || mc.interactionManager == null) return;

        // 找到物品槽
        int slot = findHotbarSlotWith(item);
        if (slot == -1) return;

        // 切换主手物品
        mc.player.getInventory().selectedSlot = slot;

        BlockPos support = pos.down(); // 用于点击支撑面
        Direction direction = Direction.UP;

        Vec3d hitPos = Vec3d.ofCenter(support); // 模拟点击中间

        BlockHitResult hitResult = new BlockHitResult(hitPos, direction, support, false);

        // 执行放置动作
        mc.interactionManager.interactBlock(mc.player, Hand.MAIN_HAND, hitResult);
    }

    private int findHotbarSlotWith(Item item) {
        for (int i = 0; i < 9; i++) {
            ItemStack stack = mc.player.getInventory().getStack(i);
            if (stack.getItem() == item) return i;
        }
        return -1;
    }
}
