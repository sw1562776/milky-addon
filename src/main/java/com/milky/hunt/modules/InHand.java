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

    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    private final Setting<Item> mainHandItem = sgGeneral.add(new ItemSetting.Builder()
        .name("main-hand-item")
        .description("Item to keep in your main hand.")
        .defaultValue(net.minecraft.item.Items.DIAMOND_SWORD)
        .build()
    );

    private final Setting<Item> offHandItem = sgGeneral.add(new ItemSetting.Builder()
        .name("off-hand-item")
        .description("Item to keep in your off hand.")
        .defaultValue(net.minecraft.item.Items.SHIELD)
        .build()
    );

    private final Setting<Integer> delay = sgGeneral.add(new IntSetting.Builder()
        .name("delay")
        .description("The ticks between slot movements.")
        .defaultValue(0)
        .min(0)
        .build()
    );

    private int ticks;

    public InHand() {
        super(Addon.CATEGORY, "InHand", "Automatically equips selected items in mainhand and offhand.");
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    private void onTick(TickEvent.Pre event) {
        if (mc.world == null || mc.player == null) return;

        if (ticks < delay.get()) {
            ticks++;
            return;
        }
        ticks = 0;

        if (mc.player.getMainHandStack().getItem() != mainHandItem.get()) {
            FindItemResult mainItem = InvUtils.find(mainHandItem.get());
            if (mainItem.found()) {
                InvUtils.move().from(mainItem.slot()).toHotbar(mc.player.getInventory().selectedSlot);
            }
        }

        if (mc.player.getOffHandStack().getItem() != offHandItem.get()) {
            FindItemResult offItem = InvUtils.find(offHandItem.get());
            if (offItem.found()) {
                InvUtils.move().from(offItem.slot()).toOffhand();
            }
        }
    }
}
