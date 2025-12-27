package com.milky.hunt.modules;

import baritone.api.BaritoneAPI;
import baritone.api.pathing.goals.GoalBlock;
import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import meteordevelopment.meteorclient.events.entity.player.PlayerMoveEvent;
import meteordevelopment.meteorclient.events.packets.PacketEvent;
import meteordevelopment.meteorclient.events.world.ChunkDataEvent;
import meteordevelopment.meteorclient.events.world.PlaySoundEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.mixininterface.IVec3d;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.systems.modules.Modules;
import meteordevelopment.meteorclient.systems.modules.player.ChestSwap;
import meteordevelopment.meteorclient.systems.modules.world.Timer;
import meteordevelopment.meteorclient.utils.Utils;
import meteordevelopment.meteorclient.utils.player.FindItemResult;
import meteordevelopment.meteorclient.utils.player.InvUtils;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.entity.MovementType;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.network.packet.c2s.play.*;
import net.minecraft.network.packet.s2c.play.CloseScreenS2CPacket;
import net.minecraft.network.packet.s2c.play.PlayerPositionLookS2CPacket;
import com.milky.hunt.Addon;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;

import java.util.List;

import static com.milky.hunt.Utils.*;

public class BoostedBounce extends Module {
    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgObstaclePasser = settings.createGroup("Obstacle Passer");

    // bounce option removed — always on while module is active

    private final Setting<Boolean> motionYBoost = sgGeneral.add(new BoolSetting.Builder()
        .name("Motion Y Boost")
        .description("Greatly increases speed by cancelling Y momentum.")
        .defaultValue(false)
        .build()
    );

    private final Setting<Boolean> onlyWhileColliding = sgGeneral.add(new BoolSetting.Builder()
        .name("Only While Colliding")
        .description("Only enables motion y boost if colliding with a wall.")
        .defaultValue(true)
        .visible(motionYBoost::get)
        .build()
    );

    private final Setting<Boolean> tunnelBounce = sgGeneral.add(new BoolSetting.Builder()
        .name("Tunnel Bounce")
        .description("Allows you to bounce in 1x2 tunnels. This should not be on if you are not in a tunnel.")
        .defaultValue(false)
        .visible(motionYBoost::get)
        .build()
    );

    private final Setting<Double> speed = sgGeneral.add(new DoubleSetting.Builder()
        .name("Speed")
        .description("The speed in blocks per second to keep you at.")
        .defaultValue(100.0)
        .sliderRange(20, 250)
        .visible(motionYBoost::get)
        .build()
    );

