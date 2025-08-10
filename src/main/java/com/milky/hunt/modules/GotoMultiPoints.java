package com.milky.hunt.modules;

import com.milky.hunt.Addon;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.util.math.BlockPos;
import baritone.api.BaritoneAPI;
import baritone.api.pathing.goals.GoalBlock;

import java.util.ArrayList;
import java.util.List;

public class GotoMultiPoints extends Module {
    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    private final Setting<List<BlockPos>> points = sgGeneral.add(new BlockPosListSetting.Builder()
        .name("points")
        .description("Coordinates to patrol through in sequence.")
        .defaultValue(List.of(
            new BlockPos(0, 64, 0),
            new BlockPos(16, 64, 16)
        ))
        .build()
    );

    private final Setting<Double> reachDistance = sgGeneral.add(new DoubleSetting.Builder()
        .name("reach-distance")
        .description("How close you need to be to consider the point reached.")
        .defaultValue(1.0)
        .min(0.1)
        .sliderMax(5.0)
        .build()
    );

    private final Setting<Double> waitSeconds = sgGeneral.add(new DoubleSetting.Builder()
        .name("wait-seconds")
        .description("Seconds to wait at each point before going to the next one.")
        .defaultValue(2.0)
        .min(0)
        .sliderMax(10)
        .build()
    );

    private int currentIndex = 0;
    private long lastArriveTime = 0;
    private boolean waiting = false;

    public GotoMultiPoints() {
        super(Addon.CATEGORY, "GotoMultiPoints", "Walks between multiple coordinates using Baritone.");
    }

    @Override
    public void onActivate() {
        if (points.get().isEmpty()) {
            info("No points set!");
            toggle();
            return;
        }
        currentIndex = 0;
        goTo(points.get().get(currentIndex));
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (mc.player == null || points.get().isEmpty()) return;

        BlockPos target = points.get().get(currentIndex);

        if (mc.player.getBlockPos().isWithinDistance(target, reachDistance.get())) {
            if (!waiting) {
                waiting = true;
                lastArriveTime = System.currentTimeMillis();
                BaritoneAPI.getProvider().getPrimaryBaritone().getPathingBehavior().cancelEverything();
            } else {
                if (System.currentTimeMillis() - lastArriveTime >= waitSeconds.get() * 1000) {
                    currentIndex = (currentIndex + 1) % points.get().size();
                    waiting = false;
                    goTo(points.get().get(currentIndex));
                }
            }
        }
    }

    private void goTo(BlockPos pos) {
        BaritoneAPI.getProvider().getPrimaryBaritone().getCustomGoalProcess()
            .setGoalAndPath(new GoalBlock(pos));
    }

    @Override
    public void onDeactivate() {
        BaritoneAPI.getProvider().getPrimaryBaritone().getPathingBehavior().cancelEverything();
    }
}
