package com.milky.hunt.hud;

import com.milky.hunt.Addon;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.DoubleSetting;
import meteordevelopment.meteorclient.settings.IntSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.systems.hud.HudElement;
import meteordevelopment.meteorclient.systems.hud.HudElementInfo;
import meteordevelopment.meteorclient.systems.hud.HudRenderer;
import meteordevelopment.meteorclient.utils.render.color.Color;
import net.minecraft.client.MinecraftClient;

public class YVelocity extends HudElement {
    public static final HudElementInfo<YVelocity> INFO =
        new HudElementInfo<>(Addon.HUD_GROUP, "Y-Velocity", "Vertical velocity with unit.", YVelocity::new);

    private final MinecraftClient mc;
    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    private final Setting<Double> scale = sgGeneral.add(new DoubleSetting.Builder()
        .name("scale")
        .description("Custom scale.")
        .defaultValue(1.0)
        .min(0.5)
        .sliderRange(0.5, 3.0)
        .build()
    );

    private final Setting<Integer> decimals = sgGeneral.add(new IntSetting.Builder()
        .name("decimals")
        .description("Decimal places.")
        .defaultValue(2)
        .min(0)
        .max(6)
        .build()
    );

    private final Setting<Boolean> perSecond = sgGeneral.add(new BoolSetting.Builder()
        .name("per-second")
        .description("If on, multiply by 20 and show b/s. If off, show per tick (b/t).")
        .defaultValue(true)
        .build()
    );

    private final Setting<Double> smoothing = sgGeneral.add(new DoubleSetting.Builder()
        .name("smoothing")
        .description("Exponential smoothing factor (0 = off, 1 = no smoothing).")
        .defaultValue(0.6)
        .min(0.0).max(1.0)
        .build()
    );

    private double filteredVy = 0.0;

    public YVelocity() {
        super(INFO);
        mc = MinecraftClient.getInstance();
    }

    @Override
    public void render(HudRenderer renderer) {
        double vy = 0.0;
        if (mc.player != null) vy = mc.player.getVelocity().y;

        filteredVy = smoothing.get() == 0.0 ? vy : (smoothing.get() * filteredVy + (1.0 - smoothing.get()) * vy);

        double v = perSecond.get() ? filteredVy * 20.0 : filteredVy;

        String fmt = "%." + decimals.get() + "f";
        String unit = perSecond.get() ? " b/s" : " b/t";
        String text = String.format(fmt, v) + unit;

        setSize(renderer.textWidth(text, true, scale.get()), renderer.textHeight(true, scale.get()));
        renderer.text(text, x, y, Color.WHITE, true, scale.get());
    }
}
