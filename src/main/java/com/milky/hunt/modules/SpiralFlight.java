package com.milky.hunt.modules;

import com.milky.hunt.Addon;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.orbit.EventHandler;
import meteordevelopment.orbit.EventPriority;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MathHelper;

import static meteordevelopment.meteorclient.MeteorClient.mc;

public class SpiralFlight extends Module {
    // ========= GUI =========
    private final SettingGroup sgGeneral  = settings.getDefaultGroup();
    private final SettingGroup sgControl  = settings.createGroup("Control");
    private final SettingGroup sgAdvanced = settings.createGroup("Advanced");

    // General
    private final Setting<BlockPos> center = sgGeneral.add(new BlockPosSetting.Builder()
        .name("center")
        .description("Spiral center (XZ used, Y ignored)")
        .defaultValue(new BlockPos(0, 64, 0))
        .build()
    );

    private final Setting<Double> ringSpacing = sgGeneral.add(new DoubleSetting.Builder()
        .name("ring-spacing-Δr")
        .description("Radius increment per full turn Δr (blocks)")
        .defaultValue(960.0).min(1.0).sliderMax(8192.0)
        .build()
    );

    private final Setting<Boolean> clockwise = sgGeneral.add(new BoolSetting.Builder()
        .name("clockwise")
        .description("Visual clockwise if true; visual counterclockwise if false")
        .defaultValue(false)
        .build()
    );

    // Control
    private final Setting<Double> chordLen = sgControl.add(new DoubleSetting.Builder()
        .name("chord-length-s")
        .description("Target chord length s between samples (blocks)")
        .defaultValue(1.5).min(0.1).sliderMax(10.0)
        .build()
    );

    private final Setting<Double> lookahead = sgControl.add(new DoubleSetting.Builder()
        .name("lookahead-ℓ")
        .description("Pure-pursuit lookahead distance ℓ (blocks)")
        .defaultValue(6.0).min(1.0).sliderMax(32.0)
        .build()
    );

    private final Setting<Double> corridor = sgControl.add(new DoubleSetting.Builder()
        .name("corridor-width-w")
        .description("Half corridor width w; inside it a small lead step is applied")
        .defaultValue(8.0).min(1.0).sliderMax(64.0)
        .build()
    );

    private final Setting<Double> yawRateDegPerTick = sgControl.add(new DoubleSetting.Builder()
        .name("max-yaw-rate-deg-per-tick")
        .description("Max yaw change per tick (deg/tick)")
        .defaultValue(10.0).min(0.0).sliderMax(180.0)
        .build()
    );

    private final Setting<Double> centerReach = sgControl.add(new DoubleSetting.Builder()
        .name("center-reach-radius")
        .description("Distance to center to start spiral (blocks)")
        .defaultValue(8).min(1.0).sliderMax(64.0)
        .build()
    );

    private final Setting<Double> leadStepMul = sgControl.add(new DoubleSetting.Builder()
        .name("lead-step-multiplier")
        .description("Lead step multiplier when inside corridor (0 = no lead)")
        .defaultValue(0.5).min(0.0).sliderMax(3.0)
        .build()
    );

    // Advanced
    private final Setting<Integer> nearestSamples = sgAdvanced.add(new IntSetting.Builder()
        .name("nearest-samples")
        .description("Samples for nearest-point search")
        .defaultValue(192).min(32).sliderMax(512)
        .build()
    );

    private final Setting<Double> searchWindow = sgAdvanced.add(new DoubleSetting.Builder()
        .name("search-window-rad")
        .description("Search window around current theta (radians)")
        .defaultValue(0.8).min(0.1).sliderMax(3.0)
        .build()
    );

    private final Setting<Integer> refineIters = sgAdvanced.add(new IntSetting.Builder()
        .name("refine-iters")
        .description("Ternary refinement iterations after sampling")
        .defaultValue(3).min(0).sliderMax(8)
        .build()
    );

    private final Setting<Double> maxAdvanceSteps = sgAdvanced.add(new DoubleSetting.Builder()
        .name("max-advance-steps")
        .description("Max per-tick parameter advance in units of |Δθ_step|")
        .defaultValue(3.0).min(0.5).sliderMax(20.0)
        .build()
    );

    private final Setting<Double> dtMin = sgAdvanced.add(new DoubleSetting.Builder()
        .name("dt-min")
        .description("Minimum |Δθ| clamp (radians)")
        .defaultValue(1e-4).min(1e-6).sliderMax(0.01)
        .build()
    );

