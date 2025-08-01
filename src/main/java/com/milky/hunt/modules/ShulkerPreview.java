package com.milky.hunt.modules;

import com.milky.hunt.Addon;
import meteordevelopment.meteorclient.events.render.Render2DEvent;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.client.item.TooltipContext;
import net.minecraft.client.util.DefaultedList;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.block.entity.ShulkerBoxBlockEntity;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.minecraft.block.Blocks;

import java.lang.reflect.Field;
import java.util.List;

public class ShulkerPreview extends Module {
    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    private final Setting<Integer> offsetX = sgGeneral.add(new IntSetting.Builder()
        .name("offset-x")
        .description("X position offset.")
        .defaultValue(0)
        .sliderRange(-500, 500)
        .build()
    );

    private final Setting<Integer> offsetY = sgGeneral.add(new IntSetting.Builder()
        .name("offset-y")
        .description("Y position offset.")
        .defaultValue(0)
        .sliderRange(-500, 500)
        .build()
    );

    private final Setting<Double> scale = sgGeneral.add(new DoubleSetting.Builder()
        .name("scale")
        .description("Scale of the item preview.")
        .defaultValue(1.0)
        .min(0.1)
        .max(5.0)
        .build()
    );

    private final Setting<Boolean> showCapacityBar = sgGeneral.add(new BoolSetting.Builder()
        .name("show-capacity-bar")
        .description("Show filled bar under the preview.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Integer> barWidth = sgGeneral.add(new IntSetting.Builder()
        .name("bar-width")
        .description("Width of the capacity bar.")
        .defaultValue(50)
        .sliderRange(10, 200)
        .build()
    );

    private final Setting<Integer> barHeight = sgGeneral.add(new IntSetting.Builder()
        .name("bar-height")
        .description("Height of the capacity bar.")
        .defaultValue(6)
        .sliderRange(1, 20)
        .build()
    );

    private final Setting<Integer> barOffsetX = sgGeneral.add(new IntSetting.Builder()
        .name("bar-offset-x")
        .description("X offset of the capacity bar relative to item.")
        .defaultValue(0)
        .sliderRange(-100, 100)
        .build()
    );

    private final Setting<Integer> barOffsetY = sgGeneral.add(new IntSetting.Builder()
        .name("bar-offset-y")
        .description("Y offset of the capacity bar relative to item.")
        .defaultValue(20)
        .sliderRange(-100, 100)
        .build()
    );

    public ShulkerPreview() {
        super(Addon.CATEGORY, "shulker-preview", "Shows a preview of shulker box contents.");
    }

    @EventHandler
    private void onRender2D(Render2DEvent event) {
        if (mc.player == null || mc.world == null) return;
        if (mc.currentScreen != null) return; // 不在界面时显示
        if (!(mc.crosshairTarget instanceof BlockHitResult bhr)) return;

        BlockPos pos = bhr.getBlockPos();
        if (mc.world.getBlockState(pos).getBlock() != Blocks.SHULKER_BOX) return;

        ShulkerBoxBlockEntity be = (ShulkerBoxBlockEntity) mc.world.getBlockEntity(pos);
        if (be == null) return;

        DefaultedList<ItemStack> contents = getInventory(be);
        if (contents == null || contents.isEmpty()) return;

        // 选一个展示的物品，这里简单取第一个非空物品
        ItemStack toDisplay = ItemStack.EMPTY;
        for (ItemStack stack : contents) {
            if (!stack.isEmpty() && stack.getItem() != Items.AIR) {
                toDisplay = stack;
                break;
            }
        }
        if (toDisplay.isEmpty()) return;

        int screenWidth = mc.getWindow().getScaledWidth();
        int screenHeight = mc.getWindow().getScaledHeight();

        int x = screenWidth / 2 + offsetX.get();
        int y = screenHeight / 2 + offsetY.get();

        meteordevelopment.meteorclient.renderer.Renderer renderer = event.renderer;

        renderer.item(toDisplay, x, y, scale.get().floatValue());

        if (showCapacityBar.get()) {
            int filled = contents.stream().mapToInt(ItemStack::getCount).sum();
            int capacity = contents.size() * 64;
            double pct = Math.min(1.0, (double) filled / capacity);

            int barW = barWidth.get();
            int barH = barHeight.get();
            int bx = x + barOffsetX.get();
            int by = y + barOffsetY.get();

            // 画背景条
            renderer.rect(bx, by, barW, barH, 0x55000000);
            // 画填充条
            int filledW = (int) (barW * pct);
            renderer.rect(bx, by, filledW, barH, 0xFF55FF55);
        }
    }

    @SuppressWarnings("unchecked")
    private DefaultedList<ItemStack> getInventory(ShulkerBoxBlockEntity be) {
        try {
            Field f = ShulkerBoxBlockEntity.class.getDeclaredField("inventory");
            f.setAccessible(true);
            Object obj = f.get(be);
            if (obj instanceof DefaultedList) {
                return (DefaultedList<ItemStack>) obj;
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        return null;
    }
}
