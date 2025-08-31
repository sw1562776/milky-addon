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
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import xaero.common.minimap.waypoints.Waypoint;
import xaero.hud.minimap.BuiltInHudModules;
import xaero.hud.minimap.module.MinimapSession;
import xaero.hud.minimap.waypoint.set.WaypointSet;
import xaero.hud.minimap.world.MinimapWorld;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;

import static meteordevelopment.meteorclient.MeteorClient.mc;

public class WaypointQueue extends Module {
    private static final int MAX_POINTS = 128;

    private final SettingGroup sgGeneral = settings.getDefaultGroup();
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

    private final Setting<Boolean> loop = sgGeneral.add(new BoolSetting.Builder()
        .name("loop")
        .description("After the last point, continue from the first.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Double> arriveDist = sgGeneral.add(new DoubleSetting.Builder()
        .name("arrive-distance")
        .description("Distance in blocks to consider arrival (XZ plane).")
        .defaultValue(3.0)
        .min(0.0)
        .sliderMax(20.0)
        .build()
    );

    private final Setting<Double> yawSpeed = sgGeneral.add(new DoubleSetting.Builder()
        .name("yaw-speed")
        .description("Max yaw delta per tick (deg).")
        .defaultValue(6.0)
        .min(0.5)
        .sliderMax(30.0)
        .build()
    );

    // --- stitched in from your "clear visited" version ---
    private final Setting<Boolean> clearVisited = sgGeneral.add(new BoolSetting.Builder()
        .name("clear-visited")
        .description("Clear a point back to 0 64 0 after it is reached.")
        .defaultValue(false)
        .build()
    );
    // ------------------------------------------------------

    private int currentIndex = 0;

    @SuppressWarnings("unchecked")
    private final Setting<BlockPos>[] points = new Setting[MAX_POINTS];

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
        return (currentIndex + 1) + "/" + used;
    }

    @EventHandler
    private void onTick(TickEvent.Post e) {
        if (mc.player == null) return;

        int used = Math.max(1, Math.min(count.get(), MAX_POINTS));
        clampCurrent(used);

        int guard = 0;
        while (guard++ < used && isEmpty(points[currentIndex].get())) {
            advance(used);
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
            // stitched behavior: clear to (0,64,0) when reached if enabled
            if (clearVisited.get()) {
                points[currentIndex].set(new BlockPos(0, 64, 0));
                refreshUi();
            }
            advance(used);
            return;
        }

        float desiredYaw = (float) (Math.toDegrees(Math.atan2(-dx, dz)));
        ClientPlayerEntity p = mc.player;
        float currentYaw = p.getYaw();
        float delta = wrapDegrees(desiredYaw - currentYaw);
        float maxStep = yawSpeed.get().floatValue();
        if (delta > maxStep) delta = maxStep;
        if (delta < -maxStep) delta = -maxStep;

        p.setYaw(currentYaw + delta);
    }

    private void advance(int used) {
        currentIndex++;
        if (currentIndex >= used) {
            if (loop.get()) currentIndex = 0;
            else currentIndex = used - 1;
        }
    }

    private void clampCurrent(int used) {
        if (currentIndex < 0) currentIndex = 0;
        if (currentIndex >= used) currentIndex = used - 1;
    }

    private static boolean isEmpty(BlockPos p) {
        return p == null || (p.getX() == 0 && p.getZ() == 0);
    }

    private static int parseIntOr(String s, int fallback) {
        try { return Integer.parseInt(s.trim()); }
        catch (Exception ignored) { return fallback; }
    }

    private static float wrapDegrees(float degrees) {
        degrees = degrees % 360.0f;
        if (degrees >= 180.0f) degrees -= 360.0f;
        if (degrees < -180.0f) degrees += 360.0f;
        return degrees;
    }

    public WWidget getWidget(GuiTheme theme) {
        this.liveTheme = theme;
        WVerticalList list = theme.verticalList();

        WButton bulk = list.add(theme.button("Load first N from Xaero (reverse subset)"))
            .expandX().widget();
        bulk.action = this::bulkLoad;

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

    private void bulkLoad() {
        int used = Math.max(1, Math.min(count.get(), MAX_POINTS));
        if (!isXaeroAvailable()) {
            info("Xaero Minimap not loaded.");
            return;
        }
        WaypointSet set = getWaypointSet();
        if (set == null) {
            info("No Xaero waypoints.");
            return;
        }

        List<Waypoint> all = new ArrayList<>();
        for (Object o : set.getWaypoints()) {
            if (o instanceof Waypoint) all.add((Waypoint) o);
        }

        int n = Math.min(used, all.size());
        if (n <= 0) {
            info("No Xaero waypoints.");
            return;
        }

        List<Waypoint> sub = new ArrayList<>(all.subList(0, n));
        Collections.reverse(sub);

        for (int i = 0; i < n; i++) {
            Waypoint w = sub.get(i);
            points[i].set(new BlockPos(w.getX(), w.getY(), w.getZ()));
        }

        refreshUi();
    }

    void fillTable(GuiTheme theme, WTable table) {
        table.clear();

        int used = Math.max(1, Math.min(count.get(), MAX_POINTS));

        for (int i = 0; i < used; i++) {
            final int idx = i;
            BlockPos p = points[idx].get();

            table.add(theme.label("#" + (idx + 1) + "   "));

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

            table.add(theme.label("       x = "));
            WTextBox xBox = table.add(theme.textBox(Integer.toString(p.getX()))).expandX().widget();

            table.add(theme.label("       z =  "));
            WTextBox zBox = table.add(theme.textBox(Integer.toString(p.getZ()))).expandX().widget();
            table.add(theme.label("       "));

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
                // manual clear keeps current Y like your original version
                points[idx].set(new BlockPos(0, points[idx].get().getY(), 0));
                refreshUi();
            };

            table.row();
        }
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
        List<Waypoint> out = new ArrayList<>();
        for (Iterator<?> it = set.getWaypoints().iterator(); it.hasNext(); ) {
            Object o = it.next();
            if (o instanceof Waypoint) out.add((Waypoint) o);
        }
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
            WTable table = add(theme.table()).expandX().widget();

            List<Waypoint> wps = listXaeroWaypoints();
            if (wps.isEmpty()) {
                table.add(theme.label("No Xaero waypoints."));
                return;
            }

            for (int i = 0; i < wps.size(); i++) {
                Waypoint w = wps.get(i);
                String name = w.getName() == null || w.getName().isBlank() ? ("#" + (i + 1)) : w.getName();

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
