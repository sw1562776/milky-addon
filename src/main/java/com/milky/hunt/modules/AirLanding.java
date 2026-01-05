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
            "minecraft:warm_ocean;" +
            "minecraft:the_end;" +
            "minecraft:end_highlands;" +
            "minecraft:end_midlands;" +
            "minecraft:end_barrens;"
            // + "minecraft:small_end_islands;"
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
        .defaultValue(0.2)
        .min(0.0)
        .sliderRange(0.0, 0.5)
        .build()
    );

    private final Setting<Boolean> lockMainhand = sgGeneral.add(new BoolSetting.Builder()
        .name("lock-mainhand")
        .description("Lock the block in main hand during placement.")
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
        PLACE_READY,
        PLACE_FOOT,
        CENTERING,
        PLACE_REST,
        FINISHED
    }

    private static final int FOOT_MAX_RETRIES = 8;
    private static final int REST_MAX_PASSES = 30;
    private static final int VERIFY_WAIT_TICKS = 2;

    private static final int PENDING_TICKS = 8;

    private static final double CENTER_EPS = 0.09;
    private static final double CENTER_STEP = 0.15;
    private static final int CENTER_MAX_TICKS = 30;

    private Phase phase = Phase.SEEK_BIOME;

    private double lastVy = 0.0;
    private int cooldown = 0;

    private final Deque<BlockPos> queue = new ArrayDeque<>();
    private BlockPos basePos = null;
    private int delayTimer = 0;

    private int preferredBlockSlot = -1;

    private boolean burnFired = false;
    private int openRetryTimer = 0;
    private int lastRocketCount = -1;
    private int rocketConsumeWait = 0;
    private int rocketSlot = -1;
    private boolean rocketArmed = false;
    private boolean burnUseSent = false;

    private BlockPos footPos = null;
    private List<BlockPos> restOrder = Collections.emptyList();
    private int footRetries = 0;
    private int footVerifyTimer = 0;
    private int restPass = 0;
    private int restVerifyTimer = 0;

    private int centeringTicks = 0;

    private final Map<BlockPos, Integer> pending = new HashMap<>();
    private boolean centerKeepsQueue = false;

    public AirLanding() {
        super(Addon.MilkyModCategory, "AirLanding", "Biome aware landing: free-fall, re-equip elytra, landing burn, then airplace a platform.");
    }

    @Override
    public void onDeactivate() {
        try { mc.options.forwardKey.setPressed(false); } catch (Throwable ignored) {}
    }

    @Override
    public void onActivate() {
        phase = Phase.SEEK_BIOME;

        lastVy = 0.0;
        cooldown = 0;
        try { mc.options.forwardKey.setPressed(false); } catch (Throwable ignored) {}
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

        footPos = null;
        restOrder = Collections.emptyList();
        footRetries = 0;
        footVerifyTimer = 0;
        restPass = 0;
        restVerifyTimer = 0;

        centeringTicks = 0;
        pending.clear();
        centerKeepsQueue = false;
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

        if (phase != Phase.CENTERING) {
            try { mc.options.forwardKey.setPressed(false); } catch (Throwable ignored) {}
        }

        if (cooldown > 0) cooldown--;

        tickPending();

        if (lockMainhand.get() && preferredBlockSlot >= 0 && (phase == Phase.PLACE_FOOT || phase == Phase.PLACE_REST || !queue.isEmpty())) {
            if (preferredBlockSlot < 9) {
                ItemStack s = p.getInventory().getStack(preferredBlockSlot);
                if (isDesiredBlockItem(s)) p.getInventory().selectedSlot = preferredBlockSlot;
                else preferredBlockSlot = -1;
            }
        }

        if (phase == Phase.CENTERING) {
            forcePitchUp();
            if (footPos == null || !isTargetBlock(footPos)) {
                failToRecover();
                return;
            }

            if (!p.isOnGround()) {
                try { mc.options.forwardKey.setPressed(false); } catch (Throwable ignored) {}
                return;
            }
            double tx = footPos.getX() + 0.5;
            double tz = footPos.getZ() + 0.5;
            double cx = p.getX();
            double cz = p.getZ();

            double dx = tx - cx;
            double dz = tz - cz;

            if (Math.abs(dx) <= CENTER_EPS && Math.abs(dz) <= CENTER_EPS) {
                try { mc.options.forwardKey.setPressed(false); } catch (Throwable ignored) {}
                if (centerKeepsQueue) {
                try { mc.options.forwardKey.setPressed(false); } catch (Throwable ignored) {}
                phase = Phase.PLACE_REST;
                centerKeepsQueue = false;
                return;
            }
            startRestPlacement();
                return;
            }

            centeringTicks++;
            if (centeringTicks > CENTER_MAX_TICKS) {
                try { mc.options.forwardKey.setPressed(false); } catch (Throwable ignored) {}
                if (centerKeepsQueue) {
                try { mc.options.forwardKey.setPressed(false); } catch (Throwable ignored) {}
                phase = Phase.PLACE_REST;
                centerKeepsQueue = false;
                return;
            }
            startRestPlacement();
                return;
            }

            double dist = Math.hypot(dx, dz);
            if (dist < 1e-6) {
                try { mc.options.forwardKey.setPressed(false); } catch (Throwable ignored) {}
                if (centerKeepsQueue) {
                try { mc.options.forwardKey.setPressed(false); } catch (Throwable ignored) {}
                phase = Phase.PLACE_REST;
                centerKeepsQueue = false;
                return;
            }
            startRestPlacement();
                return;
            }

            float yaw = (float) Math.toDegrees(Math.atan2(-dx, dz));

            p.setYaw(yaw);
            try { mc.options.forwardKey.setPressed(true); } catch (Throwable ignored) {}

            return;
        }

        if (footVerifyTimer > 0) {
            footVerifyTimer--;
            if (footVerifyTimer == 0) {
                if (footPos == null) {
                    failToRecover();
                    return;
                }
                if (isTargetBlock(footPos)) {
                    queue.clear();
                    delayTimer = 0;
                    enterCentering(false);
                } else {
                    footRetries++;
                    if (footRetries >= FOOT_MAX_RETRIES) {
                        failToRecover();
                    } else {
                        queue.clear();
                        delayTimer = 0;
                        queue.add(footPos);
                    }
                }
            }
            return;
        }

        if (restVerifyTimer > 0) {
            restVerifyTimer--;
            if (restVerifyTimer == 0) {
                if (footPos == null || !isTargetBlock(footPos)) {
                    failToRecover();
                    return;
                }
                if (verifyAndRepairRest()) {
                    finishPlacement();
                }
            }
            return;
        }

        if (!queue.isEmpty()) {
            if (delayTimer > 0) {
                delayTimer--;
                return;
            }

            if (phase == Phase.PLACE_FOOT) {
                BlockPos fp = queue.peekFirst();
                if (fp != null && isTargetBlock(fp)) {
                    queue.clear();
                    enterCentering(false);
                    return;
                }
            }


            if (phase == Phase.PLACE_REST) {
                if (footPos == null || !isTargetBlock(footPos)) { failToRecover(); return; }
                if (!p.isOnGround()) return;
                if (!isCenteredOnFoot()) { enterCentering(true); return; }
            }

            while (!queue.isEmpty()) {
                BlockPos head = queue.peekFirst();
                if (head == null) {
                    queue.clear();
                    break;
                }
                if (!isTargetBlock(head)) break;
                queue.removeFirst();
            }

            if (queue.isEmpty()) {
                if (phase == Phase.PLACE_REST) restVerifyTimer = VERIFY_WAIT_TICKS;
                return;
            }

            BlockPos next = queue.peekFirst();
            if (next == null) {
                queue.clear();
                return;
            }

            if (phase == Phase.PLACE_REST && pending.containsKey(next)) {
                delayTimer = 1;
                return;
            }

            if (placeOne(next)) {
                queue.removeFirst();
                delayTimer = placeDelay.get();

                if (phase == Phase.PLACE_REST) pending.put(next, PENDING_TICKS);

                if (phase == Phase.PLACE_FOOT) {
                    footVerifyTimer = VERIFY_WAIT_TICKS;
                } else if (phase == Phase.PLACE_REST && queue.isEmpty()) {
                    restVerifyTimer = VERIFY_WAIT_TICKS;
                }
            } else {
                queue.clear();
                basePos = null;
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
                    enterOpenAndBurn();
                }
            }

            case OPEN_AND_BURN -> {
                forcePitchUp();

                if (openRetryTimer > 0) {
                    openRetryTimer--;
                } else {
                    p.networkHandler.sendPacket(new ClientCommandC2SPacket(
                        p, ClientCommandC2SPacket.Mode.START_FALL_FLYING
                    ));
                    openRetryTimer = Math.max(1, elytraOpenRetryTicks.get());
                }

                if (!burnFired) {
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
                            return;
                        }
                    }

                    if (!burnUseSent) {
                        int seq = p.currentScreenHandler.getRevision() + 1;
                        p.networkHandler.sendPacket(new PlayerInteractItemC2SPacket(
                            Hand.MAIN_HAND, seq, p.getYaw(), p.getPitch()
                        ));
                        lastRocketCount = countRocketsInInventory();
                        rocketConsumeWait = 4;
                        burnUseSent = true;
                        return;
                    }

                    if (rocketConsumeWait > 0) {
                        rocketConsumeWait--;
                        if (rocketConsumeWait == 0) {
                            int now = countRocketsInInventory();
                            if (lastRocketCount >= 0 && now >= 0 && now < lastRocketCount) {
                                burnFired = true;
                                if (ensureBlockInMainHand()) {
                                    preferredBlockSlot = p.getInventory().selectedSlot;
                                }
                                phase = Phase.PLACE_READY;
                            } else {
                                burnUseSent = false;
                            }
                        }
                        return;
                    }
                }
            }

            case PLACE_READY -> {
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
                    beginFootPlacement(basePos);
                    cooldown = 3;
                }
            }

            case PLACE_FOOT, CENTERING, PLACE_REST, FINISHED -> {}
        }
    }

    private void enterOpenAndBurn() {
        phase = Phase.OPEN_AND_BURN;
        burnFired = false;
        openRetryTimer = 0;
        lastRocketCount = countRocketsInInventory();
        rocketConsumeWait = 0;
        rocketSlot = -1;
        rocketArmed = false;
        burnUseSent = false;
    }

    private void beginFootPlacement(BlockPos foot) {
        footPos = foot;
        List<BlockPos> all = generate2x2RailOrder(footPos);
        restOrder = all.stream().filter(pos -> !pos.equals(footPos)).collect(Collectors.toList());

        queue.clear();
        delayTimer = 0;
        footRetries = 0;
        footVerifyTimer = 0;

        restPass = 0;
        restVerifyTimer = 0;

        phase = Phase.PLACE_FOOT;
        queue.add(footPos);
    }

    private void startRestPlacement() {
        try { mc.options.forwardKey.setPressed(false); } catch (Throwable ignored) {}
        if (phase == Phase.PLACE_REST) return;
        queue.clear();
        delayTimer = 0;
        phase = Phase.PLACE_REST;
        queue.addAll(restOrder);
    }

    private boolean verifyAndRepairRest() {
        if (mc.world == null) return false;

        boolean ok = true;
        boolean hasMissing = false;
        boolean hasRequeue = false;

        for (BlockPos pos : restOrder) {
            if (isTargetBlock(pos)) continue;
            ok = false;
            hasMissing = true;
            if (!pending.containsKey(pos)) hasRequeue = true;
        }
        if (ok) return true;

        restPass++;
        if (restPass >= REST_MAX_PASSES) return true;

        if (hasMissing && !hasRequeue) {
            restVerifyTimer = VERIFY_WAIT_TICKS;
            return false;
        }

        queue.clear();
        delayTimer = 0;
        for (BlockPos pos : restOrder) {
            if (!isTargetBlock(pos) && !pending.containsKey(pos)) queue.add(pos);
        }
        return false;
    }

    private boolean isTargetBlock(BlockPos pos) {
        if (mc == null || mc.world == null) return false;
        return mc.world.getBlockState(pos).isOf(block.get());
    }

    private void failToRecover() {
        try { mc.options.forwardKey.setPressed(false); } catch (Throwable ignored) {}
        queue.clear();
        delayTimer = 0;

        footVerifyTimer = 0;
        restVerifyTimer = 0;

        footPos = null;
        restOrder = Collections.emptyList();

        restPass = 0;
        footRetries = 0;

        centeringTicks = 0;

        lastVy = 0.0;
        cooldown = 0;

        phase = Phase.WAIT_BURN_Y;
        burnFired = false;
        openRetryTimer = 0;
        lastRocketCount = -1;
        rocketConsumeWait = 0;
        rocketSlot = -1;
        rocketArmed = false;
        burnUseSent = false;
    }

    private void finishPlacement() {
        try { mc.options.forwardKey.setPressed(false); } catch (Throwable ignored) {}
        footPos = null;
        restOrder = Collections.emptyList();

        footVerifyTimer = 0;
        restVerifyTimer = 0;
        footRetries = 0;
        restPass = 0;

        centeringTicks = 0;

        if (autoDisable.get()) {
            toggle();
        } else {
            phase = Phase.FINISHED;
        }
    }

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

    private void forcePitchUp() {
        if (mc.player != null) mc.player.setPitch(-90f);
    }

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

    private List<BlockPos> generate2x2RailOrder(BlockPos corner) {
        List<BlockPos> out = new ArrayList<>(20);

        out.add(corner);
        out.add(corner.add(1, 0, 0));
        out.add(corner.add(0, 0, 1));
        out.add(corner.add(1, 0, 1));

        for (int yOff = 1; yOff <= 2; yOff++) {
            out.add(corner.add(0, yOff, -1));
            out.add(corner.add(1, yOff, -1));

            out.add(corner.add(0, yOff, 2));
            out.add(corner.add(1, yOff, 2));

            out.add(corner.add(-1, yOff, 0));
            out.add(corner.add(-1, yOff, 1));

            out.add(corner.add(2, yOff, 0));
            out.add(corner.add(2, yOff, 1));
        }

        return out;
    }

    private boolean placeOne(BlockPos pos) {
        if (mc == null || mc.player == null || mc.world == null) return false;
        if (mc.world.getBlockState(pos).isOf(block.get())) return true;
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

    private boolean ensureBlockInMainHand() {
        if (mc.player == null) return false;

        ItemStack main = mc.player.getMainHandStack();
        if (isDesiredBlockItem(main)) return true;

        FindItemResult found = InvUtils.find(block.get().asItem());
        if (!found.found()) return false;

        if (found.isMainHand()) return true;

        if (found.isHotbar()) {
            mc.player.getInventory().selectedSlot = found.slot();
            preferredBlockSlot = found.slot();
            return isDesiredBlockItem(mc.player.getMainHandStack());
        }

        int targetSlot = getFreeHotbarSlotExcluding(-1);
        InvUtils.move().from(found.slot()).toHotbar(targetSlot);
        mc.player.getInventory().selectedSlot = targetSlot;
        preferredBlockSlot = targetSlot;

        return isDesiredBlockItem(mc.player.getMainHandStack());
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


    private boolean isDesiredBlockItem(ItemStack s) {
        if (s == null || s.isEmpty()) return false;
        if (!(s.getItem() instanceof BlockItem)) return false;
        return ((BlockItem) s.getItem()).getBlock() == block.get();
    }
    private int getFreeHotbarSlotExcluding(int exclude) {
        int selected = mc.player.getInventory().selectedSlot;
        for (int i = 0; i < 9; i++) {
            if (i == exclude) continue;
            if (mc.player.getInventory().getStack(i).isEmpty()) return i;
        }
        if (selected != exclude) {
            ItemStack cur = mc.player.getInventory().getStack(selected);
            if (cur.isEmpty()) return selected;
        }
        for (int i = 0; i < 9; i++) if (i != exclude) return i;
        return selected;
    }
    private void tickPending() {
        if (pending.isEmpty()) return;
        Iterator<Map.Entry<BlockPos, Integer>> it = pending.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<BlockPos, Integer> e = it.next();
            int v = e.getValue() - 1;
            if (v <= 0) it.remove();
            else e.setValue(v);
        }
    }

    private boolean isCenteredOnFoot() {
        if (mc == null || mc.player == null || footPos == null) return false;
        double tx = footPos.getX() + 0.5;
        double tz = footPos.getZ() + 0.5;
        double cx = mc.player.getX();
        double cz = mc.player.getZ();
        return Math.abs(tx - cx) <= CENTER_EPS && Math.abs(tz - cz) <= CENTER_EPS;
    }

    private void enterCentering(boolean keepQueue) {
        centerKeepsQueue = keepQueue;
        phase = Phase.CENTERING;
        centeringTicks = 0;
        try { mc.options.forwardKey.setPressed(false); } catch (Throwable ignored) {}
    }


}