    private final Setting<Boolean> lockPitch = sgGeneral.add(new BoolSetting.Builder()
        .name("Lock Pitch")
        .description("Whether to lock your pitch when bounce is enabled.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Double> pitch = sgGeneral.add(new DoubleSetting.Builder()
        .name("Pitch")
        .description("The pitch to set when bounce is enabled.")
        .defaultValue(90.0)
        .sliderRange(-90, 90)
        .visible(lockPitch::get)
        .build()
    );

    private final Setting<Boolean> lockYaw = sgGeneral.add(new BoolSetting.Builder()
        .name("Lock Yaw")
        .description("Whether to lock your yaw when bounce is enabled.")
        .defaultValue(false)
        .build()
    );

    private final Setting<Boolean> useCustomYaw = sgGeneral.add(new BoolSetting.Builder()
        .name("Use Custom Yaw")
        .description("Enable this if you want to use a yaw that isn't a factor of 45.")
        .defaultValue(false)
        .build()
    );

    private final Setting<Double> yaw = sgGeneral.add(new DoubleSetting.Builder()
        .name("Yaw")
        .description("The yaw to set when bounce is enabled. This is auto set to the closest 45 deg angle to you unless Use Custom Yaw is enabled.")
        .defaultValue(0.0)
        .sliderRange(0, 359)
        .visible(useCustomYaw::get)
        .build()
    );

    private final Setting<Boolean> highwayObstaclePasser = sgObstaclePasser.add(new BoolSetting.Builder()
        .name("Highway Obstacle Passer")
        .description("Uses baritone to pass obstacles.")
        .defaultValue(false)
        .build()
    );

    private final Setting<Boolean> useCustomStartPos = sgObstaclePasser.add(new BoolSetting.Builder()
        .name("Use Custom Start Position")
        .description("Enable and set this ONLY if you are on a ringroad or don't want to be locked to a highway. Otherwise (0, 0) is the start position and will be automatically used.")
        .defaultValue(false)
        .visible(highwayObstaclePasser::get)
        .build()
    );

    private final Setting<BlockPos> startPos = sgObstaclePasser.add(new BlockPosSetting.Builder()
        .name("Start Position")
        .description("The start position to use when using a custom start position.")
        .defaultValue(new BlockPos(0, 0, 0))
        .visible(() -> highwayObstaclePasser.get() && useCustomStartPos.get())
        .build()
    );

    private final Setting<Boolean> awayFromStartPos = sgObstaclePasser.add(new BoolSetting.Builder()
        .name("Away From Start Position")
        .description("If true, will go away from the start position instead of towards it. The start pos is (0,0) if it is not set to a custom start pos.")
        .defaultValue(true)
        .visible(highwayObstaclePasser::get)
        .build()
    );

    private final Setting<Double> distance = sgObstaclePasser.add(new DoubleSetting.Builder()
        .name("Distance")
        .description("The distance to set the baritone goal for path realignment.")
        .defaultValue(10.0)
        .visible(highwayObstaclePasser::get)
        .build()
    );

    private final Setting<Integer> targetY = sgObstaclePasser.add(new IntSetting.Builder()
        .name("Y Level")
        .description("The Y level to bounce at. This must be correct or bounce will not start properly.")
        .defaultValue(120)
        .visible(() -> highwayObstaclePasser.get() && !useCustomStartPos.get())
        .build()
    );

    private final Setting<Boolean> avoidPortalTraps = sgObstaclePasser.add(new BoolSetting.Builder()
        .name("Avoid Portal Traps")
        .description("Will attempt to detect portal traps on chunk load and avoid them.")
        .defaultValue(false)
        .visible(highwayObstaclePasser::get)
        .build()
    );

    private final Setting<Double> portalAvoidDistance = sgObstaclePasser.add(new DoubleSetting.Builder()
        .name("Portal Avoid Distance")
        .description("The distance to a portal trap where the obstacle passer will takeover and go around it.")
        .defaultValue(20)
        .min(0)
        .sliderMax(50)
        .visible(() -> highwayObstaclePasser.get() && avoidPortalTraps.get())
        .build()
    );

    private final Setting<Integer> portalScanWidth = sgObstaclePasser.add(new IntSetting.Builder()
        .name("Portal Scan Width")
        .description("The width on the axis of the highway that will be scanned for portal traps.")
        .defaultValue(5)
        .min(3)
        .sliderMax(10)
        .visible(() -> highwayObstaclePasser.get() && avoidPortalTraps.get())
        .build()
    );

    private final Setting<Boolean> fakeFly = sgGeneral.add(new BoolSetting.Builder()
        .name("Chestplate / Fakefly")
        .description("Lets you fly using a chestplate to use almost 0 elytra durability. Must have elytra in hotbar.")
        .defaultValue(false)
        .build()
    );

    private final Setting<Boolean> toggleElytra = sgGeneral.add(new BoolSetting.Builder()
        .name("Toggle Elytra")
        .description("Equips an elytra on activate, and a chestplate on deactivate.")
        .defaultValue(false)
        .visible(() -> !fakeFly.get())
        .build()
    );

    // --- Auto replace Elytra when durability is low ---
    private final Setting<Boolean> autoReplaceElytra = sgGeneral.add(new BoolSetting.Builder()
        .name("Auto Replace Elytra")
        .description("Automatically replace Elytra when durability falls below threshold.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Integer> minElytraDurability = sgGeneral.add(new IntSetting.Builder()
        .name("Min Elytra Durability")
        .description("If current Elytra has less than this remaining durability, attempt to replace it.")
        .defaultValue(10)
        .sliderRange(1, 432)
        .visible(autoReplaceElytra::get)
        .build()
    );

    // --- Auto wear Gold armor (helmet/leggings/boots) ---
    private final Setting<Boolean> autoWearGold = sgGeneral.add(new BoolSetting.Builder()
        .name("Auto Wear Gold")
        .description("Automatically wear selected gold armor pieces above a minimum durability.")
        .defaultValue(false)
        .build()
    );

    private final Setting<Integer> minGoldDurability = sgGeneral.add(new IntSetting.Builder()
        .name("Min Gold Durability")
        .description("If equipped gold piece has less than this remaining durability, replace it.")
        .defaultValue(10)
        .sliderRange(1, 100)
        .visible(autoWearGold::get)
        .build()
    );

    private final Setting<List<Item>> goldPieces = sgGeneral.add(new ItemListSetting.Builder()
        .name("Gold Pieces")
        .description("Choose which gold armor types to maintain: helmet, leggings, boots.")
        .filter(it -> it == Items.GOLDEN_HELMET || it == Items.GOLDEN_LEGGINGS || it == Items.GOLDEN_BOOTS)
        .defaultValue(List.of(Items.GOLDEN_HELMET, Items.GOLDEN_LEGGINGS, Items.GOLDEN_BOOTS))
        .visible(autoWearGold::get)
        .build()
    );

    public BoostedBounce() {
        super(Addon.MilkyModCategory, "BoostedBounce", "Elytra fly with some more features.");
    }

    private boolean startSprinting;
    private BlockPos portalTrap = null;
    private boolean paused = false;

    private boolean elytraToggled = false;

    private Vec3d lastUnstuckPos;
    private int stuckTimer = 0;

    private Vec3d lastPos;

    private final double maxDistance = 16 * 5;
    private BlockPos tempPath = null;
    private boolean waitingForChunksToLoad;
    private int reopenTicks = 0;

    @EventHandler
    private void onReceivePacket(PacketEvent.Receive event) {
        if (event.packet instanceof PlayerPositionLookS2CPacket) {
            // no-op
        } else if (event.packet instanceof CloseScreenS2CPacket) {
            event.cancel();
        }
    }

    @Override
    public void onActivate() {
        if (mc.player == null || mc.player.getAbilities().allowFlying) return;

        startSprinting = mc.player.isSprinting();
        tempPath = null;
        portalTrap = null;
        paused = false;
        waitingForChunksToLoad = false;
        elytraToggled = false;
        lastPos = mc.player.getPos();
        lastUnstuckPos = mc.player.getPos();
        stuckTimer = 0;

        if (mc.player.getPos().multiply(1, 0, 1).length() >= 100) {
            if (BaritoneAPI.getProvider().getPrimaryBaritone().getElytraProcess().currentDestination() == null) {
                BaritoneAPI.getProvider().getPrimaryBaritone().getCustomGoalProcess().setGoal(null);
            }

            if (!useCustomStartPos.get()) {
                startPos.set(new BlockPos(0, 0, 0));
            }

            if (!useCustomYaw.get()) {
                if (mc.player.getBlockPos().getSquaredDistance(startPos.get()) < 10_000 || !highwayObstaclePasser.get()) {
                    double playerAngleNormalized = angleOnAxis(mc.player.getYaw());
                    yaw.set(playerAngleNormalized);
                } else {
                    BlockPos directionVec = mc.player.getBlockPos().subtract(startPos.get());
                    double angle = Math.toDegrees(Math.atan2(-directionVec.getX(), directionVec.getZ()));
                    double angleNormalized = angleOnAxis(angle);
                    if (!awayFromStartPos.get()) angleNormalized += 180;
                    yaw.set(angleNormalized);
                }
            }
        }
    }

    @EventHandler
    private void onPlayerMove(PlayerMoveEvent event) {
        if (mc.player == null || event.type != MovementType.SELF || !enabled() || !motionYBoost.get()) return;

        if (onlyWhileColliding.get() && !mc.player.horizontalCollision) return;

        if (lastPos != null) {
            double speedBps = mc.player.getPos().subtract(lastPos).multiply(20, 0, 20).length();

            Timer timer = Modules.get().get(Timer.class);
            if (timer.isActive()) speedBps *= timer.getMultiplier();

            if (mc.player.isOnGround() && mc.player.isSprinting() && speedBps < speed.get()) {
                if (speedBps > 20 || tunnelBounce.get()) {
                    ((IVec3d) event.movement).meteor$setY(0.0);
                }
                mc.player.setVelocity(mc.player.getVelocity().x, 0.0, mc.player.getVelocity().z);
            }
        }

        lastPos = mc.player.getPos();
    }

    @Override
    public void onDeactivate() {
        if (mc.player == null) return;

        if (BaritoneAPI.getProvider().getPrimaryBaritone().getElytraProcess().currentDestination() == null) {
            BaritoneAPI.getProvider().getPrimaryBaritone().getCustomGoalProcess().setGoal(null);
        }

        mc.player.setSprinting(startSprinting);

        if (toggleElytra.get() && !fakeFly.get()) {
            maybeSwapBackChestplate();
            maybeSwapBackLeggings();
        }
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (mc.player == null || mc.player.getAbilities().allowFlying) return;

        if (toggleElytra.get() && !fakeFly.get() && !elytraToggled) {
            if (!(mc.player.getEquippedStack(EquipmentSlot.CHEST).getItem().equals(Items.ELYTRA))) {
                Modules.get().get(ChestSwap.class).swap();
            } else {
                elytraToggled = true;
            }
        }

        if (enabled()) mc.player.setSprinting(true);

        if (autoReplaceElytra.get() && !fakeFly.get()) {
            maybeReplaceElytra();
        }
        if (reopenTicks > 0) {
            tryStartFallFlying();
            reopenTicks--;
        }

        if (autoWearGold.get()) {
            maintainGoldArmor();
        }

        if (tempPath != null && mc.player.getBlockPos().getSquaredDistance(tempPath) < 500) {
            tempPath = null;
            BaritoneAPI.getProvider().getPrimaryBaritone().getCustomGoalProcess().setGoal(null);
        } else if (tempPath != null) {
            BaritoneAPI.getProvider().getPrimaryBaritone().getCustomGoalProcess().setGoalAndPath(new GoalBlock(tempPath));
            return;
        }

        if (highwayObstaclePasser.get() && BaritoneAPI.getProvider().getPrimaryBaritone().getCustomGoalProcess().getGoal() != null) {
            return;
        }

        if (mc.player.squaredDistanceTo(lastUnstuckPos) < 25) stuckTimer++;
        else {
            stuckTimer = 0;
            lastUnstuckPos = mc.player.getPos();
        }

        int ty = getTargetY();
        if (highwayObstaclePasser.get() && mc.player.getPos().length() > 100 && (
            mc.player.getY() < ty || mc.player.getY() > ty + 2 ||
                (mc.player.horizontalCollision && isFrontBlocked(mc.player)) ||
                (portalTrap != null && portalTrap.getSquaredDistance(mc.player.getBlockPos()) < portalAvoidDistance.get() * portalAvoidDistance.get()) ||
                waitingForChunksToLoad || stuckTimer > 50)) {

            waitingForChunksToLoad = false;
            paused = true;
            BlockPos goal = mc.player.getBlockPos();
            double currDistance = distance.get();

            if (portalTrap != null) {
                currDistance += mc.player.getPos().distanceTo(portalTrap.toCenterPos());
                portalTrap = null;
                info("Pathing around portal.");
            }

            do {
                if (currDistance > maxDistance) {
                    tempPath = goal;
                    BaritoneAPI.getProvider().getPrimaryBaritone().getCustomGoalProcess().setGoalAndPath(new GoalBlock(goal));
                    return;
                }

                Vec3d unitYawVec = yawToDirection(pathYaw());
                Vec3d travelVec = mc.player.getPos().subtract(startPos.get().toCenterPos());
                double parallelCurrPosDot = travelVec.multiply(new Vec3d(1, 0, 1)).dotProduct(unitYawVec);
                Vec3d parallelCurrPosComponent = unitYawVec.multiply(parallelCurrPosDot);
                Vec3d pos = startPos.get().toCenterPos().add(parallelCurrPosComponent);
                pos = positionInDirection(pos, pathYaw(), currDistance);

                goal = new BlockPos((int) Math.floor(pos.x), ty, (int) Math.floor(pos.z));
                currDistance++;

                if (mc.world.getBlockState(goal).getBlock() == Blocks.VOID_AIR) {
                    waitingForChunksToLoad = true;
                    return;
                }
            }
            while (!mc.world.getBlockState(goal.down()).isSolidBlock(mc.world, goal.down()) ||
                mc.world.getBlockState(goal).getBlock() == Blocks.NETHER_PORTAL ||
                !mc.world.getBlockState(goal).isAir());

            BaritoneAPI.getProvider().getPrimaryBaritone().getCustomGoalProcess().setGoalAndPath(new GoalBlock(goal));
        } else {
            paused = false;
            if (!enabled()) return;

            if (!fakeFly.get()) {
                if (mc.player.isOnGround() && (!motionYBoost.get() || Utils.getPlayerSpeed().multiply(1, 0, 1).length() < speed.get())) {
                    mc.player.jump();
                }
            }

            if (lockYaw.get()) mc.player.setYaw(yaw.get().floatValue());
            if (lockPitch.get()) mc.player.setPitch(pitch.get().floatValue());
        }

        if (enabled()) {
            if (fakeFly.get()) doGrimEflyStuff();
            else sendStartFlyingPacket();
        }
    }

    public boolean enabled() {
        return this.isActive() && !paused && mc.player != null && (fakeFly.get() || mc.player.getEquippedStack(EquipmentSlot.CHEST).getItem().equals(Items.ELYTRA));
    }

    private void doGrimEflyStuff() {
        FindItemResult itemResult = InvUtils.findInHotbar(Items.ELYTRA);
        if (!itemResult.found()) return;

        swapToItem(itemResult.slot());
        sendStartFlyingPacket();

        if (mc.player.isOnGround() && (!motionYBoost.get() || Utils.getPlayerSpeed().multiply(1, 0, 1).length() < speed.get())) {
            mc.player.jump();
        }

        swapToItem(itemResult.slot());
    }

    @EventHandler
    private void onPlaySound(PlaySoundEvent event) {
        List<Identifier> armorEquipSounds = List.of(
            Identifier.of("minecraft:item.armor.equip_generic"),
            Identifier.of("minecraft:item.armor.equip_netherite"),
            Identifier.of("minecraft:item.armor.equip_elytra"),
            Identifier.of("minecraft:item.armor.equip_diamond"),
            Identifier.of("minecraft:item.armor.equip_gold"),
            Identifier.of("minecraft:item.armor.equip_iron"),
            Identifier.of("minecraft:item.armor.equip_chain"),
            Identifier.of("minecraft:item.armor.equip_leather"),
            Identifier.of("minecraft:item.elytra.flying")
        );
        for (Identifier identifier : armorEquipSounds) {
            if (identifier.equals(event.sound.getId())) {
                event.cancel();
                break;
            }
        }
    }

    // hotbar<->chest swap for FakeFly
    private void swapToItem(int slot) {
        ItemStack chestItem = mc.player.getInventory().getStack(38);
        ItemStack hotbarSwapItem = mc.player.getInventory().getStack(slot);

        Int2ObjectMap<ItemStack> changedSlots = new Int2ObjectOpenHashMap<>();
        changedSlots.put(6, hotbarSwapItem);
        changedSlots.put(slot + 36, chestItem);

        sendSwapPacket(changedSlots, slot);
    }

    private void sendStartFlyingPacket() {
        if (mc.player == null) return;
        mc.player.networkHandler.sendPacket(new ClientCommandC2SPacket(
            mc.player,
            ClientCommandC2SPacket.Mode.START_FALL_FLYING
        ));
    }

    private void sendSwapPacket(Int2ObjectMap<ItemStack> changedSlots, int buttonNum) {
        int syncId = mc.player.currentScreenHandler.syncId;
        int stateId = mc.player.currentScreenHandler.getRevision();

        mc.player.networkHandler.sendPacket(new ClickSlotC2SPacket(
            syncId,
            stateId,
            6,
            buttonNum,
            SlotActionType.SWAP,
            new ItemStack(Items.AIR),
            changedSlots
        ));
    }

    @EventHandler
    private void onChunkData(ChunkDataEvent event) {
        if (!avoidPortalTraps.get() || !highwayObstaclePasser.get()) return;
        ChunkPos pos = event.chunk().getPos();

        int ty = getTargetY();
        BlockPos centerPos = pos.getCenterAtY(ty);

        Vec3d moveDir = yawToDirection(pathYaw());
        double distanceToHighway = distancePointToDirection(Vec3d.of(centerPos), moveDir, mc.player.getPos());
        if (distanceToHighway > 21) return;

        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                for (int y = ty; y < ty + 3; y++) {
                    BlockPos position = new BlockPos(pos.x * 16 + x, y, pos.z * 16 + z);
                    if (distancePointToDirection(Vec3d.of(position), moveDir, mc.player.getPos()) > portalScanWidth.get()) continue;

                    if (mc.world.getBlockState(position).getBlock().equals(Blocks.NETHER_PORTAL)) {
                        BlockPos posBehind = new BlockPos(
                            (int) Math.floor(position.getX() + moveDir.x),
                            position.getY(),
                            (int) Math.floor(position.getZ() + moveDir.z)
                        );
                        if (mc.world.getBlockState(posBehind).isSolidBlock(mc.world, posBehind) ||
                            mc.world.getBlockState(posBehind).getBlock() == Blocks.NETHER_PORTAL) {
                            if (portalTrap == null ||
                                (portalTrap.getSquaredDistance(posBehind) > 100 &&
                                    mc.player.getBlockPos().getSquaredDistance(posBehind) < mc.player.getBlockPos().getSquaredDistance(portalTrap))) {
                                portalTrap = posBehind;
                            }
                        }
                    }
                }
            }
        }
    }

