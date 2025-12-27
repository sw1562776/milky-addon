package com.milky.hunt.modules;

import com.milky.hunt.Addon;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.entity.Entity;
import net.minecraft.entity.mob.PhantomEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import xaero.common.minimap.waypoints.Waypoint;
import xaero.hud.minimap.BuiltInHudModules;
import xaero.hud.minimap.module.MinimapSession;
import xaero.hud.minimap.waypoint.set.WaypointSet;
import xaero.hud.minimap.world.MinimapWorld;
import xaero.map.mods.SupportMods;

import java.util.*;

public class PhantomStatistics extends Module {
    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    // --- Filters / behavior ---
    private final Setting<Boolean> onlyEnd = sgGeneral.add(new BoolSetting.Builder()
        .name("only-end")
        .description("Only run in The End dimension.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> yFilter = sgGeneral.add(new BoolSetting.Builder()
        .name("y-filter")
        .description("Only record phantoms whose absolute Y is outside [playerY - deltaY, playerY + deltaY].")
        .defaultValue(true)
        .build()
    );

    private final Setting<Double> deltaY = sgGeneral.add(new DoubleSetting.Builder()
        .name("delta-y")
        .description("Record if abs(phantomY - playerY) > deltaY.")
        .defaultValue(200)
        .min(0)
        .sliderRange(0, 5000)
        .visible(yFilter::get)
        .build()
    );

    private final Setting<Integer> cooldownSeconds = sgGeneral.add(new IntSetting.Builder()
        .name("cooldown-seconds")
        .description("Minimum seconds before recording the same phantom (UUID) again.")
        .defaultValue(60)
        .min(0)
        .sliderRange(0, 600)
        .build()
    );

    private final Setting<Integer> maxMarksPerTick = sgGeneral.add(new IntSetting.Builder()
        .name("max-marks-per-tick")
        .description("Limit how many events/waypoints can be recorded in a single tick.")
        .defaultValue(2)
        .min(1)
        .sliderRange(1, 50)
        .build()
    );

    // --- Waypoints (optional) ---
    private final Setting<Boolean> saveToWaypoints = sgGeneral.add(new BoolSetting.Builder()
        .name("save-to-waypoints")
        .description("Create Xaero minimap waypoints for detected phantoms.")
        .defaultValue(true)
        .build()
    );

    private final Setting<String> waypointPrefix = sgGeneral.add(new StringSetting.Builder()
        .name("waypoint-prefix")
        .description("Prefix for waypoint names.")
        .defaultValue("Ph")
        .visible(saveToWaypoints::get)
        .build()
    );

    private final Setting<String> waypointSymbol = sgGeneral.add(new StringSetting.Builder()
        .name("waypoint-symbol")
        .description("Xaero waypoint short symbol.")
        .defaultValue("P")
        .visible(saveToWaypoints::get)
        .build()
    );

    private final Setting<Integer> waypointColor = sgGeneral.add(new IntSetting.Builder()
        .name("waypoint-color")
        .description("Xaero waypoint color index (0-15 typically).")
        .defaultValue(4)
        .min(0).max(15).sliderMax(15)
        .visible(saveToWaypoints::get)
        .build()
    );

    // --- Stats on HUD (absolute Y clustering) ---
    private final Setting<Boolean> densityStats = sgGeneral.add(new BoolSetting.Builder()
        .name("density-stats")
        .description("Show Y-layer density on HUD (events per 1000 blocks traveled).")
        .defaultValue(true)
        .build()
    );

    private final Setting<Double> avgDistance = sgGeneral.add(new DoubleSetting.Builder()
        .name("avg-distance")
        .description("Distance window (blocks) used for averaging density.")
        .defaultValue(1000)
        .min(100)
        .sliderRange(100, 20000)
        .visible(densityStats::get)
        .build()
    );

    private final Setting<Double> layerGap = sgGeneral.add(new DoubleSetting.Builder()
        .name("layer-gap")
        .description("Max absolute-Y gap (blocks) to be considered the same layer.")
        .defaultValue(120)
        .min(1)
        .sliderRange(10, 1000)
        .visible(densityStats::get)
        .build()
    );

    private final Setting<Integer> topLayers = sgGeneral.add(new IntSetting.Builder()
        .name("top-layers")
        .description("How many top dense layers to show.")
        .defaultValue(3)
        .min(1)
        .sliderRange(1, 10)
        .visible(densityStats::get)
        .build()
    );

    // --- Runtime state ---
    private final Map<UUID, Long> lastSeen = new HashMap<>();

    private double traveledXZ = 0.0;
    private Vec3d lastPos = null;

    // record events: (distAtRecord, phantomAbsY)
    private final ArrayDeque<MarkEvent> events = new ArrayDeque<>();

    private static class MarkEvent {
        final double distXZ;
        final double absY;
        MarkEvent(double distXZ, double absY) {
            this.distXZ = distXZ;
            this.absY = absY;
        }
    }

    private static class DensityLine {
        final double densityPer1k;
        final String label;
        DensityLine(double densityPer1k, String label) {
            this.densityPer1k = densityPer1k;
            this.label = label;
        }
    }

    public PhantomStatistics() {
        super(Addon.MilkyModCategory, "PhantomStats",
            "Detect phantoms, optionally mark Xaero waypoints, and show Y layer density on HUD.");
    }

    @Override
    public void onActivate() {
        traveledXZ = 0.0;
        lastPos = null;
        lastSeen.clear();
        events.clear();
    }

    @Override
    public String getInfoString() {
        if (!densityStats.get()) return null;

        List<DensityLine> top = computeTopDensity();
        if (top.isEmpty()) return null;

        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < top.size(); i++) {
            if (i > 0) sb.append(" | ");
            sb.append(top.get(i).label);
        }
        return sb.toString();
    }

