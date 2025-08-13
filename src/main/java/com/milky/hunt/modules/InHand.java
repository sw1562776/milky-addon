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

public class InHand extends Module {
    public enum Mode {
        MainHand,
        OffHand,
        Both
    }

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

    private int mainTicks;
    private int offTicks;

    public InHand() {
        super(Addon.CATEGORY, "InHand", "Automatically equips selected items in mainhand and/or offhand.");
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    private void onTick(TickEvent.Pre event) {
        if (mc.world == null || mc.player == null) return;

        if (mode.get() == Mode.MainHand || mode.get() == Mode.Both) {
            if (mainTicks < mainHandDelay.get()) {
                mainTicks++;
            } else {
                mainTicks = 0;
                if (mc.player.getMainHandStack().getItem() != mainHandItem.get()) {
                    FindItemResult mainItem = InvUtils.find(mainHandItem.get());
                    if (mainItem.found()) {
                        InvUtils.move().from(mainItem.slot()).toHotbar(mc.player.getInventory().selectedSlot);
                    }
                }
            }
        }

        if (mode.get() == Mode.OffHand || mode.get() == Mode.Both) {
            if (offTicks < offHandDelay.get()) {
                offTicks++;
            } else {
                offTicks = 0;
                if (mc.player.getOffHandStack().getItem() != offHandItem.get()) {
                    FindItemResult offItem = InvUtils.find(offHandItem.get());
                    if (offItem.found()) {
                        InvUtils.move().from(offItem.slot()).toOffhand();
                    }
                }
            }
        }
    }
}