    private static boolean isFrontBlocked(net.minecraft.entity.player.PlayerEntity p) {
        if (p == null || p.isRemoved()) return false;
        World w = p.getWorld();
        Box bb = p.getBoundingBox();
        Direction facing = p.getHorizontalFacing();
        Vec3d fwd = new Vec3d(facing.getOffsetX(), 0, facing.getOffsetZ());
        double probe = 0.62;
        double[] ys = new double[]{bb.minY + 0.2, (bb.minY + bb.maxY) * 0.5, bb.maxY - 0.1};
        for (double y : ys) {
            BlockPos pos = BlockPos.ofFloored(p.getX() + fwd.x * probe, y, p.getZ() + fwd.z * probe);
            if (isHard(w.getBlockState(pos), w, pos)) return true;
        }
        return false;
    }

    private static boolean isHard(BlockState s, World w, BlockPos pos) {
        if (s.isAir()) return false;
        if (s.isOf(Blocks.NETHER_PORTAL)) return true;
        return s.isFullCube(w, pos) && s.isSolidBlock(w, pos);
    }

    private static double roundAngle(double angleDeg) {
        double a = ((angleDeg % 360.0) + 360.0) % 360.0;
        double snapped = Math.round(a / 45.0) * 45.0;
        return ((snapped % 360.0) + 360.0) % 360.0;
    }