    @EventHandler
    private void onTick(TickEvent.Post event) {
        if (!isActive()) return;
        if (mc.player == null || mc.world == null) return;

        if (onlyEnd.get()) {
            String dim = mc.world.getRegistryKey().getValue().toString();
            if (!"minecraft:the_end".equals(dim)) return;
        }

        // Update traveled distance (XZ)
        Vec3d nowPos = mc.player.getPos();
        if (lastPos != null) {
            double dx = nowPos.x - lastPos.x;
            double dz = nowPos.z - lastPos.z;
            traveledXZ += Math.sqrt(dx * dx + dz * dz);
        }
        lastPos = nowPos;

        pruneEvents();

        long nowMs = System.currentTimeMillis();
        long cdMs = Math.max(0, cooldownSeconds.get()) * 1000L;

        double py = mc.player.getY();
        double dyLimit = deltaY.get();

        WaypointSet waypointSet = saveToWaypoints.get() ? getWaypointSet() : null;

        int recordedThisTick = 0;

        for (Entity ent : mc.world.getEntities()) {
            if (!(ent instanceof PhantomEntity phantom)) continue;

            double absY = phantom.getY();

            if (yFilter.get() && Math.abs(absY - py) <= dyLimit) continue;

            UUID id = phantom.getUuid();
            if (cdMs > 0) {
                Long last = lastSeen.get(id);
                if (last != null && (nowMs - last) < cdMs) continue;
            }

            // Record event for statistics (ABSOLUTE Y)
            events.addLast(new MarkEvent(traveledXZ, absY));
            lastSeen.put(id, nowMs);
            recordedThisTick++;

            // Optional waypoint (ABSOLUTE Y is already used in waypoint coords)
            if (saveToWaypoints.get() && waypointSet != null) {
                BlockPos p = phantom.getBlockPos(); // x,y,z are absolute coords
                if (!waypointExists(waypointSet, p.getX(), p.getY(), p.getZ(), waypointPrefix.get())) {
                    Waypoint wp = new Waypoint(
                        p.getX(),
                        p.getY(),
                        p.getZ(),
                        makeWaypointName(p.getY()),
                        waypointSymbol.get(),
                        waypointColor.get(),
                        0,
                        false
                    );
                    waypointSet.add(wp);
                    SupportMods.xaeroMinimap.requestWaypointsRefresh();
                }
            }

            if (recordedThisTick >= maxMarksPerTick.get()) break;
        }

        pruneUuidMap(nowMs, Math.max(10_000L, cdMs * 2));
    }

