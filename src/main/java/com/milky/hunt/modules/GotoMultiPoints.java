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

    public enum InputMode {
        String,
        Simple
    }

    // 输入模式选择
    private final Setting<InputMode> inputMode = sgGeneral.add(new EnumSetting.Builder<InputMode>()
        .name("input-mode")
        .description("Choose how to input patrol points.")
        .defaultValue(InputMode.String)
        .build()
    );

    // String 模式输入
    private final Setting<String> pointsString = sgGeneral.add(new StringSetting.Builder()
        .name("points")
        .description("Coordinates in format: x,y,z; x,y,z; ...")
        .defaultValue("0,64,0; 16,64,16")
        .visible(() -> inputMode.get() == InputMode.String)
        .build()
    );

    // Simple 模式输入（8个点）
    private final Setting<BlockPos> point1 = sgGeneral.add(new BlockPosSetting.Builder()
        .name("Point 1")
        .defaultValue(new BlockPos(0, 64, 0))
        .visible(() -> inputMode.get() == InputMode.Simple)
        .build()
    );

    private final Setting<BlockPos> point2 = sgGeneral.add(new BlockPosSetting.Builder()
        .name("Point 2")
        .defaultValue(new BlockPos(16, 64, 16))
        .visible(() -> inputMode.get() == InputMode.Simple)
        .build()
    );

    private final Setting<BlockPos> point3 = sgGeneral.add(new BlockPosSetting.Builder()
        .name("Point 3")
        .defaultValue(new BlockPos(0, 64, 0))
        .visible(() -> inputMode.get() == InputMode.Simple)
        .build()
    );

    private final Setting<BlockPos> point4 = sgGeneral.add(new BlockPosSetting.Builder()
        .name("Point 4")
        .defaultValue(new BlockPos(0, 64, 0))
        .visible(() -> inputMode.get() == InputMode.Simple)
        .build()
    );

    private final Setting<BlockPos> point5 = sgGeneral.add(new BlockPosSetting.Builder()
        .name("Point 5")
        .defaultValue(new BlockPos(0, 64, 0))
        .visible(() -> inputMode.get() == InputMode.Simple)
        .build()
    );

    private final Setting<BlockPos> point6 = sgGeneral.add(new BlockPosSetting.Builder()
        .name("Point 6")
        .defaultValue(new BlockPos(0, 64, 0))
        .visible(() -> inputMode.get() == InputMode.Simple)
        .build()
    );

    private final Setting<BlockPos> point7 = sgGeneral.add(new BlockPosSetting.Builder()
        .name("Point 7")
        .defaultValue(new BlockPos(0, 64, 0))
        .visible(() -> inputMode.get() == InputMode.Simple)
        .build()
    );

    private final Setting<BlockPos> point8 = sgGeneral.add(new BlockPosSetting.Builder()
        .name("Point 8")
        .defaultValue(new BlockPos(0, 64, 0))
        .visible(() -> inputMode.get() == InputMode.Simple)
        .build()
    );

    private final Setting<Boolean> loop = sgGeneral.add(new BoolSetting.Builder()
        .name("loop")
        .description("Whether to loop through points or stop after the last one.")
        .defaultValue(true)
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
            parseStringPoints();
        } else {
            parseSimplePoints();
        }
    }

    private void parseStringPoints() {
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
    }

    private void parseSimplePoints() {
        addPointIfValid(point1.get());
        addPointIfValid(point2.get());
        addPointIfValid(point3.get());
        addPointIfValid(point4.get());
        addPointIfValid(point5.get());
        addPointIfValid(point6.get());
        addPointIfValid(point7.get());
        addPointIfValid(point8.get());
    }

    private void addPointIfValid(BlockPos pos) {
        if (!(pos.getX() == 0 && pos.getY() == 0 && pos.getZ() == 0)) {
            points.add(pos);
        }
    }
}