    private double pathYaw() {
        return roundAngle(yaw.get());
    }

    private int getTargetY() {
        return useCustomStartPos.get() ? startPos.get().getY() : targetY.get();
    }

    private boolean isHealthyElytra(ItemStack stack) {
        if (stack == null || stack.isEmpty() || !stack.isOf(Items.ELYTRA)) return false;
        int remaining = stack.getMaxDamage() - stack.getDamage();
        return remaining >= minElytraDurability.get();
    }

    private int findBestElytraSlot() {
        int bestSlot = -1;
        int bestRemain = -1;
        for (int i = 0; i < mc.player.getInventory().size(); i++) {
            ItemStack s = mc.player.getInventory().getStack(i);
            if (s.isOf(Items.ELYTRA)) {
                int remain = s.getMaxDamage() - s.getDamage();
                if (remain >= minElytraDurability.get() && remain > bestRemain) {
                    bestRemain = remain;
                    bestSlot = i;
                }
            }
        }
        return bestSlot;
    }

    private void maybeReplaceElytra() {
        ItemStack chest = mc.player.getInventory().getArmorStack(2);
        if (isHealthyElytra(chest)) return;
        int slot = findBestElytraSlot();
        if (slot == -1) return;
        InvUtils.move().from(slot).toArmor(2);
        reopenTicks = 3; // Give a few ticks to re-open gliding
    }