    // ---------------- density computation (adaptive clustering on ABSOLUTE Y) ----------------

    private List<DensityLine> computeTopDensity() {
        if (events.isEmpty()) return Collections.emptyList();

        double window = avgDistance.get();
        double nowD = traveledXZ;
        double minD = nowD - window;

        ArrayList<Double> ys = new ArrayList<>();
        double earliestD = Double.POSITIVE_INFINITY;

        for (MarkEvent e : events) {
            if (e.distXZ >= minD) {
                ys.add(e.absY);
                if (e.distXZ < earliestD) earliestD = e.distXZ;
            }
        }
        if (ys.isEmpty()) return Collections.emptyList();

        double usedWindow = Math.max(1.0, nowD - earliestD);
        double scale = 1000.0 / usedWindow; // count * scale => per 1000 blocks

        ys.sort(Double::compareTo);

        double gap = layerGap.get();

        ArrayList<double[]> clusters = new ArrayList<>(); // {sum, count}
        double sum = ys.get(0);
        int cnt = 1;

        for (int i = 1; i < ys.size(); i++) {
            double v = ys.get(i);
            double prev = ys.get(i - 1);
            if (v - prev <= gap) {
                sum += v;
                cnt++;
            } else {
                clusters.add(new double[]{sum, cnt});
                sum = v;
                cnt = 1;
            }
        }
        clusters.add(new double[]{sum, cnt});

        ArrayList<DensityLine> lines = new ArrayList<>();
        for (double[] c : clusters) {
            double mean = c[0] / c[1];
            int yCenter = (int) Math.round(mean);
            double dens = c[1] * scale;

            String label = "y" + yCenter + " " + fmt1(dens) + "/k";
            lines.add(new DensityLine(dens, label));

        }

        lines.sort((a, b) -> Double.compare(b.densityPer1k, a.densityPer1k));

        int n = Math.min(topLayers.get(), lines.size());
        return lines.subList(0, n);
    }

    private void pruneEvents() {
        double window = avgDistance.get();
        double minD = traveledXZ - window;
        double buffer = Math.max(50.0, window * 0.05);
        double cutoff = minD - buffer;

        while (!events.isEmpty() && events.peekFirst().distXZ < cutoff) {
            events.pollFirst();
        }
    }

    private void pruneUuidMap(long nowMs, long maxAgeMs) {
        if (lastSeen.size() < 512) return;
        Iterator<Map.Entry<UUID, Long>> it = lastSeen.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<UUID, Long> en = it.next();
            if (nowMs - en.getValue() > maxAgeMs) it.remove();
        }
    }

    // ---------------- Xaero helpers ----------------

    private WaypointSet getWaypointSet() {
        try {
            MinimapSession minimapSession = BuiltInHudModules.MINIMAP.getCurrentSession();
            if (minimapSession == null) return null;
            MinimapWorld currentWorld = minimapSession.getWorldManager().getCurrentWorld();
            if (currentWorld == null) return null;
            return currentWorld.getCurrentWaypointSet();
        } catch (Throwable t) {
            return null;
        }
    }

    private boolean waypointExists(WaypointSet waypointSet, int x, int y, int z, String prefix) {
        try {
            for (Waypoint w : waypointSet.getWaypoints()) {
                if (w.getX() == x && w.getY() == y && w.getZ() == z) {
                    String n = w.getName();
                    if (n != null && n.startsWith(prefix)) return true;
                }
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    private String makeWaypointName(int absY) {
        // absolute Y in name
        return waypointPrefix.get() + " y" + absY;
    }

    // ---------------- format helpers ----------------

    private static String fmt0(double v) {
        if (Math.abs(v - Math.rint(v)) < 1e-9) return Long.toString(Math.round(v));
        return String.format(Locale.ROOT, "%.1f", v);
    }

    private static String fmt1(double v) {
        return String.format(Locale.ROOT, "%.1f", v);
    }
}
