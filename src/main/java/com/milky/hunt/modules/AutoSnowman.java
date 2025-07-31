package com.milky.hunt.modules;

import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.IntSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.player.PlayerUtils;
import meteordevelopment.meteorclient.utils.world.BlockUtils;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.block.Blocks;
import net.minecraft.item.BlockItem;
import net.minecraft.item.Items;
import net.minecraft.util.Hand;
import net.minecraft.util.math.*;

import java.util.ArrayList;
import java.util.List;

public class AutoSnowman extends Module {
    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    private final Setting<Boolean> continuous = sgGeneral.add(new BoolSetting.Builder()
        .name("continuous")
        .description("Continuously builds snow golems if materials are available.")
        .defaultValue(false)
        .build()
    );

    private final Setting<Integer> delay = sgGeneral.add(new IntSetting.Builder()
        .name("delay")
        .description("Delay between each snowman build cycle in milliseconds.")
        .defaultValue(1000)
        .min(0)
        .sliderMax(5000)
        .build()
    );

    private final List<BlockPos> snowmanBlocks = new ArrayList<>();
    private int index = 0;
    private long lastBuildTime = 0;

    public AutoSnowman() {
        super(Hunt.CATEGORY, "auto-snowman", "Automatically builds a snow golem 2 blocks ahead.");
    }

    @Override
    public void onActivate() {
        if (mc.player == null) return;

        snowmanBlocks.clear();
        index = 0;
        lastBuildTime = System.currentTimeMillis();

        // 获取玩家朝向的水平向量
        Vec3d lookVec = mc.player.getRotationVec(1.0f);
        Vec2f horizontal = new Vec2f((float) lookVec.x, (float) lookVec.z);
        if (horizontal.lengthSquared() < 1e-5) {
            warning("Invalid facing direction.");
            toggle();
            return;
        }

        horizontal = horizontal.normalize().multiply(2); // 水平距离 2 格

        // 基础位置：距离玩家 2 格远的地面方块
        BlockPos basePos = new BlockPos(
            mc.player.getX() + horizontal.x,
            mc.player.getY() - 1,
            mc.player.getZ() + horizontal.y
        );

        // 添加放置方块位置：两层雪 + 南瓜
        snowmanBlocks.add(basePos);
        snowmanBlocks.add(basePos.up());
        snowmanBlocks.add(basePos.up(2));
    }

    @EventHandler
    private void onTick(TickEvent.Post event) {
        if (mc.player == null || mc.world == null) return;
        if (index >= snowmanBlocks.size()) {
            if (continuous.get()) {
                long now = System.currentTimeMillis();
                if (now - lastBuildTime >= delay.get()) {
                    onActivate(); // 重置构建流程
                }
            } else {
                info("Snowman complete.");
                toggle();
            }
            return;
        }

        BlockPos pos = snowmanBlocks.get(index);

        // 确保对应位置可以放置
        if (!mc.world.getBlockState(pos).isReplaceable()) {
            error("Cannot place block at " + pos.toShortString() + ". Stopping.");
            toggle();
            return;
        }

        // 确定该放置什么：前两个放雪，最后一个放南瓜
        if (index < 2) {
            if (!PlayerUtils.selectItemFromHotbar(item -> item.getItem() == Items.SNOW_BLOCK)) {
                error("No snow blocks in hotbar.");
                toggle();
                return;
            }
        } else {
            if (!PlayerUtils.selectItemFromHotbar(item -> item.getItem() == Items.CARVED_PUMPKIN)) {
                error("No carved pumpkin in hotbar.");
                toggle();
                return;
            }
        }

        BlockUtils.place(pos, Hand.MAIN_HAND, true);
        index++;
    }
}