    private void tryStartFallFlying() {
        sendStartFlyingPacket();
    }

    private void maintainGoldArmor() {
        if (mc.player == null) return;
        List<Item> targets = goldPieces.get();
        if (targets == null || targets.isEmpty()) return;

        for (Item it : targets) {
            int armorIdx = armorSlotIndexFor(it);
            if (armorIdx == -1) continue;

            int invArmorSlot = (armorIdx == 3 ? 39 : armorIdx == 2 ? 38 : armorIdx == 1 ? 37 : 36);
            ItemStack equipped = mc.player.getInventory().getStack(invArmorSlot);

            boolean ok = equipped != null && !equipped.isEmpty() && equipped.isOf(it)
                && remainingDurability(equipped) >= minGoldDurability.get();
            if (ok) continue;

            int best = findBestGoldSlot(it);
            if (best == -1) continue;

            InvUtils.move().from(best).toArmor(armorIdx);
        }
    }

    private int armorSlotIndexFor(Item it) {
        if (it == Items.GOLDEN_HELMET) return 3;
        if (it == Items.GOLDEN_LEGGINGS) return 1;
        if (it == Items.GOLDEN_BOOTS) return 0;
        return -1;
    }

    private int findBestGoldSlot(Item it) {
        int best = -1, bestRemain = -1;
        for (int i = 0; i < mc.player.getInventory().size(); i++) {
            ItemStack s = mc.player.getInventory().getStack(i);
            if (s.isOf(it)) {
                int r = remainingDurability(s);
                if (r >= minGoldDurability.get() && r > bestRemain) {
                    bestRemain = r;
                    best = i;
                }
            }
        }
        return best;
    }

