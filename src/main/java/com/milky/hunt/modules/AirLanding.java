package com.milky.hunt.modules;

import com.milky.hunt.Addon;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.BlockSetting;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.DoubleSetting;
import meteordevelopment.meteorclient.settings.IntSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.settings.StringSetting;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.player.FindItemResult;
import meteordevelopment.meteorclient.utils.player.InvUtils;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.block.Block;
import net.minecraft.block.Blocks;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.item.BlockItem;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.network.packet.c2s.play.ClientCommandC2SPacket;
import net.minecraft.network.packet.c2s.play.PlayerActionC2SPacket;
import net.minecraft.network.packet.c2s.play.PlayerInteractBlockC2SPacket;
import net.minecraft.network.packet.c2s.play.PlayerInteractItemC2SPacket;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.util.Hand;
import net.minecraft.util.Identifier;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.Heightmap;
import net.minecraft.world.biome.Biome;

import java.util.*;
import java.util.stream.Collectors;

public class AirLanding extends Module {
    private final SettingGroup sgGeneral = settings.getDefaultGroup();


    private final Setting<Boolean> autoDisable = sgGeneral.add(new BoolSetting.Builder()
        .name("auto-disable")
        .description("Disable the module when placement is finished.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Integer> placeDelay = sgGeneral.add(new IntSetting.Builder()
        .name("place-delay-ticks")
        .description("Delay between placements in ticks.")
        .defaultValue(2)
        .min(0)
        .sliderRange(0, 10)
        .build()
    );

    // Biome whitelist (semicolon-separated)
    private final Setting<Boolean> biomeFilterEnabled = sgGeneral.add(new BoolSetting.Builder()
        .name("biome-filter-enabled")
        .description("Only operate when the landing biome is in the whitelist.")
        .defaultValue(true)
        .build()
    );

    private final Setting<String> biomeIds = sgGeneral.add(new StringSetting.Builder()
        .name("biome-whitelist")
        .description("Semicolon-separated biome IDs (e.g. minecraft:ocean;minecraft:plains).")
        .defaultValue(
    "minecraft:beach;" +
    "minecraft:birch_forest;" +
    "minecraft:cherry_grove;" +
    "minecraft:cold_ocean;" +
    "minecraft:deep_cold_ocean;" +
    "minecraft:deep_frozen_ocean;" +
    "minecraft:deep_lukewarm_ocean;" +
    "minecraft:deep_ocean;" +
    "minecraft:desert;" +
    "minecraft:flower_forest;" +
    "minecraft:forest;" +
    "minecraft:frozen_ocean;" +
    "minecraft:lukewarm_ocean;" +
    "minecraft:mangrove_swamp;" +
    "minecraft:mushroom_fields;" +
    "minecraft:ocean;" +
    "minecraft:plains;" +
    "minecraft:river;" +
    "minecraft:savanna;" +
    "minecraft:snowy_beach;" +
    "minecraft:snowy_plains;" +
    "minecraft:stony_shore;" +
    "minecraft:sunflower_plains;" +
    "minecraft:swamp;" +
    "minecraft:warm_ocean"
     )
        .build()
    );

    private final Setting<Integer> biomeScanRadius = sgGeneral.add(new IntSetting.Builder()
        .name("biome-scan-radius")
        .description("Radius (in blocks on X/Z) to sample biome at surface. 0=current column, 1≈3x3, 2≈5x5.")
        .defaultValue(1)
        .min(0)
        .sliderRange(0, 4)
        .build()
    );

    // Absolute-Y triggers
    private final Setting<Integer> reequipAtY = sgGeneral.add(new IntSetting.Builder()
        .name("reequip-elytra-y")
        .description("At or below this Y, equip the Elytra again (keep looking straight up).")
        .defaultValue(320)
        .min(-64)
        .sliderRange(-64, 400)
        .build()
    );

    private final Setting<Integer> landingBurnY = sgGeneral.add(new IntSetting.Builder()
        .name("landing-burn-y")
        .description("At or below this Y, start sending START_FALL_FLYING and must fire one rocket.")
        .defaultValue(200)
        .min(-64)
        .sliderRange(-64, 400)
        .build()
    );

    private final Setting<Integer> elytraOpenRetryTicks = sgGeneral.add(new IntSetting.Builder()
        .name("elytra-open-retry-ticks")
        .description("Retry interval (ticks) for START_FALL_FLYING while preparing landing burn.")
        .defaultValue(2)
        .min(1)
        .sliderRange(1, 20)
        .build()
    );

    private final Setting<Double> epsilon = sgGeneral.add(new DoubleSetting.Builder()
        .name("velocity-epsilon")
        .description("|vy| threshold to trigger (apex check).")
        .defaultValue(0.15)
        .min(0.0)
        .sliderRange(0.0, 0.5)
        .build()
    );

    private final Setting<Boolean> lockMainhand = sgGeneral.add(new BoolSetting.Builder()
        .name("lock-mainhand")
        .description("Lock the block in main hand during PLACE_READY / placement.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Block> block = sgGeneral.add(new BlockSetting.Builder()
        .name("block")
        .description("Block to place for the platform (InvertedY packet sequence).")
        .defaultValue(Blocks.OBSIDIAN)
        .build()
    );

    private final Setting<Boolean> allowAnyBlock = sgGeneral.add(new BoolSetting.Builder()
        .name("fallback-any-block")
        .description("If preferred block is missing, use any block in inventory.")
        .defaultValue(false)
        .build()
    );

    private enum Phase {
        SEEK_BIOME,
        DROP_FAST,
        REEQUIP_AT_Y,
        WAIT_BURN_Y,
        OPEN_AND_BURN,
        PLACE_READY
    }

    private Phase phase = Phase.SEEK_BIOME;

    private double lastVy = 0.0;
    private int cooldown = 0;

    private final Deque<BlockPos> queue = new ArrayDeque<>();
    private BlockPos basePos = null;
    private int delayTimer = 0;

    // Preferred block slot is chosen only when entering PLACE_READY
    private int preferredBlockSlot = -1;

    // Burn state
    private boolean burnFired = false;
    private int openRetryTimer = 0;
    private int lastRocketCount = -1;
    private int rocketConsumeWait = 0;
    private int rocketSlot = -1;
    private boolean rocketArmed = false;
    private boolean burnUseSent = false;

    public AirLanding() {
        super(Addon.CATEGORY, "AirLanding", "Biome-aware landing: free-fall, re-equip at Y, landing burn, then airplace-based platform placement.");
    }

    @Override
    public void onActivate() {
        phase = Phase.SEEK_BIOME;

        lastVy = 0.0;
        cooldown = 0;
        queue.clear();
        basePos = null;
        delayTimer = 0;

        preferredBlockSlot = -1;

        burnFired = false;
        openRetryTimer = 0;
        lastRocketCount = -1;
        rocketConsumeWait = 0;
        rocketSlot = -1;
        rocketArmed = false;
        burnUseSent = false;
    }

    @Override
    public String getInfoString() {
        if (mc == null || mc.player == null) return null;
        return String.format("state=%s vy=%.3f", phase.name(), mc.player.getVelocity().y);
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (mc == null) return;
        ClientPlayerEntity p = mc.player;
        if (p == null || mc.world == null) return;

        if (cooldown > 0) cooldown--;

        // Only lock the block in hand during PLACE_READY or while actively placing.
        if (lockMainhand.get() && preferredBlockSlot >= 0 && (phase == Phase.PLACE_READY || !queue.isEmpty())) {
            if (preferredBlockSlot < 9) {
                p.getInventory().selectedSlot = preferredBlockSlot;
            }
        }

        // Placement queue
        if (!queue.isEmpty()) {
            if (delayTimer > 0) {
                delayTimer--;
                return;
            }
            BlockPos next = queue.peekFirst();
            if (next != null) {
                if (placeOne(next)) {
                    queue.removeFirst();
                    delayTimer = placeDelay.get();
                    if (queue.isEmpty() && autoDisable.get()) {
                        toggle();
                    }
                } else {
                    queue.clear();
                    basePos = null;
                }
            }
            return;
        }

        switch (phase) {
            case SEEK_BIOME -> {
                if (!biomeFilterEnabled.get() || isAllowedBiomeHere()) {
                    phase = Phase.DROP_FAST;
                }
            }

            case DROP_FAST -> {
                forcePitchUp();
                unequipElytraToHotbar();
                if (p.getY() <= reequipAtY.get()) {
                    phase = Phase.REEQUIP_AT_Y;
                }
            }

            case REEQUIP_AT_Y -> {
                forcePitchUp();
                equipElytra();
                phase = Phase.WAIT_BURN_Y;
            }

            case WAIT_BURN_Y -> {
                forcePitchUp();
                if (p.getY() <= landingBurnY.get()) {
                    phase = Phase.OPEN_AND_BURN;
                    openRetryTimer = 0;
                    lastRocketCount = countRocketsInInventory();
                    rocketConsumeWait = 0;
                    rocketSlot = -1;
                    rocketArmed = false;
                    burnUseSent = false;
                }
            }

            case OPEN_AND_BURN -> {
                forcePitchUp();

                // keep sending START_FALL_FLYING on a paced interval
                if (openRetryTimer > 0) {
                    openRetryTimer--;
                } else {
                    p.networkHandler.sendPacket(new ClientCommandC2SPacket(
                        p, ClientCommandC2SPacket.Mode.START_FALL_FLYING
                    ));
                    openRetryTimer = Math.max(1, elytraOpenRetryTicks.get());
                }

                if (!burnFired) {
                    // Arm rocket once (choose a hotbar slot that is NOT preferredBlockSlot)
                    if (!rocketArmed) {
                        int hotbarIdx = findRocketInHotbar();
                        if (hotbarIdx < 0) {
                            int target = getFreeHotbarSlotExcluding(preferredBlockSlot);
                            if (moveRocketToHotbar(target)) hotbarIdx = target;
                        }
                        if (hotbarIdx >= 0) {
                            p.getInventory().selectedSlot = hotbarIdx;
                            rocketSlot = hotbarIdx;
                            rocketArmed = true;
                        } else {
                            // Wait for rockets to appear
                            return;
                        }
                    }

                    // Send exactly one use, then wait for consumption; if not consumed, retry
                    if (!burnUseSent) {
                        int seq = p.currentScreenHandler.getRevision() + 1;
                        p.networkHandler.sendPacket(new PlayerInteractItemC2SPacket(
                            Hand.MAIN_HAND, seq, p.getYaw(), p.getPitch()
                        ));
                        lastRocketCount = countRocketsInInventory();
                        rocketConsumeWait = 4; // give a bit more time to avoid double consume
                        burnUseSent = true;
                        return;
                    }

                    if (rocketConsumeWait > 0) {
                        rocketConsumeWait--;
                        if (rocketConsumeWait == 0) {
                            int now = countRocketsInInventory();
                            if (lastRocketCount >= 0 && now >= 0 && now < lastRocketCount) {
                                burnFired = true;
                                // Immediately switch back to block and lock
                                if (ensureBlockInMainHand()) {
                                    preferredBlockSlot = p.getInventory().selectedSlot;
                                }
                                phase = Phase.PLACE_READY;
                            } else {
                                // Not consumed: allow re-try (but keep rocket selected)
                                burnUseSent = false;
                            }
                        }
                        return;
                    }
                }
            }

            case PLACE_READY -> {
                // Ensure block in hand and remember slot (only once)
                if (preferredBlockSlot < 0) {
                    if (ensureBlockInMainHand()) {
                        preferredBlockSlot = p.getInventory().selectedSlot;
                    }
                }
                double vy = p.getVelocity().y;
                boolean turning = lastVy > 0.0 && Math.abs(vy) <= epsilon.get();
                lastVy = vy;

                if (cooldown == 0 && turning) {
                    basePos = p.getBlockPos().down();
                    queue.addAll(generate5x5Order(basePos));
                    cooldown = 3;
                }
            }
        }
    }

    // === Biome ===
    private boolean isAllowedBiomeHere() {
        if (mc.world == null || mc.player == null) return false;

        final Set<String> allowed = parseBiomeIds(biomeIds.get());
        if (allowed.isEmpty()) return true;

        BlockPos playerPos = mc.player.getBlockPos();
        int r = Math.max(0, biomeScanRadius.get());

        for (int dz = -r; dz <= r; dz++) {
            for (int dx = -r; dx <= r; dx++) {
                BlockPos surface = mc.world.getTopPosition(
                    Heightmap.Type.MOTION_BLOCKING_NO_LEAVES,
                    new BlockPos(playerPos.getX() + dx, 0, playerPos.getZ() + dz)
                );
                RegistryEntry<Biome> entry = mc.world.getBiome(surface);
                Optional<RegistryKey<Biome>> key = entry.getKey();
                if (key.isPresent()) {
                    Identifier id = key.get().getValue();
                    if (allowed.contains(id.toString().toLowerCase(Locale.ROOT))) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private Set<String> parseBiomeIds(String raw) {
        if (raw == null || raw.isEmpty()) return Collections.emptySet();
        return Arrays.stream(raw.split(";"))
            .map(String::trim)
            .filter(s -> !s.isEmpty())
            .map(s -> s.toLowerCase(Locale.ROOT))
            .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    // === Orientation ===
    private void forcePitchUp() {
        if (mc.player != null) mc.player.setPitch(-90f);
    }

    // === Elytra equip/unequip (armor index 2 = chest) ===
    private static final int ARMOR_CHEST_INDEX = 2;

    private boolean unequipElytraToHotbar() {
        if (mc.player == null) return false;
        ItemStack chest = mc.player.getInventory().getArmorStack(ARMOR_CHEST_INDEX);
        if (chest == null || chest.isEmpty() || !chest.isOf(Items.ELYTRA)) return true;
        int target = getFreeHotbarSlotExcluding(-1);
        InvUtils.move().fromArmor(ARMOR_CHEST_INDEX).toHotbar(target);
        return true;
    }

    private boolean equipElytra() {
        if (mc.player == null) return false;
        ItemStack chest = mc.player.getInventory().getArmorStack(ARMOR_CHEST_INDEX);
        if (chest != null && !chest.isEmpty() && chest.isOf(Items.ELYTRA)) return true;
        FindItemResult found = InvUtils.find(Items.ELYTRA);
        if (!found.found()) return false;
        InvUtils.move().from(found.slot()).toArmor(ARMOR_CHEST_INDEX);
        return true;
    }

    // === Placement (InvertedY packet order) ===
    private List<BlockPos> generate5x5Order(BlockPos center) {
        List<BlockPos> order = new ArrayList<>(25);
        order.add(center);
        for (int dz = -1; dz <= 1; dz++) {
            for (int dx = -1; dx <= 1; dx++) {
                if (dx == 0 && dz == 0) continue;
                if (Math.max(Math.abs(dx), Math.abs(dz)) == 1) {
                    order.add(center.add(dx, 0, dz));
                }
            }
        }
        for (int dz = -2; dz <= 2; dz++) {
            for (int dx = -2; dx <= 2; dx++) {
                if (Math.max(Math.abs(dx), Math.abs(dz)) == 2) {
                    order.add(center.add(dx, 0, dz));
                }
            }
        }
        return order;
    }

    private boolean placeOne(BlockPos pos) {
        if (mc == null || mc.player == null) return false;
        if (!ensureBlockInMainHand()) return false;

        BlockHitResult bhr = new BlockHitResult(Vec3d.ofCenter(pos), Direction.UP, pos, false);

        mc.player.networkHandler.sendPacket(new PlayerActionC2SPacket(
            PlayerActionC2SPacket.Action.SWAP_ITEM_WITH_OFFHAND, BlockPos.ORIGIN, Direction.DOWN));
        mc.player.networkHandler.sendPacket(new PlayerInteractBlockC2SPacket(
            Hand.OFF_HAND, bhr, mc.player.currentScreenHandler.getRevision() + 2));
        mc.player.networkHandler.sendPacket(new PlayerActionC2SPacket(
            PlayerActionC2SPacket.Action.SWAP_ITEM_WITH_OFFHAND, BlockPos.ORIGIN, Direction.DOWN));

        return true;
    }

    // === Inventory helpers ===
    private boolean ensureBlockInMainHand() {
        if (mc.player == null) return false;

        ItemStack main = mc.player.getMainHandStack();
        if (isBlock(main)) return true;

        Item preferredItem = block.get().asItem();
        FindItemResult found = InvUtils.find(preferredItem);

        if (!found.found()) {
            if (!allowAnyBlock.get()) return false;
            found = findAnyBlock();
        }
        if (!found.found()) return false;

        if (found.isMainHand()) return true;

        if (found.isHotbar()) {
            mc.player.getInventory().selectedSlot = found.slot();
            return isBlock(mc.player.getMainHandStack());
        }

        int targetSlot = getFreeHotbarSlotExcluding(-1);
        InvUtils.move().from(found.slot()).toHotbar(targetSlot);
        mc.player.getInventory().selectedSlot = targetSlot;

        return isBlock(mc.player.getMainHandStack());
    }

    private int findRocketInHotbar() {
        if (mc.player == null) return -1;
        for (int i = 0; i < 9; i++) {
            ItemStack s = mc.player.getInventory().getStack(i);
            if (s != null && !s.isEmpty() && s.isOf(Items.FIREWORK_ROCKET)) return i;
        }
        return -1;
    }

    private boolean moveRocketToHotbar(int target) {
        FindItemResult found = InvUtils.find(Items.FIREWORK_ROCKET);
        if (!found.found()) return false;
        InvUtils.move().from(found.slot()).toHotbar(target);
        return true;
    }

    private int countRocketsInInventory() {
        if (mc.player == null) return -1;
        int total = 0;
        for (int i = 0; i < mc.player.getInventory().size(); i++) {
            ItemStack s = mc.player.getInventory().getStack(i);
            if (s != null && !s.isEmpty() && s.isOf(Items.FIREWORK_ROCKET)) total += s.getCount();
        }
        return total;
    }

    private FindItemResult findAnyBlock() {
        FindItemResult hb = InvUtils.findInHotbar(this::isBlock);
        if (hb.found()) return hb;
        return InvUtils.find(this::isBlock);
    }

    private boolean isBlock(ItemStack s) {
        return s != null && !s.isEmpty() && s.getItem() instanceof BlockItem;
    }

    private int getFreeHotbarSlotExcluding(int exclude) {
        int selected = mc.player.getInventory().selectedSlot;
        // Prefer an empty slot not equal to exclude
        for (int i = 0; i < 9; i++) {
            if (i == exclude) continue;
            if (mc.player.getInventory().getStack(i).isEmpty()) return i;
        }
        // Fallback: if current selected is not excluded and is empty, use it
        if (selected != exclude) {
            ItemStack cur = mc.player.getInventory().getStack(selected);
            if (cur.isEmpty()) return selected;
        }
        // Last resort: pick the first non-excluded slot (may overwrite whatever is there)
        for (int i = 0; i < 9; i++) if (i != exclude) return i;
        return selected;
    }
}
