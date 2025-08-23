package com.milky.hunt.modules;

import com.milky.hunt.Addon;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.systems.modules.Modules;
import meteordevelopment.orbit.EventHandler;
import meteordevelopment.orbit.EventPriority;

import java.util.*;
import java.util.concurrent.TimeUnit;

public class Timeline extends Module {
    private enum StepType { WAIT_MODULE, RUN_GROUP_DURATION }

    private static class Step {
        StepType type;
        List<String> modules;
        long durationMs;
        Step(StepType t, List<String> ms, long d) { type = t; modules = ms; durationMs = d; }
    }

    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    private final Setting<String> script = sgGeneral.add(new StringSetting.Builder()
        .name("timeline-script")
        .description("Example: ChestRestock ; GotoMultiPoints, RightClickEntity, 30 mins")
        .defaultValue("ChestRestock ; GotoMultiPoints, RightClickEntity, 30 mins")
        .build()
    );

    private final Setting<Boolean> loop = sgGeneral.add(new BoolSetting.Builder()
        .name("loop")
        .description("Loop the sequence.")
        .defaultValue(false)
        .build()
    );

    private final Setting<Boolean> strictNames = sgGeneral.add(new BoolSetting.Builder()
        .name("strict-names")
        .description("Fail on unknown module names at parse-time.")
        .defaultValue(false)
        .build()
    );

    private static final Set<String> ONLY_UNTIMED = new HashSet<>(Arrays.asList(
        "chestdeposit", "chestrestock", "landing", "pitstop", "pullup", "quickcommand"
    ));
    private static final Set<String> ONLY_TIMED = new HashSet<>(Arrays.asList(
        "boostedbounce", "cruise", "gotomultipoints", "inhand", "rightclickentity", "spiralflight"
    ));
    private static final Set<String> ALLOWED_PARALLEL = new HashSet<>(Arrays.asList(
        "boostedbounce", "cruise", "gotomultipoints", "inhand", "rightclickentity", "spiralflight", "waypointqueue"
    ));

    private final List<Step> steps = new ArrayList<>();
    private int idx = -1;
    private long stepEndAt = 0L;
    private final Set<String> startedThisStep = new HashSet<>();
    private final Map<String, Boolean> wasActiveBefore = new HashMap<>();
    private String parseError = null;
    private boolean armedWait = false;
    private long loopStartAt = 0L;

    public Timeline() {
        super(
            Addon.CATEGORY,
            "Timeline",
            "Time-based sequencer that runs other modules via a one-line script.\n" +
            "Use ';' to chain steps in order; within a step, ',' runs modules in parallel and the last token must be a duration (30s/2m/1h).\n" +
            "A single module with no duration means: start it (if needed) and wait until it deactivates, then continue.\n" +
            "Parallel steps may include only RightClickEntity, InHand, GotoMultiPoints; RightClickEntity and InHand require a duration; ChestDeposit, ChestRestock, QuickCommand must be untimed; GotoMultiPoints supports both.\n" +
            "Enable 'loop' to restart from the first step when finished; the HUD shows the current step and 'loop 15h3m1s' elapsed in this round."
        );
    }

    @Override
    public void onActivate() {
        steps.clear();
        idx = -1;
        stepEndAt = 0L;
        startedThisStep.clear();
        wasActiveBefore.clear();
        parseError = null;
        armedWait = false;
        loopStartAt = System.currentTimeMillis();

        if (!parse(script.get())) { toggle(); return; }
        nextStep();
    }

