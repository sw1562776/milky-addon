package com.milky.hunt.modules;

import com.milky.hunt.Addon;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.settings.StringListSetting;
import meteordevelopment.meteorclient.settings.DoubleSetting;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.util.math.BlockPos;
import meteordevelopment.meteorclient.pathing.PathManagers;

import java.util.ArrayList;
import java.util.List;

public class GotoMultiPoints extends Module {
    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    private final Setting<List<String>> points = sgGeneral.add(new StringListSetting.Builder()
        .name("points")
        .description("List of coordinates to walk between. Format: x y z")
        .defaultValue(List.of("0 64 0", "100 64 100"))
        .build()
    );

    private final Setting<Double> waitTime = sgGeneral.add(new DoubleSetting.Builder()
        .name("wait-time")
        .description("Time to wait at each point before moving to the next.")
        .defaultValue(2.0)
        .min(0.0)
        .sliderMax(30.0)
        .build()
    );

    private int currentIndex = 0;
    private long lastArrivalTime = 0;
    private boolean waiting = false;

    public GotoMultiPoints() {
        super(Addon.CATEGORY, "GotoMultiPoints", "Walks between multiple points back and forth using Baritone.");
    }

    @Override
    public void onActivate() {
        currentIndex = 0;
        waiting = false;
        moveToCurrentPoint();
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (mc.player == null || mc.world == null) return;

        BlockPos target = parseBlockPos(points.get().get(currentIndex));
        if (target == null) return;

        double dist = mc.player.getBlockPos().getSquaredDistance(target);
        if (!waiting && dist <= 2) { // 到达
            waiting = true;
            lastArrivalTime = System.currentTimeMillis();
        }

        if (waiting) {
            if ((System.currentTimeMillis() - lastArrivalTime) >= waitTime.get() * 1000) {
                // 下一个点
                currentIndex = (currentIndex + 1) % points.get().size();
                waiting = false;
                moveToCurrentPoint();
            }
        }
    }

    private void moveToCurrentPoint() {
        BlockPos target = parseBlockPos(points.get().get(currentIndex));
        if (target != null) {
            PathManagers.get().moveTo(target, true);
        }
    }

    private BlockPos parseBlockPos(String s) {
        try {
            String[] parts = s.trim().split("\\s+");
            if (parts.length != 3) return null;
            int x = Integer.parseInt(parts[0]);
            int y = Integer.parseInt(parts[1]);
            int z = Integer.parseInt(parts[2]);
            return new BlockPos(x, y, z);
        } catch (Exception e) {
            return null;
        }
    }
}
