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
        .defaultValue(2)
        .min(1)
        .max(16)
        .visible(() -> inputMode.get() == InputMode.Simple)
        .build()
    );

        private final List<Setting<BlockPos>> pointSettings = new ArrayList<>();


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
    
        // Create compact point settings via loop (defaults unified to 0,64,0)
        for (int i = 1; i <= 16; i++) {
            final int idx = i;
            pointSettings.add(
                sgGeneral.add(new BlockPosSetting.Builder()
                    .name("point-" + i)
                    .description("Coordinate of point " + i + ".")
                    .defaultValue(new BlockPos(0, 64, 0))
                    .visible(() -> inputMode.get() == InputMode.Simple && pointsCount.get() >= idx)
                    .build()
                )
            );
        }
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
            for (int i = 0; i < Math.min(pointsCount.get(), pointSettings.size()); i++) {
                points.add(pointSettings.get(i).get());
            }
        }
    }
}