    @Override
    public void onDeactivate() {
        stopStep(true);
        startedThisStep.clear();
        wasActiveBefore.clear();
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    private void onTick(TickEvent.Pre e) {
        if (!isActive()) return;
        if (parseError != null) { toggle(); return; }
        if (idx < 0 || idx >= steps.size()) { toggle(); return; }

        Step s = steps.get(idx);
        switch (s.type) {
            case WAIT_MODULE -> {
                String name = s.modules.get(0);
                Module m = findModule(name);
                if (m == null) { failUnknown(name); return; }
                if (!armedWait) {
                    wasActiveBefore.put(name, m.isActive());
                    if (!m.isActive()) { m.toggle(); startedThisStep.add(name); }
                    armedWait = true;
                } else {
                    if (!m.isActive()) nextStep();
                }
            }
            case RUN_GROUP_DURATION -> {
                long now = System.currentTimeMillis();
                if (stepEndAt == 0L) {
                    for (String name : s.modules) {
                        Module m = findModule(name);
                        if (m == null) { failUnknown(name); return; }
                        wasActiveBefore.put(name, m.isActive());
                        if (!m.isActive()) { m.toggle(); startedThisStep.add(name); }
                    }
                    stepEndAt = now + s.durationMs;
                } else if (now >= stepEndAt) {
                    stopStep(false);
                    nextStep();
                }
            }
        }
    }

    private void nextStep() {
        idx++;
        armedWait = false;
        stepEndAt = 0L;
        startedThisStep.clear();

        if (idx >= steps.size()) {
            if (loop.get()) {
                idx = 0;
                loopStartAt = System.currentTimeMillis();
            } else {
                toggle();
            }
        }
    }

    private void stopStep(boolean onDeactivate) {
        if (idx < 0 || idx >= steps.size()) return;
        Step s = steps.get(idx);
        if (s.type == StepType.RUN_GROUP_DURATION || onDeactivate) {
            for (String name : startedThisStep) {
                Module m = findModule(name);
                if (m != null) {
                    boolean before = wasActiveBefore.getOrDefault(name, false);
                    if (m.isActive() && !before) m.toggle();
                }
            }
        }
    }

    private boolean parse(String text) {
        parseError = null;
        steps.clear();

        String[] parts = Arrays.stream(text.split(";"))
            .map(String::trim).filter(p -> !p.isEmpty()).toArray(String[]::new);
        if (parts.length == 0) { parseError = "Script is empty."; return false; }

        for (String part : parts) {
            String[] tokens = Arrays.stream(part.split(","))
                .map(String::trim).filter(t -> !t.isEmpty()).toArray(String[]::new);
            if (tokens.length == 0) continue;

            if (tokens.length == 1) {
                String mod = tokens[0];

                if (isOnlyTimed(mod)) {
                    parseError = "This step contains a module that requires a duration (e.g., add \", 30s\").";
                    return false;
                }
                if (strictNames.get() && findModule(mod) == null) {
                    parseError = "The script references an unknown or unloaded module.";
                    return false;
                }

                steps.add(new Step(StepType.WAIT_MODULE, List.of(mod), 0));
                continue;
            }

            String last = tokens[tokens.length - 1];
            Long dur = parseDurationMs(last);
            if (dur == null) {
                parseError = "Each comma-separated group must end with a duration token (e.g., 30s/2m/1h).";
                return false;
            }

            List<String> mods = new ArrayList<>();
            for (int i = 0; i < tokens.length - 1; i++) {
                String m = tokens[i];

                if (isOnlyUntimed(m)) {
                    parseError = "This group contains a module that cannot be used with a duration; put it in its own step without time.";
                    return false;
                }
                if (!isParallelAllowed(m)) {
                    parseError = "This group includes a module that is not allowed in parallel steps; keep only parallel-safe modules or split into separate steps.";
                    return false;
                }
                if (strictNames.get() && findModule(m) == null) {
                    parseError = "The script references an unknown or unloaded module.";
                    return false;
                }

                mods.add(m);
            }

            steps.add(new Step(StepType.RUN_GROUP_DURATION, mods, dur));
        }
        return true;
    }

    private Long parseDurationMs(String raw) {
        String s = raw.trim().toLowerCase(Locale.ROOT);
        if (s.matches("\\d+[smh]")) {
            long n = Long.parseLong(s.substring(0, s.length() - 1));
            char u = s.charAt(s.length() - 1);
            if (u == 's') return TimeUnit.SECONDS.toMillis(n);
            if (u == 'm') return TimeUnit.MINUTES.toMillis(n);
            if (u == 'h') return TimeUnit.HOURS.toMillis(n);
        }
        String[] sp = s.split("\\s+");
        if (sp.length == 2 && sp[0].matches("\\d+")) {
            long n = Long.parseLong(sp[0]);
            String u = sp[1];
            if (u.startsWith("s")) return TimeUnit.SECONDS.toMillis(n);
            if (u.startsWith("m")) return TimeUnit.MINUTES.toMillis(n);
            if (u.startsWith("h")) return TimeUnit.HOURS.toMillis(n);
        }
        if (sp.length == 1 && sp[0].matches("\\d+")) {
            long n = Long.parseLong(sp[0]);
            return TimeUnit.SECONDS.toMillis(n);
        }
        return null;
    }

    private static String key(String name) { return name.toLowerCase(Locale.ROOT); }
    private static boolean isOnlyUntimed(String name) { return ONLY_UNTIMED.contains(key(name)); }
    private static boolean isOnlyTimed(String name) { return ONLY_TIMED.contains(key(name)); }
    private static boolean isParallelAllowed(String name) { return ALLOWED_PARALLEL.contains(key(name)); }

    private Module findModule(String name) {
        return Modules.get().get(name);
    }

    private void failUnknown(String name) {
        parseError = "The script references an unknown or unloaded module.";
        stopStep(false);
    }

    private static String formatCompact(long ms) {
        if (ms < 0) ms = 0;
        long s = ms / 1000;
        long h = s / 3600;
        long m = (s % 3600) / 60;
        long sec = s % 60;

        StringBuilder sb = new StringBuilder();
        if (h > 0) sb.append(h).append("h");
        if (m > 0 || h > 0) sb.append(m).append("m");
        sb.append(sec).append("s");
        return sb.toString();
    }

    @Override
    public String getInfoString() {
        if (parseError != null) return "ERR";
        if (idx < 0 || idx >= steps.size()) return null;

        String loopStr = "loop " + formatCompact(System.currentTimeMillis() - loopStartAt);
        Step s = steps.get(idx);

        if (s.type == StepType.WAIT_MODULE) {
            return (idx + 1) + "/" + steps.size() + " " + s.modules.get(0) + " | " + loopStr;
        } else {
            long leftMs = Math.max(0L, stepEndAt == 0L ? 0L : (stepEndAt - System.currentTimeMillis()));
            String stepLeft = formatCompact(leftMs);
            String mods = String.join("+", s.modules);
            return (idx + 1) + "/" + steps.size() + " " + mods + " " + stepLeft + " | " + loopStr;
        }
    }
}
