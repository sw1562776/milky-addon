package com.milky.hunt.modules;

import com.milky.hunt.Addon;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.systems.modules.Modules;
import meteordevelopment.orbit.EventHandler;

import net.minecraft.block.Block;
import net.minecraft.item.Item;
import net.minecraft.registry.Registries;
import net.minecraft.util.Identifier;

import java.lang.reflect.Field;
import java.util.*;
import java.util.function.Predicate;

public class Magazine extends Module {
    private static final int MAX_SLOTS = 128;

    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgCommands = settings.createGroup("Commands");

    private final Setting<Integer> slots = sgGeneral.add(new IntSetting.Builder()
        .name("slots")
        .description("How many command slots to show (1..128).")
        .defaultValue(2)
        .min(1)
        .max(MAX_SLOTS)
        .build()
    );

    private final Setting<Integer> nextIndex = sgGeneral.add(new IntSetting.Builder()
        .name("next-index")
        .description("1-based index of the slot to execute on next activation. Will auto-advance after execution.")
        .defaultValue(1)
        .min(1)
        .max(MAX_SLOTS)
        .build()
    );

    private final Setting<Boolean> loop = sgGeneral.add(new BoolSetting.Builder()
        .name("loop")
        .description("After the last slot, wrap around to 1.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> strictNames = sgGeneral.add(new BoolSetting.Builder()
        .name("strict-names")
        .description("Fail the whole slot if a module name is unknown.")
        .defaultValue(false)
        .build()
    );

    private final Setting<Boolean> strictParameters = sgGeneral.add(new BoolSetting.Builder()
        .name("strict-parameters")
        .description("Fail the whole slot if any parameter key/value doesn't match a known Setting.")
        .defaultValue(false)
        .build()
    );

    private final Setting<String>[] commands;

    public Magazine() {
        super(Addon.MilkyWayCategory, "Magazine",
            "A parameter switcher queue. Each activation applies one command slot, advances next-index, and turns itself off.");

        @SuppressWarnings("unchecked")
        Setting<String>[] arr = new Setting[MAX_SLOTS];
        for (int i = 0; i < MAX_SLOTS; i++) {
            final int idx = i; // zero-based capture
            arr[i] = sgCommands.add(new StringSetting.Builder()
                .name("slot-" + (i + 1))
                .description("Command line for slot " + (idx + 1) + ". Format: Module: key -> value, key2 -> value2; Other: key3 -> value3")
                .defaultValue("")
                .visible(() -> (idx + 1) <= slots.get())
                .build());
        }
        commands = arr;
    }

    @Override
    public void onActivate() {
        int total = Math.max(1, Math.min(slots.get(), MAX_SLOTS));
        int idx1 = Math.max(1, Math.min(nextIndex.get(), total)); // 1-based

        String cmd = commands[idx1 - 1].get().trim();
        if (cmd.isEmpty()) {
            info("Slot " + idx1 + " is empty.");
            advanceAndExit(idx1, total);
            return;
        }

        boolean ok = applySlot(cmd);
        if (ok) info("Applied slot " + idx1 + ".");
        else    warning("Slot " + idx1 + " not fully applied (see log).");

        advanceAndExit(idx1, total);
    }

    private void advanceAndExit(int currentIdx1, int total) {
        int next;
        if (loop.get()) next = (currentIdx1 % total) + 1;
        else            next = Math.min(currentIdx1 + 1, total);
        nextIndex.set(next);
        toggle();
    }

    private static class KV {
        final String key;
        final String value;
        KV(String k, String v) { key = k; value = v; }
    }

    private static class ModuleCommand {
        final String moduleName;
        final List<KV> pairs;
        ModuleCommand(String mn, List<KV> ps) { moduleName = mn; pairs = ps; }
    }

    private List<ModuleCommand> parseSlot(String text) {
        List<ModuleCommand> out = new ArrayList<>();
        for (String section : splitKeepNonEmpty(text, ';')) {
            int colon = section.indexOf(':');
            if (colon <= 0) continue;
            String moduleName = section.substring(0, colon).trim();
            String rhs = section.substring(colon + 1).trim();
            if (moduleName.isEmpty() || rhs.isEmpty()) continue;

            List<KV> kvs = new ArrayList<>();
            for (String pair : splitKeepNonEmpty(rhs, ',')) {
                int arrow = pair.indexOf("->");
                if (arrow <= 0) continue;
                String key = pair.substring(0, arrow).trim();
                String val = pair.substring(arrow + 2).trim();
                if (!key.isEmpty() && !val.isEmpty()) kvs.add(new KV(key, val));
            }
            if (!kvs.isEmpty()) out.add(new ModuleCommand(moduleName, kvs));
        }
        return out;
    }

    private static List<String> splitKeepNonEmpty(String s, char sep) {
        String[] raw = s.split("\\Q" + sep + "\\E");
        List<String> list = new ArrayList<>(raw.length);
        for (String r : raw) {
            String t = r.trim();
            if (!t.isEmpty()) list.add(t);
        }
        return list;
    }


    private boolean applySlot(String slotText) {
        List<ModuleCommand> sections = parseSlot(slotText);
        if (sections.isEmpty()) {
            warning("No valid sections found in slot.");
            return false;
        }

        boolean allOk = true;
        for (ModuleCommand mc : sections) {
            Module target = Modules.get().get(mc.moduleName);
            if (target == null) {
                String msg = "Unknown module: " + mc.moduleName;
                if (strictNames.get()) {
                    error(msg + " (strict-names). Aborting slot.");
                    return false;
                } else {
                    warning(msg + " (skipped).");
                    allOk = false;
                    continue;
                }
            }

            Map<String, Setting<?>> settingMap = collectSettings(target);
            boolean sectionOk = applyPairsToModule(target, settingMap, mc.pairs);
            if (!sectionOk) allOk = false;
        }

        return allOk;
    }

    private boolean applyPairsToModule(Module module, Map<String, Setting<?>> settingMap, List<KV> pairs) {
        boolean allOk = true;

        Map<String, Setting<?>> byNorm = new HashMap<>();
        for (Setting<?> s : settingMap.values()) {
            String norm = normalizeKey(settingName(s));
            byNorm.put(norm, s);
        }

        for (KV kv : pairs) {
            String kNorm = normalizeKey(kv.key);
            Setting<?> setting = byNorm.get(kNorm);
            if (setting == null) {
                String msg = "Unknown setting for " + module.name + ": " + kv.key;
                if (strictParameters.get()) {
                    error(msg + " (strict-params).");
                    return false;
                } else {
                    warning(msg + " (skipped).");
                    allOk = false;
                    continue;
                }
            }

            boolean ok = trySetValue(setting, kv.value);
            if (!ok) {
                String msg = "Failed to set " + module.name + "." + settingName(setting) + " = " + kv.value;
                if (strictParameters.get()) {
                    error(msg + " (strict-params).");
                    return false;
                } else {
                    warning(msg + " (skipped).");
                    allOk = false;
                }
            }
        }

        return allOk;
    }

    private boolean trySetValue(Setting<?> setting, String raw) {
        try {
            if (setting instanceof BoolSetting bs) {
                Boolean v = parseBool(raw);
                if (v == null) return false;
                bs.set(v);
                return true;
            }
            if (setting instanceof IntSetting is) {
                Integer v = parseInt(raw);
                if (v == null) return false;
                try {
                    Field fMin = IntSetting.class.getDeclaredField("min");
                    Field fMax = IntSetting.class.getDeclaredField("max");
                    fMin.setAccessible(true); fMax.setAccessible(true);
                    int min = (int) fMin.get(is);
                    int max = (int) fMax.get(is);
                    is.set(clamp(v, min, max));
                } catch (Throwable ignored) {
                    is.set(v);
                }
                return true;
            }
            if (setting instanceof DoubleSetting ds) {
                Double v = parseDouble(raw);
                if (v == null) return false;
                try {
                    Field fMin = DoubleSetting.class.getDeclaredField("min");
                    Field fMax = DoubleSetting.class.getDeclaredField("max");
                    fMin.setAccessible(true); fMax.setAccessible(true);
                    double min = (double) fMin.get(ds);
                    double max = (double) fMax.get(ds);
                    ds.set(Math.max(min, Math.min(max, v)));
                } catch (Throwable ignored) {
                    ds.set(v);
                }
                return true;
            }
            if (setting instanceof EnumSetting<?> es) {
                Object cur = es.get();
                if (!(cur instanceof Enum<?> curEnum)) return false;
                @SuppressWarnings("unchecked")
                Class<? extends Enum<?>> enumCls = (Class<? extends Enum<?>>) curEnum.getDeclaringClass();
                Enum<?> parsed = parseEnumRaw(enumCls, raw);
                if (parsed == null) return false;
                @SuppressWarnings("unchecked")
                EnumSetting<Enum<?>> typed = (EnumSetting<Enum<?>>) (EnumSetting<?>) es;
                typed.set(parsed);
                return true;
            }
            if (setting instanceof StringSetting ss) {
                ss.set(unquote(raw));
                return true;
            }
            if (setting instanceof ItemSetting) {
                Item it = parseItem(raw);
                if (it == null) return false;
                @SuppressWarnings("unchecked")
                Setting<Item> s = (Setting<Item>) setting;
                s.set(it);
                return true;
            }
            if (setting.getClass().getSimpleName().equals("BlockSetting")) {
                Block b = parseBlock(raw);
                if (b == null) return false;
                @SuppressWarnings({"unchecked", "rawtypes"})
                Setting<Block> s = (Setting) setting;
                s.set(b);
                return true;
            }

            return false;
        } catch (Throwable t) {
            warning("Exception setting " + settingName(setting) + ": " + t.getMessage());
            return false;
        }
    }

    private static String unquote(String s) {
        if (s.length() >= 2) {
            char a = s.charAt(0), b = s.charAt(s.length() - 1);
            if ((a == '"' && b == '"') || (a == '\'' && b == '\'')) return s.substring(1, s.length() - 1);
        }
        return s;
    }

    private static Boolean parseBool(String s) {
        String v = s.trim().toLowerCase(Locale.ROOT);
        return switch (v) {
            case "true", "on", "yes", "y", "1" -> true;
            case "false", "off", "no", "n", "0" -> false;
            default -> null;
        };
    }

    private static Integer parseInt(String s) {
        try { return Integer.parseInt(s.trim()); } catch (Exception ignored) { return null; }
    }

    private static Double parseDouble(String s) {
        try { return Double.parseDouble(s.trim()); } catch (Exception ignored) { return null; }
    }

    private static <E extends Enum<E>> E parseEnumRaw(Class<? extends Enum<?>> enumRaw, String s) {
        @SuppressWarnings("unchecked")
        Class<E> enumClass = (Class<E>) enumRaw;
        String want = s.trim().replace(' ', '_').replace('-', '_').toUpperCase(Locale.ROOT);
        for (E e : enumClass.getEnumConstants()) {
            if (e.name().equalsIgnoreCase(want)) return e;
        }
        return null;
    }

    private static Item parseItem(String s) {
        String v = s.trim();
        int dot = v.lastIndexOf('.');
        if (dot >= 0) v = v.substring(dot + 1);
        v = v.toLowerCase(Locale.ROOT);

        Identifier id;
        if (v.contains(":")) {
            id = Identifier.tryParse(v);
            if (id == null) return null;
        } else {
            id = Identifier.of("minecraft", v);
        }
        try {
            return Registries.ITEM.get(id);
        } catch (Throwable t) {
            return null;
        }
    }

    private static Block parseBlock(String s) {
        String v = s.trim();
        int dot = v.lastIndexOf('.');
        if (dot >= 0) v = v.substring(dot + 1);
        v = v.toLowerCase(Locale.ROOT);

        Identifier id;
        if (v.contains(":")) {
            id = Identifier.tryParse(v);
            if (id == null) return null;
        } else {
            id = Identifier.of("minecraft", v);
        }
        try {
            return Registries.BLOCK.get(id);
        } catch (Throwable t) {
            return null;
        }
    }

    private Map<String, Setting<?>> collectSettings(Module m) {
        Map<String, Setting<?>> map = new LinkedHashMap<>();

        try {
            Field settingsField = Module.class.getDeclaredField("settings");
            settingsField.setAccessible(true);
            Object settingsObj = settingsField.get(m);
            if (settingsObj != null) {
                Field groupsField = settingsObj.getClass().getDeclaredField("groups");
                groupsField.setAccessible(true);
                Object groups = groupsField.get(settingsObj);
                if (groups instanceof Collection<?> col) {
                    for (Object g : col) collectFromGroup(g, map);
                }
            }
        } catch (Throwable ignored) {}

        if (map.isEmpty()) {
            forEachField(m.getClass(), f -> Setting.class.isAssignableFrom(f.getType()), f -> {
                try {
                    f.setAccessible(true);
                    Object val = f.get(m);
                    if (val instanceof Setting<?> s) {
                        map.putIfAbsent(settingName(s), s);
                    }
                } catch (Throwable ignored) {}
            });
        }
        return map;
    }

    private void collectFromGroup(Object groupObj, Map<String, Setting<?>> out) {
        if (groupObj == null) return;
        try {
            Field settingsField = groupObj.getClass().getDeclaredField("settings");
            settingsField.setAccessible(true);
            Object list = settingsField.get(groupObj);
            if (list instanceof Collection<?> col) {
                for (Object o : col) {
                    if (o instanceof Setting<?> s) out.putIfAbsent(settingName(s), s);
                }
            }
        } catch (Throwable ignored) {}
    }

    private static void forEachField(Class<?> cls, Predicate<Field> filter, java.util.function.Consumer<Field> action) {
        for (Class<?> c = cls; c != null && c != Object.class; c = c.getSuperclass()) {
            Field[] fs = c.getDeclaredFields();
            for (Field f : fs) if (filter.test(f)) action.accept(f);
        }
    }

    private static int clamp(int v, int min, int max) {
        if (v < min) return min;
        if (v > max) return max;
        return v;
    }

    private static String normalizeKey(String s) {
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            if (Character.isLetterOrDigit(ch)) sb.append(Character.toLowerCase(ch));
        }
        return sb.toString();
    }

    private static String settingName(Setting<?> s) {
        try {
            Field f = Setting.class.getDeclaredField("name");
            f.setAccessible(true);
            Object val = f.get(s);
            if (val instanceof String str) return str;
        } catch (Throwable ignored) {}
        return s.toString();
    }

    private void info(String msg) {
        try { super.info(msg); } catch (Throwable t) { System.out.println("[Magazine] " + msg); }
    }

    private void warning(String msg) {
        try { super.warning(msg); } catch (Throwable t) { System.out.println("[Magazine][WARN] " + msg); }
    }

    private void error(String msg) {
        try { super.error(msg); } catch (Throwable t) { System.err.println("[Magazine][ERR] " + msg); }
    }

    @EventHandler
    private void onTick(TickEvent.Pre e) {
    }
}
