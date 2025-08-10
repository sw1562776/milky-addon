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
    public enum InputMode {
        Simple,
        String
    }

    private final SettingGroup sgGeneral = settings.getDefaultGroup();


    private final Setting<InputMode> inputMode = sgGeneral.add(new EnumSetting.Builder<InputMode>()
        .name("input-mode")
        .description("Choose coordinate input mode.")
        .defaultValue(InputMode.Simple)
        .build()
    );

    private final Setting<Integer> pointsCount = sgGeneral.add(new IntSetting.Builder()
        .name("points-count")
        .description("Number of points to use.")
        .defaultValue(4)
        .min(1)
        .max(8)
        .visible(() -> inputMode.get() == InputMode.Simple)
        .build()
    );

    private final Setting<BlockPos> point1 = sgGeneral.add(new BlockPosSetting.Builder()
        .name("point-1")
        .description("Coordinate of point 1.")
        .defaultValue(new BlockPos(8, 64, 8))
        .visible(() -> inputMode.get() == InputMode.Simple && pointsCount.get() >= 1)
        .build()
    );

    private final Setting<BlockPos> point2 = sgGeneral.add(new BlockPosSetting.Builder()
        .name("point-2")
        .description("Coordinate of point 2.")
        .defaultValue(new BlockPos(16, 64, 16))
        .visible(() -> inputMode.get() == InputMode.Simple && pointsCount.get() >= 2)
        .build()
    );

    private final Setting<BlockPos> point3 = sgGeneral.add(new BlockPosSetting.Builder()
        .name("point-3")
        .description("Coordinate of point 3.")
        .defaultValue(new BlockPos(32, 64, 32))
        .visible(() -> inputMode.get() == InputMode.Simple && pointsCount.get() >= 3)
        .build()
    );

    private final Setting<BlockPos> point4 = sgGeneral.add(new BlockPosSetting.Builder()
        .name("point-4")
        .description("Coordinate of point 4.")
        .defaultValue(new BlockPos(64, 64, 64))
        .visible(() -> inputMode.get() == InputMode.Simple && pointsCount.get() >= 4)
        .build()
    );

    private final Setting<BlockPos> point5 = sgGeneral.add(new BlockPosSetting.Builder()
        .name("point-5")
        .description("Coordinate of point 5.")
        .defaultValue(new BlockPos(128, 64, 128))
        .visible(() -> inputMode.get() == InputMode.Simple && pointsCount.get() >= 5)
        .build()
    );

    private final Setting<BlockPos> point6 = sgGeneral.add(new BlockPosSetting.Builder()
        .name("point-6")
        .description("Coordinate of point 6.")
        .defaultValue(new BlockPos(256, 64, 256))
        .visible(() -> inputMode.get() == InputMode.Simple && pointsCount.get() >= 6)
        .build()
    );

    private final Setting<BlockPos> point7 = sgGeneral.add(new BlockPosSetting.Builder()
        .name("point-7")
        .description("Coordinate of point 7.")
        .defaultValue(new BlockPos(512, 64, 512))
        .visible(() -> inputMode.get() == InputMode.Simple && pointsCount.get() >= 7)
        .build()
    );

    private final Setting<BlockPos> point8 = sgGeneral.add(new BlockPosSetting.Builder()
        .name("point-8")
        .description("Coordinate of point 8.")
        .defaultValue(new BlockPos(1024, 64, 1024))
        .visible(() -> inputMode.get() == InputMode.Simple && pointsCount.get() >= 8)
        .build()
    );

    private final Setting<String> pointsString = sgGeneral.add(new StringSetting.Builder()
        .name("points")
        .description("Coordinates to patrol in string format: x,y,z; x,y,z; ...")
        .defaultValue("8,64,8; 16,64,16")
        .visible(() -> inputMode.get() == InputMode.String)
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
    
    private final Setting<Boolean> loop = sgGeneral.add(new BoolSetting.Builder()
        .name("loop")
        .description("Loop through points or stop after the last one.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Double> waitSeconds = sgGeneral.add(new DoubleSetting.Builder()
        .name("wait-seconds")
        .description("Seconds to wait after returning to the starting point.")
        .defaultValue(2.0)
        .min(0)
        .sliderMax(30)
        .visible(loop::get)
        .build()
    );

    private final List<BlockPos> points = new ArrayList<>();
    private int currentIndex = 0;
    private boolean waiting = false;
    private boolean loopWait = false;
    private long lastArriveTime = 0;

    public GotoMultiPoints() {
        super(Addon.CATEGORY, "GotoMultiPoints", "Walks between multiple coordinates using Baritone.");
    }

    @Override
    public void onActivate() {
        parsePoints();
        if (points.isEmpty()) {
            info("No points set!");
            toggle();
            return;
        }
        currentIndex = 0;
        waiting = false;
        loopWait = false;
        goTo(points.get(currentIndex));
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (mc.player == null || points.isEmpty()) return;

        BlockPos target = points.get(currentIndex);

        if (mc.player.getBlockPos().isWithinDistance(target, reachDistance.get())) {
            if (!waiting) {
                waiting = true;
                lastArriveTime = System.currentTimeMillis();
                BaritoneAPI.getProvider().getPrimaryBaritone().getPathingBehavior().cancelEverything();
            } else {
                if (System.currentTimeMillis() - lastArriveTime >= 300) {
                    int lastIndex = points.size() - 1;

                    if (currentIndex == lastIndex) {
                        if (loop.get()) {
                            currentIndex = 0;
                            waiting = false;
                            loopWait = true;
                            goTo(points.get(currentIndex));
                        } else {
                            info("Finished all points. Stopping.");
                            toggle();
                        }
                    }
                    else if (currentIndex == 0 && loopWait) {
                        if (System.currentTimeMillis() - lastArriveTime >= waitSeconds.get() * 1000) {
                            loopWait = false;
                            if (points.size() > 1) {
                                currentIndex = 1;
                                waiting = false;
                                goTo(points.get(currentIndex));
                            } else {
                                waiting = false;
                                goTo(points.get(0));
                            }
                        }
                    }
                    else {
                        currentIndex++;
                        waiting = false;
                        goTo(points.get(currentIndex));
                    }
                }
            }
        }
    }

    private void goTo(BlockPos pos) {
        BaritoneAPI.getProvider().getPrimaryBaritone().getCustomGoalProcess().setGoalAndPath(new GoalBlock(pos));
    }

    @Override
    public void onDeactivate() {
        BaritoneAPI.getProvider().getPrimaryBaritone().getPathingBehavior().cancelEverything();
    }

    private void parsePoints() {
        points.clear();

        if (inputMode.get() == InputMode.String) {
            String[] entries = pointsString.get().split(";");
            for (String s : entries) {
                String[] parts = s.trim().split(",");
                if (parts.length == 3) {
                    try {
                        int x = Integer.parseInt(parts[0].trim());
                        int y = Integer.parseInt(parts[1].trim());
                        int z = Integer.parseInt(parts[2].trim());
                        points.add(new BlockPos(x, y, z));
                    } catch (NumberFormatException ignored) {}
                }
            }
        } else {
            if (pointsCount.get() >= 1) points.add(point1.get());
            if (pointsCount.get() >= 2) points.add(point2.get());
            if (pointsCount.get() >= 3) points.add(point3.get());
            if (pointsCount.get() >= 4) points.add(point4.get());
            if (pointsCount.get() >= 5) points.add(point5.get());
            if (pointsCount.get() >= 6) points.add(point6.get());
            if (pointsCount.get() >= 7) points.add(point7.get());
            if (pointsCount.get() >= 8) points.add(point8.get());
        }
    }
}
