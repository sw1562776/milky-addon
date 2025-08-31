package com.milky.hunt.modules;

import com.milky.hunt.Addon;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.player.FindItemResult;
import meteordevelopment.meteorclient.utils.player.InvUtils;
import meteordevelopment.orbit.EventHandler;
import meteordevelopment.orbit.EventPriority;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.screen.slot.SlotActionType;

public class InHand extends Module {
    public enum Mode { MainHand, OffHand, Both }
    public enum DurabilityFilter { Off, Above, Below }

    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    private final Setting<Mode> mode = sgGeneral.add(new EnumSetting.Builder<Mode>()
        .name("mode")
        .description("Which hand(s) to manage.")
        .defaultValue(Mode.Both)
        .build()
    );

    private final Setting<Item> mainHandItem = sgGeneral.add(new ItemSetting.Builder()
        .name("main-hand-item")
        .description("Item to keep in your main hand.")
        .defaultValue(net.minecraft.item.Items.COMMAND_BLOCK)
        .visible(() -> mode.get() == Mode.MainHand || mode.get() == Mode.Both)
        .build()
    );

    private final Setting<Integer> mainHandDelay = sgGeneral.add(new IntSetting.Builder()
        .name("main-hand-delay")
        .description("Ticks between slot movements for the main hand.")
        .defaultValue(0)
        .min(0)
        .visible(() -> mode.get() == Mode.MainHand || mode.get() == Mode.Both)
        .build()
    );

    private final Setting<DurabilityFilter> mainHandDurMode = sgGeneral.add(new EnumSetting.Builder<DurabilityFilter>()
        .name("main-hand-durability-filter")
        .description("Filter by remaining durability for main-hand item.")
        .defaultValue(DurabilityFilter.Off)
        .visible(() -> mode.get() == Mode.MainHand || mode.get() == Mode.Both)
        .build()
    );

    private final Setting<Integer> mainHandDurThreshold = sgGeneral.add(new IntSetting.Builder()
        .name("main-hand-durability-%")
        .description("Threshold as remaining durability percent (0–100). (Above = strictly >, Below = strictly <)")
        .defaultValue(50)
        .min(0)
        .max(100)
        .sliderMax(100)
        .visible(() -> (mode.get() == Mode.MainHand || mode.get() == Mode.Both) && mainHandDurMode.get() != DurabilityFilter.Off)
        .build()
    );

    private final Setting<Item> offHandItem = sgGeneral.add(new ItemSetting.Builder()
        .name("off-hand-item")
        .description("Item to keep in your off hand.")
        .defaultValue(net.minecraft.item.Items.BARRIER)
        .visible(() -> mode.get() == Mode.OffHand || mode.get() == Mode.Both)
        .build()
    );

    private final Setting<Integer> offHandDelay = sgGeneral.add(new IntSetting.Builder()
        .name("off-hand-delay")
        .description("Ticks between slot movements for the off hand.")
        .defaultValue(0)
        .min(0)
        .visible(() -> mode.get() == Mode.OffHand || mode.get() == Mode.Both)
        .build()
    );

    private final Setting<DurabilityFilter> offHandDurMode = sgGeneral.add(new EnumSetting.Builder<DurabilityFilter>()
        .name("off-hand-durability-filter")
        .description("Filter by remaining durability for off-hand item.")
        .defaultValue(DurabilityFilter.Off)
        .visible(() -> mode.get() == Mode.OffHand || mode.get() == Mode.Both)
        .build()
    );

    private final Setting<Integer> offHandDurThreshold = sgGeneral.add(new IntSetting.Builder()
        .name("off-hand-durability-%")
        .description("Threshold as remaining durability percent (0–100). (Above = strictly >, Below = strictly <)")
        .defaultValue(50)
        .min(0)
        .max(100)
        .sliderMax(100)
        .visible(() -> (mode.get() == Mode.OffHand || mode.get() == Mode.Both) && offHandDurMode.get() != DurabilityFilter.Off)
        .build()
    );

    private int mainTicks;
    private int offTicks;

    public InHand() {
        super(Addon.CATEGORY, "InHand", "Automatically equips selected items in mainhand and/or offhand, with optional durability filters.");
    }

    private static boolean passesDurability(ItemStack stack, DurabilityFilter filter, int thresholdPercent) {
        int max = stack.getMaxDamage();
        if (max <= 0) return true;
        int damage = stack.getDamage();
        int remaining = Math.max(0, max - damage);
        int remainingPct = (int) Math.round(remaining * 100.0 / max);
        return switch (filter) {
            case Off   -> true;
            case Above -> remainingPct > thresholdPercent;
            case Below -> remainingPct < thresholdPercent;
        };
    }

    private static boolean isDesired(ItemStack stack, Item target, DurabilityFilter filter, int thresholdPercent) {
        return stack.getItem() == target && passesDurability(stack, filter, thresholdPercent);
    }

    private static FindItemResult findWithDurability(Item target, DurabilityFilter filter, int thresholdPercent) {
        return InvUtils.find(s -> s != null && !s.isEmpty() && isDesired(s, target, filter, thresholdPercent));
    }

