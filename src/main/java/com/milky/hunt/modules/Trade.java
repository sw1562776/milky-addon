package com.milky.hunt.modules;

import com.milky.hunt.Addon;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import meteordevelopment.meteorclient.events.packets.PacketEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.EnumSetting;
import meteordevelopment.meteorclient.settings.IntSetting;
import meteordevelopment.meteorclient.settings.ItemSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.settings.StringSetting;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.enchantment.Enchantment;
import net.minecraft.enchantment.EnchantmentHelper;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.network.packet.c2s.play.ClickSlotC2SPacket;
import net.minecraft.network.packet.c2s.play.CloseHandledScreenC2SPacket;
import net.minecraft.network.packet.c2s.play.SelectMerchantTradeC2SPacket;
import net.minecraft.network.packet.s2c.play.SetTradeOffersS2CPacket;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.screen.MerchantScreenHandler;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.util.Identifier;
import net.minecraft.village.TradeOffer;
import net.minecraft.village.TradeOfferList;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class Trade extends Module {
    public enum Mode { Buy, Sell }

    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    private final Setting<Mode> mode = sgGeneral.add(new EnumSetting.Builder<Mode>()
        .name("mode")
        .description("Buy: pay emeralds for result. Sell: give items to get emeralds.")
        .defaultValue(Mode.Buy)
        .build()
    );

    private final Setting<Item> buyItem = sgGeneral.add(new ItemSetting.Builder()
        .name("buy-item")
        .description("Result item to obtain in Buy mode (e.g., BOOKSHELF or ENCHANTED_BOOK).")
        .defaultValue(Items.BOOKSHELF)
        .visible(() -> mode.get() == Mode.Buy)
        .build()
    );

    private final Setting<Item> sellCostItem = sgGeneral.add(new ItemSetting.Builder()
        .name("sell-cost-item")
        .description("Cost item to give in Sell mode (result must be EMERALD).")
        .defaultValue(Items.STRING)
        .visible(() -> mode.get() == Mode.Sell)
        .build()
    );

    private final Setting<String> enchTargetsLine = sgGeneral.add(new StringSetting.Builder()
        .name("enchanted-targets")
        .description("For ENCHANTED_BOOK in Buy mode. Semicolon-separated entries like: minecraft:silk_touch:1; minecraft:sharpness:5")
        .defaultValue("minecraft:silk_touch:1; minecraft:sharpness:5")
        .visible(() -> mode.get() == Mode.Buy && buyItem.get() == Items.ENCHANTED_BOOK)
        .build()
    );

    private final Setting<Integer> buyMaxPrice = sgGeneral.add(new IntSetting.Builder()
        .name("buy-max-price")
        .description("Max emeralds to spend in Buy mode (0 = no limit).")
        .defaultValue(0)
        .min(0)
        .sliderMax(64)
        .visible(() -> mode.get() == Mode.Buy)
        .build()
    );

    private final Setting<Integer> sellMinPrice = sgGeneral.add(new IntSetting.Builder()
        .name("sell-min-price")
        .description("Min emeralds to receive in Sell mode (0 = no limit).")
        .defaultValue(0)
        .min(0)
        .sliderMax(64)
        .visible(() -> mode.get() == Mode.Sell)
        .build()
    );

    private final Setting<Boolean> closeAfter = sgGeneral.add(new BoolSetting.Builder()
        .name("close-after")
        .description("Close the merchant screen after trading.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Integer> selectDelayTicks = sgGeneral.add(new IntSetting.Builder()
        .name("select-delay-ticks")
        .description("Ticks to wait after selecting the recipe before clicking the result (helps avoid UI/server desync).")
        .defaultValue(3)
        .min(0)
        .sliderMax(20)
        .build()
    );

    private boolean haveOffers = false;
    private int screenSyncId = -1;
    private TradeOfferList offers = null;
    private int selectedOfferIdx = -1;
    private enum Step { Idle, SelectOffer, DelayThenClick, ClickResult, CloseAfterDelay }
    private Step step = Step.Idle;
    private int delayTicks = 0;

    public Trade() {
        super(Addon.MilkyModCategory, "Trade", "Auto trades with villagers.");
    }

    @Override
    public void onActivate() {
        resetSession();
    }

    @Override
    public void onDeactivate() {
        resetSession();
    }

    private void resetSession() {
        haveOffers = false;
        screenSyncId = -1;
        offers = null;
        selectedOfferIdx = -1;
        step = Step.Idle;
        delayTicks = 0;
    }

    private void closeTradeScreen(MerchantScreenHandler handler) {
        if (mc.player != null) mc.player.closeHandledScreen();
        if (mc.getNetworkHandler() != null) mc.getNetworkHandler().sendPacket(new CloseHandledScreenC2SPacket(handler.syncId));
        resetSession();
    }

    @EventHandler
    private void onPacketReceive(PacketEvent.Receive event) {
        if (!(event.packet instanceof SetTradeOffersS2CPacket pkt)) return;
        if (!(mc.player != null && mc.player.currentScreenHandler instanceof MerchantScreenHandler handler)) return;
        if (pkt.getSyncId() != handler.syncId) return;
        applyOffers(handler, pkt.getOffers(), "packet");
    }

    private void tryPullOffersFromHandler(MerchantScreenHandler handler) {
        TradeOfferList list = handler.getRecipes();
        if (list != null && !list.isEmpty()) applyOffers(handler, list, "handler:getRecipes");
    }

    private void applyOffers(MerchantScreenHandler handler, TradeOfferList list, String source) {
        offers = list;
        screenSyncId = handler.syncId;
        haveOffers = offers != null && !offers.isEmpty();
        selectedOfferIdx = -1;
        step = Step.Idle;
        if (!haveOffers) return;
        for (int i = 0; i < offers.size(); i++) {
            TradeOffer o = offers.get(i);
            if (o.isDisabled() || o.getUses() >= o.getMaxUses()) continue;
            if (matchesByMode(o)) { selectedOfferIdx = i; break; }
        }
        if (selectedOfferIdx >= 0) {
            step = Step.SelectOffer;
        } else {
            if (closeAfter.get()) {
                delayTicks = Math.max(0, selectDelayTicks.get());
                step = Step.CloseAfterDelay;
            } else {
                resetSession();
            }
        }
    }

    private boolean matchesByMode(TradeOffer o) {
        if (mode.get() == Mode.Buy) {
            Item wantResult = buyItem.get();
            if (wantResult == null) return false;
            Item resultItem = o.getSellItem().getItem();
            if (resultItem != wantResult) return false;
            if (resultItem == Items.ENCHANTED_BOOK) {
                String line = enchTargetsLine.get();
                if (!(line == null || line.isBlank())) {
                    List<EnchTarget> targets = parseEnchTargets(line);
                    if (!targets.isEmpty() && !enchantedBookMatchesExactly(o.getSellItem(), targets)) return false;
                }
            }
            int emeraldCost = emeraldCostOfOffer(o);
            int maxP = buyMaxPrice.get();
            if (maxP > 0 && emeraldCost > maxP) return false;
            return true;
        } else {
            if (o.getSellItem().getItem() != Items.EMERALD) return false;
            Item targetCost = sellCostItem.get();
            if (targetCost == null) return false;
            Item a = o.getOriginalFirstBuyItem().getItem();
            Item b = o.getSecondBuyItem().map(t -> t.item().value()).orElse(null);
            if (!(a == targetCost || (b != null && b == targetCost))) return false;
            int emeraldOut = o.getSellItem().getCount();
            int minP = sellMinPrice.get();
            if (minP > 0 && emeraldOut < minP) return false;
            return true;
        }
    }

    private int emeraldCostOfOffer(TradeOffer o) {
        int cost = 0;
        ItemStack displayed = o.getDisplayedFirstBuyItem();
        if (displayed.getItem() == Items.EMERALD) {
            cost += Math.max(1, displayed.getCount());
        }
        var sb = o.getSecondBuyItem();
        if (sb.isPresent() && sb.get().item().value() == Items.EMERALD) {
            cost += sb.get().count();
        }
        return cost;
    }

    private static class EnchTarget {
        final Identifier id;
        final int level;
        EnchTarget(Identifier id, int level) { this.id = id; this.level = level; }
    }

    private List<EnchTarget> parseEnchTargets(String line) {
        List<EnchTarget> out = new ArrayList<>();
        String[] parts = line.split(";");
        for (String raw : parts) {
            if (raw == null) continue;
            String s = raw.trim().toLowerCase(Locale.ROOT);
            if (s.isEmpty()) continue;
            String ns = "minecraft";
            String name;
            int lv;
            String[] fields = s.split(":");
            if (fields.length == 2) {
                name = fields[0];
                lv = parseIntSafe(fields[1], -1);
            } else if (fields.length == 3) {
                ns = fields[0];
                name = fields[1];
                lv = parseIntSafe(fields[2], -1);
            } else {
                continue;
            }
            if (lv <= 0) continue;
            Identifier id = Identifier.of(ns, name);
            out.add(new EnchTarget(id, lv));
        }
        return out;
    }

    private boolean enchantedBookMatchesExactly(ItemStack book, List<EnchTarget> targets) {
        if (book.get(DataComponentTypes.STORED_ENCHANTMENTS) == null) return false;
        var enchMap = EnchantmentHelper.getEnchantments(book);
        for (var entry : enchMap.getEnchantmentEntries()) {
            RegistryEntry<Enchantment> key = entry.getKey();
            int level = entry.getIntValue();
            if (mc.world == null) continue;
            var optReg = mc.world.getRegistryManager().getOptional(RegistryKeys.ENCHANTMENT);
            if (optReg.isEmpty()) continue;
            Identifier onBook = optReg.get().getId(key.value());
            if (onBook == null) continue;
            for (EnchTarget t : targets) {
                if (t.id.equals(onBook) && level == t.level) return true;
            }
        }
        return false;
    }

    private int parseIntSafe(String s, int def) {
        try { return Integer.parseInt(s); } catch (Exception ignored) { return def; }
    }

    @EventHandler
    private void onTick(TickEvent.Post event) {
        if (mc.player == null) return;
        if (!(mc.player.currentScreenHandler instanceof MerchantScreenHandler handler)) {
            if (haveOffers) resetSession();
            return;
        }
        if (!haveOffers) {
            tryPullOffersFromHandler(handler);
            return;
        }
        if (handler.syncId != screenSyncId) { resetSession(); return; }

        if (step != Step.CloseAfterDelay) {
            if (selectedOfferIdx < 0 || selectedOfferIdx >= offers.size()) return;
        }

        switch (step) {
            case SelectOffer -> {
                mc.getNetworkHandler().sendPacket(new SelectMerchantTradeC2SPacket(selectedOfferIdx));
                delayTicks = Math.max(0, selectDelayTicks.get());
                step = Step.DelayThenClick;
            }
            case DelayThenClick -> {
                if (delayTicks-- <= 0) step = Step.ClickResult;
            }
            case ClickResult -> {
                int resultSlot = 2;
                int revision = handler.getRevision();
                var changedStacks = new Int2ObjectOpenHashMap<ItemStack>();
                mc.getNetworkHandler().sendPacket(new ClickSlotC2SPacket(
                    handler.syncId, revision, resultSlot, 0,
                    SlotActionType.QUICK_MOVE, ItemStack.EMPTY, changedStacks
                ));
                if (closeAfter.get()) {
                    if (mc.player != null) mc.player.closeHandledScreen();
                    mc.getNetworkHandler().sendPacket(new CloseHandledScreenC2SPacket(handler.syncId));
                }
                resetSession();
            }
            case CloseAfterDelay -> {
                if (delayTicks-- <= 0) {
                    if (closeAfter.get()) closeTradeScreen(handler);
                    else resetSession();
                }
            }
            case Idle -> {
                step = Step.SelectOffer;
            }
        }
    }
}
