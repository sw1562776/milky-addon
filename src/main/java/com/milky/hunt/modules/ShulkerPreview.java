package com.milky.hunt.modules;

import com.milky.hunt.Addon;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.events.render.Render2DEvent;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.block.Blocks;
import net.minecraft.block.entity.ShulkerBoxBlockEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.util.hit.BlockHitResult;

import java.util.List;

public class ShulkerPreview extends Module {
    private final SettingGroup sg = settings.getDefaultGroup();

    private final Setting<Boolean> enabled = sg.add(new BoolSetting.Builder()
        .name("enabled")
        .description("Enable shulker preview overlay")
        .defaultValue(true)
        .build()
    );

    private final Setting<DisplayItemMode> displayItemMode = sg.add(new EnumSetting.Builder<DisplayItemMode>()
        .name("display-item-mode")
        .description("Which item to show as icon")
        .defaultValue(DisplayItemMode.FIRST)
        .build()
    );

    private final Setting<Boolean> onlyOnShift = sg.add(new BoolSetting.Builder()
        .name("only-on-shift")
        .description("Only preview when sneaking")
        .defaultValue(false)
        .build()
    );

    private final Setting<Integer> offsetX = sg.add(new IntSetting.Builder()
        .name("offset-x")
        .description("Icon X offset from center")
        .defaultValue(0)
        .sliderRange(-100, 100)
        .build()
    );

    private final Setting<Integer> offsetY = sg.add(new IntSetting.Builder()
        .name("offset-y")
        .description("Icon Y offset from center")
        .defaultValue(0)
        .sliderRange(-100, 100)
        .build()
    );

    private final Setting<Double> scale = sg.add(new DoubleSetting.Builder()
        .name("scale")
        .description("Icon scale")
        .defaultValue(1.0)
        .sliderRange(0.5, 2.0)
        .build()
    );

    private final Setting<Boolean> showCapacityBar = sg.add(new BoolSetting.Builder()
        .name("show-capacity-bar")
        .description("Show capacity bar under icon")
        .defaultValue(true)
        .build()
    );

    private final Setting<BarDirection> barDirection = sg.add(new EnumSetting.Builder<BarDirection>()
        .name("bar-direction")
        .description("Direction of capacity bar")
        .defaultValue(BarDirection.DOWN)
        .build()
    );

    private final Setting<Integer> barWidth = sg.add(new IntSetting.Builder()
        .name("bar-width")
        .description("Capacity bar width")
        .defaultValue(16)
        .sliderRange(4, 30)
        .build()
    );

    private final Setting<Integer> barHeight = sg.add(new IntSetting.Builder()
        .name("bar-height")
        .description("Capacity bar height")
        .defaultValue(3)
        .sliderRange(1, 10)
        .build()
    );

    private final Setting<Integer> barOffsetX = sg.add(new IntSetting.Builder()
        .name("bar-offset-x")
        .description("Capacity bar X offset relative to icon")
        .defaultValue(0)
        .sliderRange(-20, 20)
        .build()
    );

    private final Setting<Integer> barOffsetY = sg.add(new IntSetting.Builder()
        .name("bar-offset-y")
        .description("Capacity bar Y offset relative to icon")
        .defaultValue(18)
        .sliderRange(-20, 20)
        .build()
    );

    private final Setting<Integer> minStackCount = sg.add(new IntSetting.Builder()
        .name("min-stack-count")
        .description("Minimum stack size to consider for display")
        .defaultValue(1)
        .sliderRange(1, 64)
        .build()
    );

    public ShulkerPreview() {
        super(Addon.CATEGORY, "ShulkerPreview", "Preview shulker box contents when hovering.");
    }

    @EventHandler
    private void onRender2D(Render2DEvent event) {
        if (!enabled.get() || mc.player == null || mc.world == null) return;
        if (mc.currentScreen != null) return; // 不在 GUI 里
        if (onlyOnShift.get() && !mc.player.isSneaking()) return;
        if (!(mc.crosshairTarget instanceof BlockHitResult)) return;

        BlockHitResult bhr = (BlockHitResult) mc.crosshairTarget;
        if (mc.world.getBlockState(bhr.getBlockPos()).getBlock() != Blocks.SHULKER_BOX) return;

        if (!(mc.world.getBlockEntity(bhr.getBlockPos()) instanceof ShulkerBoxBlockEntity be)) return;

        List<ItemStack> contents;
        try {
            // Yarn 映射下 ShulkerBoxBlockEntity 通常有 getItems()
            contents = be.getItems();
        } catch (NoSuchMethodError | NoClassDefFoundError e) {
            // 保险 fallback: 直接空 list
            return;
        }

        if (contents == null || contents.isEmpty()) return;

        ItemStack toDisplay = DisplayItemMode.pick(displayItemMode.get(), contents, minStackCount.get());
        if (toDisplay.isEmpty()) return;

        int x = event.scaledWidth / 2 + offsetX.get();
        int y = event.scaledHeight / 2 + offsetY.get();

        event.renderer.item(toDisplay, x, y, (float) (scale.get()));

        if (showCapacityBar.get()) {
            int filled = contents.stream().mapToInt(ItemStack::getCount).sum();
            int capacity = contents.size() * 64; // 假设每格最大 64
            double pct = Math.min(1.0, (double) filled / capacity);
            int barW = barWidth.get();
            int barH = barHeight.get();
            int bx = x + barOffsetX.get();
            int by = y + barOffsetY.get();
            if (barDirection.get() == BarDirection.UP) {
                by = y - barOffsetY.get() - barH;
            }

            // 背景
            event.renderer.rect(bx, by, bx + barW, by + barH, 0x55000000);
            int filledW = (int) (barW * pct);
            // 进度
            event.renderer.rect(bx, by, bx + filledW, by + barH, 0xFF55FF55);
        }
    }

    private enum DisplayItemMode {
        FIRST, LAST, MOST, LEAST;

        static ItemStack pick(DisplayItemMode m, List<ItemStack> list, int min) {
            return list.stream()
                .filter(s -> s.getCount() >= min && !s.isEmpty())
                .sorted((a, b) -> {
                    switch (m) {
                        case LEAST:
                            return Integer.compare(a.getCount(), b.getCount());
                        case MOST:
                            return Integer.compare(b.getCount(), a.getCount());
                        case LAST:
                            return 1; // 保持原顺序，后面会取最后
                        case FIRST:
                        default:
                            return -1;
                    }
                })
                .findFirst()
                .orElseGet(() -> {
                    if (list.isEmpty()) return ItemStack.EMPTY;
                    if (m == LAST) return list.get(list.size() - 1);
                    return list.get(0);
                });
        }
    }

    private enum BarDirection { DOWN, UP }
}
