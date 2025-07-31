package com.stash.hunt.modules;

import com.stash.hunt.Addon;
import meteordevelopment.meteorclient.events.packets.PacketEvent;
import net.minecraft.item.SpawnEggItem;
import meteordevelopment.meteorclient.events.render.Render3DEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.renderer.ShapeMode;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.render.color.SettingColor;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.item.BlockItem;
import net.minecraft.network.packet.c2s.play.*;
import net.minecraft.network.packet.s2c.play.SubtitleS2CPacket;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;

public class GrimAirPlace extends Module {
    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgRange = settings.createGroup("Range");

    private final Setting<Integer> placeDelay = sgGeneral.add(new IntSetting.Builder()
        .name("Place Delay")
        .description("The delay in ticks between block placements.")
        .defaultValue(0)
        .build()
    );

    private final Setting<Boolean> render = sgGeneral.add(new BoolSetting.Builder()
        .name("render")
        .description("Renders a block overlay where the obsidian will be placed.")
        .defaultValue(true)
        .build()
    );

    private final Setting<ShapeMode> shapeMode = sgGeneral.add(new EnumSetting.Builder<ShapeMode>()
        .name("shape-mode")
        .description("How the shapes are rendered.")
        .defaultValue(ShapeMode.Both)
        .build()
    );

    private final Setting<SettingColor> sideColor = sgGeneral.add(new ColorSetting.Builder()
        .name("side-color")
        .description("The color of the sides of the blocks being rendered.")
        .defaultValue(new SettingColor(204, 0, 0, 10))
        .build()
    );

    private final Setting<SettingColor> lineColor = sgGeneral.add(new ColorSetting.Builder()
        .name("line-color")
        .description("The color of the lines of the blocks being rendered.")
        .defaultValue(new SettingColor(204, 0, 0, 255))
        .build()
    );

    private final Setting<Boolean> customRange = sgRange.add(new BoolSetting.Builder()
        .name("custom-range")
        .description("Use custom range for air place.")
        .defaultValue(false)
        .build()
    );

    private final Setting<Double> range = sgRange.add(new DoubleSetting.Builder()
        .name("range")
        .description("Custom range to place at.")
        .visible(customRange::get)
        .defaultValue(5)
        .min(0)
        .sliderMax(5.5)
        .build()
    );

    private HitResult hitResult;
    private int delay = 0;

    public GrimAirPlace() {
        super(Addon.CATEGORY, "grim-air-place", "Places a block where your crosshair is pointing at.");
    }

    @Override
    public void onActivate()
    {
        delay = 0;
    }

    @EventHandler
    private void onTick(TickEvent.Post event) {
        if (mc.player == null) return;
        delay++;
        double r = customRange.get() ? range.get() : mc.player.getBlockInteractionRange();
        hitResult = mc.getCameraEntity().raycast(r, 0, false);

        if (!(hitResult instanceof BlockHitResult blockHitResult) || !(mc.player.getMainHandStack().getItem() instanceof BlockItem) && !(mc.player.getMainHandStack().getItem() instanceof SpawnEggItem)) return;

        if (delay < placeDelay.get()) return;

        if (mc.options.useKey.isPressed()) {
            mc.player.networkHandler.sendPacket(new PlayerActionC2SPacket(PlayerActionC2SPacket.Action.SWAP_ITEM_WITH_OFFHAND, new BlockPos(0,0,0), Direction.DOWN));

            mc.player.networkHandler.sendPacket(new PlayerInteractBlockC2SPacket(Hand.OFF_HAND, blockHitResult, mc.player.currentScreenHandler.getRevision() + 2));

            mc.player.networkHandler.sendPacket(new PlayerActionC2SPacket(PlayerActionC2SPacket.Action.SWAP_ITEM_WITH_OFFHAND, new BlockPos(0,0,0), Direction.DOWN));

            mc.player.swingHand(Hand.MAIN_HAND);
            delay = 0;
        }
    }

    @EventHandler
    private void onRender(Render3DEvent event) {
        if (!(hitResult instanceof BlockHitResult blockHitResult)
            || !mc.world.getBlockState(blockHitResult.getBlockPos()).isReplaceable()
            || !(mc.player.getMainHandStack().getItem() instanceof BlockItem) && !(mc.player.getMainHandStack().getItem() instanceof SpawnEggItem)
            || !render.get()) return;

        event.renderer.box(blockHitResult.getBlockPos(), sideColor.get(), lineColor.get(), shapeMode.get(), 0);
    }
}
