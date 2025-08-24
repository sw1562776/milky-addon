package com.milky.hunt.modules;

import com.milky.hunt.Addon;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.gui.GuiTheme;
import meteordevelopment.meteorclient.gui.WindowScreen;
import meteordevelopment.meteorclient.gui.widgets.WWidget;
import meteordevelopment.meteorclient.gui.widgets.containers.WTable;
import meteordevelopment.meteorclient.gui.widgets.containers.WVerticalList;
import meteordevelopment.meteorclient.gui.widgets.input.WTextBox;
import meteordevelopment.meteorclient.gui.widgets.pressable.WButton;
import meteordevelopment.meteorclient.gui.widgets.pressable.WMinus;
import meteordevelopment.meteorclient.settings.BlockPosSetting;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.DoubleSetting;
import meteordevelopment.meteorclient.settings.IntSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.orbit.EventHandler;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import xaero.common.minimap.waypoints.Waypoint;
import xaero.hud.minimap.BuiltInHudModules;
import xaero.hud.minimap.module.MinimapSession;
import xaero.hud.minimap.waypoint.set.WaypointSet;
import xaero.hud.minimap.world.MinimapWorld;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;

import static meteordevelopment.meteorclient.MeteorClient.mc;

public class WaypointQueue extends Module {
    private static final int MAX_POINTS = 32;

    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgControl = settings.createGroup("Control");

    private final SettingGroup sgStorage = settings.createGroup("Storage (hidden)");

    private WTable liveTable;
    private GuiTheme liveTheme;

    private final Setting<Integer> count = sgGeneral.add(new IntSetting.Builder()
        .name("count")
        .description("Number of target points to use.")
        .defaultValue(4)
        .min(1)
        .max(MAX_POINTS)
        .sliderMax(MAX_POINTS)
        .onChanged(v -> refreshUi())
        .build()
    );

    @SuppressWarnings("unchecked")
    private final Setting<BlockPos>[] points = new Setting[MAX_POINTS];

    private final Setting<Double> arriveDist = sgControl.add(new DoubleSetting.Builder()
        .name("arrive-distance")
        .description("Horizontal distance threshold to switch to the next point.")
        .defaultValue(5.0)
        .min(0.1)
        .sliderMin(1.0)
        .sliderMax(50.0)
        .build()
    );

    private final Setting<Double> maxTurnPerTick = sgControl.add(new DoubleSetting.Builder()
        .name("max-turn-deg-per-tick")
        .description("Max degrees to turn per tick. 0 = instant.")
        .defaultValue(0.0)
        .min(0.0)
        .sliderMax(30.0)
        .build()
    );

    private final Setting<Boolean> loop = sgControl.add(new BoolSetting.Builder()
        .name("loop")
        .description("After the last point, continue from the first.")
        .defaultValue(true)
        .build()
    );

    private int currentIndex = 0;

    public WaypointQueue() {
        super(Addon.CATEGORY, "WaypointQueue", "Aim toward selected Xaero waypoints in order.");

        for (int i = 0; i < MAX_POINTS; i++) {
            points[i] = sgStorage.add(new BlockPosSetting.Builder()
                .name("point-" + (i + 1))
                .description("Target point " + (i + 1))
                .defaultValue(new BlockPos(0, 64, 0))
                .visible(() -> false)
                .build()
            );
        }
    }

    @Override
    public void onActivate() {
        currentIndex = 0;
    }

    @Override
    public String getInfoString() {
        int used = Math.max(1, Math.min(count.get(), MAX_POINTS));
        if (mc.player == null || points[currentIndex].get() == null) return null;
        BlockPos t = points[currentIndex].get();
        if (isEmpty(t)) return null;
        Vec3d pp = mc.player.getPos();
        double dx = (t.getX() + 0.5) - pp.x;
        double dz = (t.getZ() + 0.5) - pp.z;
        int dist = (int) Math.round(Math.sqrt(dx * dx + dz * dz));
        return (currentIndex + 1) + "/" + used + " | " + dist + "blocks";
    }

    @Override
    public WWidget getWidget(GuiTheme theme) {
        this.liveTheme = theme;
        WVerticalList list = theme.verticalList();

        liveTable = list.add(theme.table()).expandX().widget();
        fillTable(theme, liveTable);

        return list;
    }

    private void refreshUi() {
        if (liveTable != null && liveTheme != null) {
            liveTable.clear();
            fillTable(liveTheme, liveTable);
        }
    }

    private static int parseIntOr(String s, int fallback) {
        try {
            if (s == null || s.isEmpty() || "-".equals(s)) return fallback;
            return Integer.parseInt(s.trim());
        } catch (Exception ignored) {
            return fallback;
        }
    }