    private final Setting<Double> dtMax = sgAdvanced.add(new DoubleSetting.Builder()
        .name("dt-max")
        .description("Maximum |Δθ| clamp (radians)")
        .defaultValue(0.25).min(0.01).sliderMax(1.0)
        .build()
    );

    // ========= Runtime =========
    private enum Phase { GO_TO_CENTER, SPIRAL, DONE }
    private Phase phase;

    private double thetaOnPath;   // parameter (radians)
    private double tauStartRef;   // for potential future use (turns HUD, etc.)

    public SpiralFlight() {
        super(Addon.CATEGORY, "SpiralFlight", "Fly an Archimedean spiral.");
    }

    @Override
    public void onActivate() {
        if (mc.player == null || mc.world == null) { toggle(); return; }

        thetaOnPath = 0.0;
        tauStartRef = 0.0;

        double[] c = centerXZ();
        double qx = mc.player.getX(), qz = mc.player.getZ();
        if (dist2(qx, qz, c[0], c[1]) > sq(centerReach.get())) {
            phase = Phase.GO_TO_CENTER;
        } else {
            startSpiral();
            return;
        }
    }

    @Override
    public void onDeactivate() {
        phase = Phase.DONE;
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    private void onTick(TickEvent.Pre event) {
        if (mc.player == null || mc.world == null) return;

        double[] c = centerXZ();
        double qx = mc.player.getX(), qz = mc.player.getZ();

        if (phase == Phase.GO_TO_CENTER) {
            double yawTargetDeg = Math.toDegrees(Math.atan2(c[1] - qz, c[0] - qx));
            faceYawSmooth((float) yawTargetDeg, yawRateDegPerTick.get().floatValue());
            if (dist2(qx, qz, c[0], c[1]) <= sq(centerReach.get())) startSpiral();
            return;
        }
        if (phase != Phase.SPIRAL) return;

        // === Spiral follow ===
        final double B  = ringSpacing.get() / (2.0 * Math.PI);
        final boolean cwVisual = clockwise.get();

        final double s  = chordLen.get();
        final double L  = lookahead.get();
        final double w  = corridor.get();
        final double dtMinVal = dtMin.get();
        final double dtMaxVal = dtMax.get();

        // Adaptive |Δθ| at current thetaOnPath
        double tauHere = cwVisual ? +thetaOnPath : -thetaOnPath; // tau increases along chosen visual direction
        double rHere = Math.max(0.0, B * tauHere);
        double dthetaMag = s / Math.max(1e-6, Math.sqrt(rHere * rHere + B * B));
        dthetaMag = clampAbs(dthetaMag, dtMinVal, dtMaxVal);
        double dthetaStep = cwVisual ? +dthetaMag : -dthetaMag;

        // Nearest-point projection around thetaOnPath
        double[] nearest = nearestOnCurve(thetaOnPath, B, c[0], c[1], qx, qz, cwVisual,
                                          searchWindow.get(), nearestSamples.get(), refineIters.get());
        double thetaNearest = nearest[0];
        double distNearest  = nearest[1];

        // Monotonic advance toward projection (no reverse): for CCW we decrease theta, for CW we increase theta
        double maxAdvance = maxAdvanceSteps.get() * Math.abs(dthetaStep);
        thetaOnPath = advanceMonotonic(thetaOnPath, thetaNearest, maxAdvance, cwVisual);

        // Base theta for aiming: small lead step when inside corridor
        double thetaBase = thetaOnPath;
        if (distNearest <= w && leadStepMul.get() > 0.0) {
            thetaBase += (cwVisual ? +1.0 : -1.0) * Math.abs(dthetaStep) * leadStepMul.get();
        }

        // Lookahead and steering
        final double thetaLook = integrateLookahead(thetaBase, L, B, cwVisual, s, dtMinVal, dtMaxVal);
        final double[] pStar = posPolar(thetaLook, B, c[0], c[1], cwVisual);
        final double yawTargetDeg = Math.toDegrees(Math.atan2(pStar[1] - qz, pStar[0] - qx));
        faceYawSmooth((float) yawTargetDeg, yawRateDegPerTick.get().floatValue());
    }

    // ===== Helpers =====
    private void startSpiral() {
        thetaOnPath = 0.0;
        phase = Phase.SPIRAL;
    }

    private double[] centerXZ() {
        BlockPos c = center.get();
        return new double[] { c.getX() + 0.5, c.getZ() + 0.5 }; // block center
    }

    private static double sq(double v) { return v * v; }

    // Squared distance on XZ
    private static double dist2(double x1, double z1, double x2, double z2) {
        double dx = x1 - x2, dz = z1 - z2;
        return dx * dx + dz * dz;
    }

    private static double clampAbs(double v, double minAbs, double maxAbs) {
        double s = Math.signum(v == 0.0 ? 1.0 : v);
        double a = Math.abs(v);
        if (a < minAbs) a = minAbs;
        if (a > maxAbs) a = maxAbs;
        return s * a;
    }

    // XZ polar with r = B * tau, tau = (cw ? +theta : -theta)
    private static double[] posPolar(double theta, double B, double xc, double zc, boolean cwVisual) {
        double tau = cwVisual ? +theta : -theta;
        double r = Math.max(0.0, B * tau);
        double x = xc + r * Math.cos(theta);
        double z = zc + r * Math.sin(theta);
        return new double[] { x, z };
    }

    private void faceYawSmooth(float yawTargetDeg, float maxDeltaDeg) {
        float yaw = mc.player.getYaw();
        float diff = wrapDeg(yawTargetDeg - yaw);
        if (maxDeltaDeg <= 0f) {
            mc.player.setYaw(yaw + diff); //LOCK
            return;
        }
        float step = MathHelper.clamp(diff, -maxDeltaDeg, maxDeltaDeg);
        mc.player.setYaw(yaw + step);
    }

    private static float wrapDeg(double deg) {
        float f = (float) deg;
        while (f <= -180.0f) f += 360.0f;
        while (f > 180.0f) f -= 360.0f;
        return f;
    }

    // Integrate adaptive Δθ until accumulated ~ L
    private static double integrateLookahead(double theta0, double L, double B, boolean cwVisual,
                                             double s, double dtMinVal, double dtMaxVal) {
        double acc = 0.0;
        double theta = theta0;
        int guard = 0;
        while (acc < L && guard++ < 4096) {
            double tau = cwVisual ? +theta : -theta;
            double r = Math.max(0.0, B * tau);
            double dthetaMag = s / Math.max(1e-6, Math.sqrt(r * r + B * B));
            dthetaMag = clampAbs(dthetaMag, dtMinVal, dtMaxVal);
            double dtheta = cwVisual ? +dthetaMag : -dthetaMag;
            acc += s;
            theta += dtheta;
        }
        return theta;
    }

    // Nearest point around thetaCenter (sampling + ternary refinements)
    private double[] nearestOnCurve(double thetaCenter, double B,
                                    double xc, double zc, double qx, double qz, boolean cwVisual,
                                    double windowRad, int samples, int refine) {
        double bestTheta = thetaCenter;
        double bestD2 = Double.MAX_VALUE;

        for (int i = 0; i < samples; i++) {
            double t = thetaCenter + (((double) i) / (samples - 1) - 0.5) * 2.0 * windowRad;
            double d2 = dist2AtTheta(t, B, xc, zc, qx, qz, cwVisual);
            if (d2 < bestD2) { bestD2 = d2; bestTheta = t; }
        }

        double left = bestTheta - windowRad / 6.0;
        double right = bestTheta + windowRad / 6.0;
        for (int k = 0; k < refine; k++) {
            double m1 = left + (right - left) / 3.0;
            double m2 = right - (right - left) / 3.0;
            double d1 = dist2AtTheta(m1, B, xc, zc, qx, qz, cwVisual);
            double d2 = dist2AtTheta(m2, B, xc, zc, qx, qz, cwVisual);
            if (d1 < d2) { right = m2; bestTheta = m1; bestD2 = d1; }
            else { left = m1; bestTheta = m2; bestD2 = d2; }
        }
        return new double[] { bestTheta, Math.sqrt(bestD2) };
    }

    private static double dist2AtTheta(double t, double B,
                                       double xc, double zc, double qx, double qz, boolean cwVisual) {
        double[] p = posPolar(t, B, xc, zc, cwVisual);
        double dx = p[0] - qx, dz = p[1] - qz;
        return dx * dx + dz * dz;
    }

    // Advance "current" toward "target" without reversing the chosen visual direction.
    // visual CW => theta increases; visual CCW => theta decreases.
    private static double advanceMonotonic(double current, double target, double maxStep, boolean cwVisual) {
        if (cwVisual) {
            // theta must not decrease
            if (target < current) target = current;
            double adv = Math.min(target - current, maxStep);
            if (adv < 0) adv = 0;
            return current + adv;
        } else {
            // theta must not increase
            if (target > current) target = current;
            double adv = Math.min(current - target, maxStep);
            if (adv < 0) adv = 0;
            return current - adv;
        }
    }
}
