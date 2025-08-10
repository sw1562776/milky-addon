package com.milky.hunt.modules;

import com.milky.hunt.Addon;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.orbit.EventHandler;
import meteordevelopment.meteorclient.events.world.TickEvent;
import net.minecraft.util.math.BlockPos;
import baritone.api.BaritoneAPI;
import baritone.api.pathing.goals.GoalBlock;

import java.util.ArrayList;
import java.util.List;

public class GotoMultiPoints extends Module {
    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    private final Setting<String> pointsString = sgGeneral.add(new StringSetting.Builder()
        .name("points")
        .description("List of waypoints in format x,y,z; x,y,z; ...")
        .defaultValue("0,64,0; 100,70,100")
        .build()
    );

    private final List<BlockPos> points = new ArrayList<>();
    private int currentIndex = 0;

    public GotoMultiPoints() {
        super(Addon.CATEGORY, "goto-multi-points", "Goto multiple waypoints using Baritone.");
    }

    @Override
    public void onActivate() {
        parsePoints();
        currentIndex = 0;
        gotoNextPoint();
    }

    @EventHandler
    private void onTick(TickEvent.Post event) {
        if (points.isEmpty()) return;

        BlockPos currentPoint = points.get(currentIndex);
        if (mc.player.getBlockPos().isWithinDistance(currentPoint, 2)) {
            currentIndex++;
            if (currentIndex < points.size()) {
                gotoNextPoint();
            } else {
                toggle();
            }
        }
    }

    private void gotoNextPoint() {
        BlockPos target = points.get(currentIndex);
        BaritoneAPI.getProvider().getPrimaryBaritone().getCustomGoalProcess()
            .setGoalAndPath(new GoalBlock(target));
    }

    private void parsePoints() {
        points.clear();
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
}