    private void fillTable(GuiTheme theme, WTable table) {
    table.clear();

    int used = Math.max(1, Math.min(count.get(), MAX_POINTS));
    for (int i = 0; i < used; i++) {
        final int idx = i;
        BlockPos p = points[idx].get();

        table.add(theme.label("#" + (idx + 1)));

        WButton pick = table.add(theme.button("Pick from Xaero")).widget();
        pick.action = () -> {
            if (!isXaeroAvailable()) {
                info("Xaero Minimap not loaded.");
                return;
            }
            mc.setScreen(new WaypointPickerScreen(theme, pos -> {
                points[idx].set(pos);
                refreshUi();
            }));
        };

        // x= [textbox]
        table.add(theme.label("x="));
        WTextBox xBox = table.add(theme.textBox(Integer.toString(p.getX()))).expandX().widget();

        // z= [textbox]
        table.add(theme.label("z="));
        WTextBox zBox = table.add(theme.textBox(Integer.toString(p.getZ()))).expandX().widget();

        Runnable sync = () -> {
            int x = parseIntOr(xBox.get(), p.getX());
            int z = parseIntOr(zBox.get(), p.getZ());
            int y = points[idx].get().getY();
            points[idx].set(new BlockPos(x, y, z));
        };
        xBox.action = sync;
        zBox.action = sync;

        WMinus clear = table.add(theme.minus()).widget();
        clear.action = () -> {
            points[idx].set(new BlockPos(0, 64, 0));
            refreshUi();
        };

        table.row();
    }
}


    @EventHandler
    private void onTick(TickEvent.Pre e) {
        if (mc.player == null || mc.world == null) return;

        int used = Math.max(1, Math.min(count.get(), MAX_POINTS));
        int guard = 0;

        while (guard < used && isEmpty(points[currentIndex].get())) {
            currentIndex = (currentIndex + 1) % used;
            guard++;
        }
        if (guard >= used && isEmpty(points[currentIndex].get())) return;

        BlockPos target = points[currentIndex].get();
        if (target == null) return;

        Vec3d playerPos = mc.player.getPos();
        Vec3d targetPos = new Vec3d(target.getX() + 0.5, playerPos.y, target.getZ() + 0.5);

        double dx = targetPos.x - playerPos.x;
        double dz = targetPos.z - playerPos.z;
        double distXZ = Math.sqrt(dx * dx + dz * dz);

        if (distXZ <= arriveDist.get()) {
            advance(used);
            return;
        }

        float desiredYaw = (float) (MathHelper.atan2(dz, dx) * (180.0 / Math.PI)) - 90f;
        float yaw = mc.player.getYaw();

        float newYaw = approachAngle(yaw, desiredYaw, maxTurnPerTick.get().floatValue());
        mc.player.setYaw(newYaw);
    }

    private void advance(int used) {
        if (currentIndex + 1 < used) currentIndex++;
        else if (loop.get()) currentIndex = 0;
        else {
            toggle();
            info("Finished.");
        }
    }

    private static boolean isEmpty(BlockPos p) {
        return p == null || (p.getX() == 0 && p.getZ() == 0 && p.getY() == 64);
    }

    private static float approachAngle(float current, float target, float maxStep) {
        float delta = MathHelper.wrapDegrees(target - current);
        if (maxStep <= 0f) return current + delta;
        if (delta > maxStep) delta = maxStep;
        if (delta < -maxStep) delta = -maxStep;
        return current + delta;
    }

    private static boolean isXaeroAvailable() {
        return FabricLoader.getInstance().isModLoaded("xaerominimap");
    }

    private static WaypointSet getWaypointSet() {
        if (!isXaeroAvailable()) return null;
        MinimapSession s = BuiltInHudModules.MINIMAP.getCurrentSession();
        if (s == null) return null;
        MinimapWorld w = s.getWorldManager().getCurrentWorld();
        if (w == null) return null;
        return w.getCurrentWaypointSet();
    }

    private static List<Waypoint> listXaeroWaypoints() {
        WaypointSet set = getWaypointSet();
        if (set == null) return Collections.emptyList();
        List<Waypoint> out = new ArrayList<Waypoint>();
        for (Waypoint w : set.getWaypoints()) out.add(w);
        return out;
    }

    private static class WaypointPickerScreen extends WindowScreen {
        private final Consumer<BlockPos> onPick;

        protected WaypointPickerScreen(GuiTheme theme, Consumer<BlockPos> onPick) {
            super(theme, "Pick Xaero Waypoint");
            this.onPick = Objects.requireNonNull(onPick);
        }

        @Override
        public void initWidgets() {
            WVerticalList list = add(theme.verticalList()).expandX().widget();

            List<Waypoint> wps = listXaeroWaypoints();
            if (wps.isEmpty()) {
                list.add(theme.label("No Xaero waypoints in current world."));
                return;
            }

            WTable table = list.add(theme.table()).expandX().widget();

            for (Waypoint w : wps) {
                String name = (w.getName() == null || w.getName().isEmpty()) ? "(unnamed)" : w.getName();
                table.add(theme.label(name));
                table.add(theme.label("  [" + w.getX() + ", " + w.getY() + ", " + w.getZ() + "]"));

                WButton use = table.add(theme.button("Use")).widget();
                use.action = () -> {
                    onPick.accept(new BlockPos(w.getX(), w.getY(), w.getZ()));
                    close();
                };

                table.row();
            }
        }
    }
}
