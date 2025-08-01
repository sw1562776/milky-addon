package com.milky.hunt.modules;

import meteordevelopment.meteorclient.events.render.Render2DEvent;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.block.entity.ShulkerBoxBlockEntity;
import net.minecraft.client.gui.DrawContext;
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

        BlockPos pos = mc.crosshairTarget.getBlockPos();
        if (pos == null) return;

        BlockEntity be = mc.world.getBlockEntity(pos);
        if (!(be instanceof ShulkerBoxBlockEntity shulker)) return;

        List<ItemStack> items = shulker.getInventory();

        int cols = 9, rows = 3;
        int boxW = (int) (18 * cols * scale.get());
        int boxH = (int) (18 * rows * scale.get());

        int startX = (mc.getWindow().getScaledWidth() - boxW) / 2;
        int startY = (int) (mc.getWindow().getScaledHeight() * 0.8 - boxH);

        DrawContext dc = event.drawContext;
        MatrixStack ms = dc.getMatrices();

        if (drawBackground.get()) {
            dc.fill(startX - 2, startY - 2, startX + boxW + 2, startY + boxH + 2, 0x90000000);
        }

        if (drawBorder.get()) {
            dc.drawHorizontalLine(startX - 2, startX + boxW + 2, startY - 2, 0xFFFFFFFF);
            dc.drawHorizontalLine(startX - 2, startX + boxW + 2, startY + boxH + 2, 0xFFFFFFFF);
            dc.drawVerticalLine(startX - 2, startY - 2, startY + boxH + 2, 0xFFFFFFFF);
            dc.drawVerticalLine(startX + boxW + 2, startY - 2, startY + boxH + 2, 0xFFFFFFFF);
        }

        ItemRenderer ir = mc.getItemRenderer();
        for (int i = 0; i < items.size(); i++) {
            int x = i % cols, y = i / cols;
            int itemX = startX + (int) (x * 18 * scale.get());
            int itemY = startY + (int) (y * 18 * scale.get());

            ItemStack stack = items.get(i);
            ms.push();
            ms.translate(itemX, itemY, 0);
            ms.scale(scale.get().floatValue(), scale.get().floatValue(), 1);
            ir.renderInGuiWithOverrides(stack, 0, 0);
            ir.renderGuiItemOverlay(mc.textRenderer, stack, 0, 0, null);
            ms.pop();
        }
    }
}