    private void tryStashMainhandIfFilterNotSatisfied(Item target, DurabilityFilter filter, int threshold) {
        if (filter == DurabilityFilter.Off) return;
        ItemStack cur = mc.player.getMainHandStack();
        if (cur.isEmpty() || cur.getItem() != target) return;
        int max = cur.getMaxDamage();
        if (max <= 0) return;
        if (passesDurability(cur, filter, threshold)) return;
        FindItemResult candidate = findWithDurability(target, filter, threshold);
        if (candidate.found()) return;
        FindItemResult empty = InvUtils.findEmpty();
        if (empty.found()) {
            InvUtils.move().fromHotbar(mc.player.getInventory().selectedSlot).to(empty.slot());
        }
    }

    private void tryStashOffhandIfFilterNotSatisfied(Item target, DurabilityFilter filter, int threshold) {
        if (filter == DurabilityFilter.Off) return;
        ItemStack cur = mc.player.getOffHandStack();
        if (cur.isEmpty() || cur.getItem() != target) return;
        int max = cur.getMaxDamage();
        if (max <= 0) return;
        if (passesDurability(cur, filter, threshold)) return;
        FindItemResult candidate = findWithDurability(target, filter, threshold);
        if (candidate.found()) return;
        FindItemResult empty = InvUtils.findEmpty();
        if (empty.found()) {
            InvUtils.move().fromOffhand().to(empty.slot());
        }
    }

    @EventHandler
    private void onTickMainHand(TickEvent.Pre event) {
        if (!isActive() || mc.player == null) return;

        if (mode.get() == Mode.MainHand || mode.get() == Mode.Both) {
            if (mainTicks < mainHandDelay.get()) {
                mainTicks++;
            } else {
                mainTicks = 0;

                Item target = mainHandItem.get();
                DurabilityFilter filter = mainHandDurMode.get();
                int threshold = mainHandDurThreshold.get();

                ItemStack current = mc.player.getMainHandStack();
                boolean currentOk = isDesired(current, target, filter, threshold);

                if (!currentOk) {
                    FindItemResult mainItem = findWithDurability(target, filter, threshold);
                    if (mainItem.found()) {
                        bringToSelectedViaSwap(mainItem);
                    } else {
                        tryStashMainhandIfFilterNotSatisfied(target, filter, threshold);
                    }
                }
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    private void onTickOffHand(TickEvent.Pre event) {
        if (!isActive() || mc.player == null) return;

        if (mode.get() == Mode.OffHand || mode.get() == Mode.Both) {
            if (offTicks < offHandDelay.get()) {
                offTicks++;
            } else {
                offTicks = 0;

                Item target = offHandItem.get();
                DurabilityFilter filter = offHandDurMode.get();
                int threshold = offHandDurThreshold.get();

                ItemStack current = mc.player.getOffHandStack();
                boolean currentOk = isDesired(current, target, filter, threshold);

                if (!currentOk) {
                    FindItemResult offItem = findWithDurability(target, filter, threshold);
                    if (offItem.found()) {
                        InvUtils.move().from(offItem.slot()).toOffhand();
                    } else {
                        tryStashOffhandIfFilterNotSatisfied(target, filter, threshold);
                    }
                }
            }
        }
    }

    @Override
    public void onDeactivate() {
        mainTicks = 0;
        offTicks = 0;
    }

    @Override
    public String getInfoString() {
        StringBuilder handInfoBuilder = new StringBuilder();

        if (mode.get() == Mode.MainHand || mode.get() == Mode.Both) {
            FindItemResult main = InvUtils.find(mainHandItem.get());
            handInfoBuilder.append(mainHandItem.get().getName().getString())
                .append("*")
                .append(main.count());
        }

        if (mode.get() == Mode.OffHand || mode.get() == Mode.Both) {
            if (handInfoBuilder.length() > 0) handInfoBuilder.append(" ");
            FindItemResult off = InvUtils.find(offHandItem.get());
            handInfoBuilder.append(offHandItem.get().getName().getString())
                .append("*")
                .append(off.count());
        }

        return handInfoBuilder.toString();
    }

    private boolean bringToSelectedViaSwap(FindItemResult it) {
        if (!it.found()) return false;

        int selected = mc.player.getInventory().selectedSlot;
        if (it.isHotbar()) {
            InvUtils.swap(it.slot(), true);
            return true;
        }

        ScreenHandler h = mc.player.playerScreenHandler;
        int selectedContainerSlot = 36 + selected;

        try {
            mc.interactionManager.clickSlot(h.syncId, it.slot(), 0, SlotActionType.PICKUP, mc.player);
            mc.interactionManager.clickSlot(h.syncId, selectedContainerSlot, 0, SlotActionType.PICKUP, mc.player);
            mc.interactionManager.clickSlot(h.syncId, it.slot(), 0, SlotActionType.PICKUP, mc.player);
            return true;
        } catch (Exception ex) {
            return false;
        }
    }
}
