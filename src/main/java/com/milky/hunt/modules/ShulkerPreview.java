
package com.milky.hunt.modules;

import meteordevelopment.meteorclient.events.render.Render2DEvent;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.block.entity.ShulkerBoxBlockEntity;
import net.minecraft.client.gui.DrawableHelper;
import net.minecraft.client.render.item.ItemRenderer;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.item.ItemStack;
import net.minecraft.util.math.BlockPos;

import java.util.List;

import static meteordevelopment.meteorclient.MeteorClient.mc;
import static com.milky.hunt.Addon.CATEGORY;

public class ShulkerPreview extends Module {
    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    private final Setting<Boolean> drawBackground = sgGeneral.add(new BoolSetting.Builder()
        .name("draw-background")
        .description("Whether to draw a background behind the preview.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> drawBorder = sgGeneral.add(new BoolSetting.Builder()
        .name("draw-border")
        .description("Draws a border around the preview.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> onlyOnSneak = sgGeneral.add(new BoolSetting.Builder()
        .name("only-when-sneaking")
        .description("Only shows the preview while sneaking.")
        .defaultValue(false)
        .build()
    );

    private final Setting<Double> scale = sgGeneral.add(new DoubleSetting.Builder()
        .name("scale")
        .description("Scale of the preview.")
        .defaultValue(1.0)
        .min(0.5)
        .max(3.0)
        .sliderMax(3.0)
        .build()
    );

    public ShulkerPreview() {
        super(CATEGORY, "shulker-preview", "Shows the contents of shulker boxes.");
    }

    @EventHandler
    private void onRender2D(Render2DEvent event) {
        if (mc.crosshairTarget == null || mc.player == null || mc.world == null) return;
        if (onlyOnSneak.get() && !mc.player.isSneaking()) return;

        BlockPos blockPos = mc.crosshairTarget.getBlockPos();
        if (blockPos == null) return;

        BlockEntity blockEntity = mc.world.getBlockEntity(blockPos);
        if (!(blockEntity instanceof ShulkerBoxBlockEntity shulker)) return;

        List<ItemStack> items = shulker.getInventory();

        int columns = 9;
        int rows = 3;

        int boxWidth = (int) (18 * columns * scale.get());
        int boxHeight = (int) (18 * rows * scale.get());

        int startX = (mc.getWindow().getScaledWidth() - boxWidth) / 2;
        int startY = (int) (mc.getWindow().getScaledHeight() * 0.8 - boxHeight);

        MatrixStack matrices = event.matrices;

        if (drawBackground.get()) {
            DrawableHelper.fill(matrices, startX - 2, startY - 2, startX + boxWidth + 2, startY + boxHeight + 2, 0x90000000);
        }

        if (drawBorder.get()) {
            DrawableHelper.drawHorizontalLine(matrices, startX - 2, startX + boxWidth + 2, startY - 2, 0xFFFFFFFF);
            DrawableHelper.drawHorizontalLine(matrices, startX - 2, startX + boxWidth + 2, startY + boxHeight + 2, 0xFFFFFFFF);
            DrawableHelper.drawVerticalLine(matrices, startX - 2, startY - 2, startY + boxHeight + 2, 0xFFFFFFFF);
            DrawableHelper.drawVerticalLine(matrices, startX + boxWidth + 2, startY - 2, startY + boxHeight + 2, 0xFFFFFFFF);
        }

        ItemRenderer itemRenderer = mc.getItemRenderer();
        for (int i = 0; i < items.size(); i++) {
            int x = i % columns;
            int y = i / columns;
            int itemX = startX + (int) (x * 18 * scale.get());
            int itemY = startY + (int) (y * 18 * scale.get());

            ItemStack itemStack = items.get(i);
            matrices.push();
            matrices.translate(itemX, itemY, 0);
            matrices.scale(scale.get().floatValue(), scale.get().floatValue(), 1);
            itemRenderer.renderInGuiWithOverrides(itemStack, 0, 0);
            itemRenderer.renderGuiItemOverlay(mc.textRenderer, itemStack, 0, 0, null);
            matrices.pop();
        }
    }
}