    private int remainingDurability(ItemStack s) {
        return s.getMaxDamage() - s.getDamage();
    }

    private void maybeSwapBackChestplate() {
        int best = findBestChestplateSlot();
        if (best != -1) InvUtils.move().from(best).toArmor(2);
    }

    private int findBestChestplateSlot() {
        int best = -1, bestTier = -1, bestRemain = -1;
        for (int i = 0; i < mc.player.getInventory().size(); i++) {
            ItemStack s = mc.player.getInventory().getStack(i);
            if (s.isEmpty()) continue;
            int tier = chestplateTier(s.getItem());
            if (tier < 0) continue;
            int r = remainingDurability(s);
            if (tier > bestTier || (tier == bestTier && r > bestRemain)) {
                bestTier = tier;
                bestRemain = r;
                best = i;
            }
        }
        return best;
    }

    private int chestplateTier(Item it) {
        if (it == Items.NETHERITE_CHESTPLATE) return 5;
        if (it == Items.DIAMOND_CHESTPLATE) return 4;
        if (it == Items.IRON_CHESTPLATE) return 3;
        if (it == Items.CHAINMAIL_CHESTPLATE) return 2;
        if (it == Items.GOLDEN_CHESTPLATE) return 1;
        if (it == Items.LEATHER_CHESTPLATE) return 0;
        return -1;
    }

