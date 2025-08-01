package com.milky.hunt.modules;

import meteordevelopment.meteorclient.events.render.Render2DEvent;
import meteordevelopment.meteorclient.renderer.Renderer2D;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.block.Blocks;
import net.minecraft.block.entity.ShulkerBoxBlockEntity;
import net.minecraft.client.MinecraftClient;
import net.minecraft.item.ItemStack;
import net.minecraft.util.collection.DefaultedList;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;

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
        .description("Show capacity bar below preview.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Integer> barWidth = sgGeneral.add(new IntSetting.Builder()
        .name("bar-width")
        .description("Width of capacity bar.")
        .defaultValue(50)
        .sliderRange(10, 200)
        .build()
    );

    private final Setting<Integer> barHeight = sgGeneral.add(new IntSetting.Builder()
        .name("bar-height")
        .description("Height of capacity bar.")
        .defaultValue(6)
        .sliderRange(1, 20)
        .build()
    );

    private final Setting<Integer> barOffsetX = sgGeneral.add(new IntSetting.Builder()
        .name("bar-offset-x")
        .description("X offset of capacity bar.")
        .defaultValue(0)
        .sliderRange(-100, 100)
        .build()
    );

    private final Setting<Integer> barOffsetY = sgGeneral.add(new IntSetting.Builder()
        .name("bar-offset-y")
        .description("Y offset of capacity bar.")
        .defaultValue(20)
        .sliderRange(-100, 100)
        .build()
    );

    public ShulkerPreview() {
        super(null, "shulker-preview", "Shows preview of shulker box contents.");
    }

    @EventHandler
    private void onRender2D(Render2DEvent event) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.player == null || mc.world == null) return;
        if (mc.currentScreen != null) return;

        if (!(mc.crosshairTarget instanceof BlockHitResult bhr)) return;

        BlockPos pos = bhr.getBlockPos();
        if (mc.world.getBlockState(pos).getBlock() != Blocks.SHULKER_BOX) return;

        var beRaw = mc.world.getBlockEntity(pos);
        if (!(beRaw instanceof ShulkerBoxBlockEntity be)) return;

        DefaultedList<ItemStack> contents = be.inventory;
        if (contents == null || contents.isEmpty()) return;

        ItemStack toDisplay = ItemStack.EMPTY;
        for (ItemStack stack : contents) {
            if (!stack.isEmpty()) {
                toDisplay = stack;
                break;
            }
        }
        if (toDisplay.isEmpty()) return;

        int screenWidth = mc.getWindow().getScaledWidth();
        int screenHeight = mc.getWindow().getScaledHeight();

        int x = screenWidth / 2 + offsetX.get();
        int y = screenHeight / 2 + offsetY.get();

        Renderer2D renderer = event.renderer;

        renderer.item(toDisplay, x, y, scale.get().floatValue());

        if (showCapacityBar.get()) {
            int filled = contents.stream().mapToInt(ItemStack::getCount).sum();
            int capacity = contents.size() * 64;
            double pct = Math.min(1.0, (double) filled / capacity);

            int barW = barWidth.get();
            int barH = barHeight.get();
            int bx = x + barOffsetX.get();
            int by = y + barOffsetY.get();

            renderer.rectQuad(bx, by, barW, barH, 0x55000000);
            int filledW = (int) (barW * pct);
            renderer.rectQuad(bx, by, filledW, barH, 0xFF55FF55);
        }
    }
}