    private void maybeSwapBackLeggings() {
        ItemStack legs = mc.player.getInventory().getArmorStack(1);
        boolean wearingGoldOrEmpty = legs == null || legs.isEmpty() || legs.isOf(Items.GOLDEN_LEGGINGS);
        if (!wearingGoldOrEmpty) return;
        int best = findBestNonGoldLeggingsSlot();
        if (best != -1) InvUtils.move().from(best).toArmor(1);
    }

    private int findBestNonGoldLeggingsSlot() {
        Item[][] tiers = new Item[][]{
            {Items.NETHERITE_LEGGINGS},
            {Items.DIAMOND_LEGGINGS},
            {Items.IRON_LEGGINGS},
            {Items.CHAINMAIL_LEGGINGS},
            {Items.LEATHER_LEGGINGS}
        };
        for (Item[] tier : tiers) {
            int best = -1, bestRemain = -1;
            for (int i = 0; i < mc.player.getInventory().size(); i++) {
                ItemStack s = mc.player.getInventory().getStack(i);
                if (s.isEmpty()) continue;
                if (s.isOf(Items.GOLDEN_LEGGINGS)) continue;
                for (Item it : tier) {
                    if (s.isOf(it)) {
                        int r = remainingDurability(s);
                        if (r > bestRemain) {
                            bestRemain = r;
                            best = i;
                        }
                        break;
                    }
                }
            }
            if (best != -1) return best;
        }
        return -1;
    }
}
