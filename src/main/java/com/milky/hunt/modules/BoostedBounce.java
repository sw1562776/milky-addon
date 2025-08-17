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
import net.minecraft.block.Blocks;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.entity.MovementType;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.network.packet.c2s.play.ClickSlotC2SPacket;
import net.minecraft.network.packet.c2s.play.ClientCommandC2SPacket;
import net.minecraft.network.packet.s2c.play.CloseScreenS2CPacket;
import net.minecraft.network.packet.s2c.play.PlayerPositionLookS2CPacket;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.util.math.Vec3d;

import com.milky.hunt.Addon;

import java.util.List;

import static com.milky.hunt.Utils.*;

public class BoostedBounce extends Module {

    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgObstaclePasser = settings.createGroup("Obstacle Passer");

    private final Setting<Boolean> bounce = sgGeneral.add(new BoolSetting.Builder()
        .name("Bounce")
        .description("Automatically does bounce efly.")
        .defaultValue(false)
        .build()
    );

    private final Setting<Boolean> motionYBoost = sgGeneral.add(new BoolSetting.Builder()
        .name("Motion Y Boost")
        .description("Greatly increases speed by cancelling Y momentum.")
        .defaultValue(false)
        .visible(bounce::get)
        .build()
    );

    private final Setting<Boolean> onlyWhileColliding = sgGeneral.add(new BoolSetting.Builder()
        .name("Only While Colliding")
        .description("Only enables motion y boost if colliding with a wall.")
        .defaultValue(true)
        .visible(() -> bounce.get() && motionYBoost.get())
        .build()
    );

    private final Setting<Boolean> tunnelBounce = sgGeneral.add(new BoolSetting.Builder()
        .name("Tunnel Bounce")
        .description("Allows you to bounce in 1x2 tunnels. This should not be on if you are not in a tunnel.")
        .defaultValue(false)
        .visible(() -> bounce.get() && motionYBoost.get())
        .build()
    );

    private final Setting<Double> speed = sgGeneral.add(new DoubleSetting.Builder()
        .name("Speed")
        .description("The speed in blocks per second to keep you at.")
        .defaultValue(100.0)
        .sliderRange(20, 250)
        .visible(() -> bounce.get() && motionYBoost.get())
        .build()
    );

    private final Setting<Boolean> lockPitch = sgGeneral.add(new BoolSetting.Builder()
        .name("Lock Pitch")
        .description("Whether to lock your pitch when bounce is enabled.")
        .defaultValue(true)
        .visible(bounce::get)
        .build()
    );

    private final Setting<Double> pitch = sgGeneral.add(new DoubleSetting.Builder()
        .name("Pitch")
        .description("The pitch to set when bounce is enabled.")
        .defaultValue(90.0)
        .sliderRange(-90, 90)
        .visible(() -> bounce.get() && lockPitch.get())
        .build()
    );

    private final Setting<Boolean> lockYaw = sgGeneral.add(new BoolSetting.Builder()
        .name("Lock Yaw")
        .description("Whether to lock your yaw when bounce is enabled.")
        .defaultValue(false)
        .visible(bounce::get)
        .build()
    );

    private final Setting<Boolean> useCustomYaw = sgGeneral.add(new BoolSetting.Builder()
        .name("Use Custom Yaw")
        .description("Enable this if you want to use a yaw that isn't a factor of 45. WARNING: This affects the baritone goal for obstacle passer; " +
            "use the default Rotations module if you only want a different yawlock.")
        .defaultValue(false)
        .visible(bounce::get)
        .build()
    );

    private final Setting<Double> yaw = sgGeneral.add(new DoubleSetting.Builder()
        .name("Yaw")
        .description("The yaw to set when bounce is enabled. This is auto set to the closest 45° angle unless Use Custom Yaw is enabled. " +
            "WARNING: This affects the baritone goal for obstacle passer; use the default Rotations module if you only want a different yawlock.")
        .defaultValue(0.0)
        .sliderRange(0, 359)
        .visible(() -> bounce.get() && useCustomYaw.get())
        .build()
    );

    private final Setting<Boolean> highwayObstaclePasser = sgObstaclePasser.add(new BoolSetting.Builder()
        .name("Highway Obstacle Passer")
        .description("Uses baritone to pass obstacles.")
        .defaultValue(false)
        .visible(bounce::get)
        .build()
    );

    private final Setting<Boolean> useCustomStartPos = sgObstaclePasser.add(new BoolSetting.Builder()
        .name("Use Custom Start Position")
        .description("Enable and set this ONLY if you are on a ringroad or don't want to be locked to a highway. Otherwise (0, 0) is used.")
        .defaultValue(false)
        .visible(() -> bounce.get() && highwayObstaclePasser.get())
        .build()
    );

    private final Setting<BlockPos> startPos = sgObstaclePasser.add(new BlockPosSetting.Builder()
        .name("Start Position")
        .description("The start position to use when using a custom start position.")
        .defaultValue(new BlockPos(0, 0, 0))
        .visible(() -> bounce.get() && highwayObstaclePasser.get() && useCustomStartPos.get())
        .build()
    );

    private final Setting<Boolean> awayFromStartPos = sgObstaclePasser.add(new BoolSetting.Builder()
        .name("Away From Start Position")
        .description("If true, goes away from the start position instead of towards it. Default start pos is (0,0) unless custom.")
        .defaultValue(true)
        .visible(() -> bounce.get() && highwayObstaclePasser.get())
        .build()
    );

    private final Setting<Double> distance = sgObstaclePasser.add(new DoubleSetting.Builder()
        .name("Distance")
        .description("The distance to set the baritone goal for path realignment.")
        .defaultValue(10.0)
        .visible(() -> bounce.get() && highwayObstaclePasser.get())
        .build()
    );

    private final Setting<Integer> targetY = sgObstaclePasser.add(new IntSetting.Builder()
        .name("Y Level")
        .description("The Y level to bounce at. This must be correct or bounce will not start properly.")
        .defaultValue(120)
        .visible(() -> bounce.get() && highwayObstaclePasser.get())
        .build()
    );

    private final Setting<Boolean> avoidPortalTraps = sgObstaclePasser.add(new BoolSetting.Builder()
        .name("Avoid Portal Traps")
        .description("Attempts to detect portal traps on chunk load and avoid them.")
        .defaultValue(false)
        .visible(() -> bounce.get() && highwayObstaclePasser.get())
        .build()
    );

    private final Setting<Double> portalAvoidDistance = sgObstaclePasser.add(new DoubleSetting.Builder()
        .name("Portal Avoid Distance")
        .description("Distance to a portal trap where the obstacle passer will takeover and go around it.")
        .defaultValue(20)
        .min(0)
        .sliderMax(50)
        .visible(() -> bounce.get() && highwayObstaclePasser.get() && avoidPortalTraps.get())
        .build()
    );

    private final Setting<Integer> portalScanWidth = sgObstaclePasser.add(new IntSetting.Builder()
        .name("Portal Scan Width")
        .description("Width along the highway axis to scan for portal traps.")
        .defaultValue(5)
        .min(3)
        .sliderMax(10)
        .visible(() -> bounce.get() && highwayObstaclePasser.get() && avoidPortalTraps.get())
        .build()
    );

    private final Setting<Boolean> fakeFly = sgGeneral.add(new BoolSetting.Builder()
        .name("Chestplate / Fakefly")
        .description("Fly using a chestplate to use almost 0 elytra durability. Must have elytra in hotbar.")
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

    public BoostedBounce() {
        super(
            Addon.CATEGORY,
            "BoostedBounce",
            "Elytra fly with bounce/obstacle-pass features (1.21.4 port)."
        );
    }

    private boolean startSprinting;
    private BlockPos portalTrap = null;
    private boolean paused = false;

    private boolean elytraToggled = false;

    private Vec3d lastUnstuckPos;
    private int stuckTimer = 0;

    @EventHandler
    private void onReceivePacket(PacketEvent.Receive event) {
        if (event.packet instanceof PlayerPositionLookS2CPacket) {
            // onActivate();
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

        if (bounce.get() && mc.player.getPos().multiply(1, 0, 1).length() >= 100) {
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
                    if (!awayFromStartPos.get()) {
                        angleNormalized += 180;
                    }
                    yaw.set(angleNormalized);
                }
            }
        }
    }

    private Vec3d lastPos;

    @EventHandler
    private void onPlayerMove(PlayerMoveEvent event) {
        if (mc.player == null || event.type != MovementType.SELF || !enabled() || !motionYBoost.get() || !bounce.get()) return;
        if (onlyWhileColliding.get() && !mc.player.horizontalCollision) return;

        if (lastPos != null) {
            double speedBps = mc.player.getPos().subtract(lastPos).multiply(20, 0, 20).length();

            Timer timer = Modules.get().get(Timer.class);
            if (timer.isActive()) {
                speedBps *= timer.getMultiplier();
            }

            if (mc.player.isOnGround() && mc.player.isSprinting() && speedBps < speed.get()) {
                if (speedBps > 20 || tunnelBounce.get()) {
                    ((IVec3d) event.movement).meteor$setY(0);
                }
                mc.player.setVelocity(mc.player.getVelocity().x, 0.0, mc.player.getVelocity().z);
            }
        }

        lastPos = mc.player.getPos();
    }

    @Override
    public void onDeactivate() {
        if (mc.player == null) return;

        if (bounce.get()) {
            if (BaritoneAPI.getProvider().getPrimaryBaritone().getElytraProcess().currentDestination() == null) {
                BaritoneAPI.getProvider().getPrimaryBaritone().getCustomGoalProcess().setGoal(null);
            }
        }

        mc.player.setSprinting(startSprinting);

        if (toggleElytra.get() && !fakeFly.get()) {
            if (!mc.player.getEquippedStack(EquipmentSlot.CHEST).getItem().toString().contains("chestplate")) {
                Modules.get().get(ChestSwap.class).swap();
            }
        }
    }

    // 5 chunks forwards
    private final double maxDistance = 16 * 5;

    // temp path when no valid blocks in range
    private BlockPos tempPath = null;

    private boolean waitingForChunksToLoad;

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

        if (bounce.get()) {
            if (tempPath != null && mc.player.getBlockPos().getSquaredDistance(tempPath) < 500) {
                tempPath = null;
                BaritoneAPI.getProvider().getPrimaryBaritone().getCustomGoalProcess().setGoal(null);
            } else if (tempPath != null) {
                BaritoneAPI.getProvider().getPrimaryBaritone().getCustomGoalProcess().setGoalAndPath(new GoalBlock(tempPath));
                return;
            }

            // if still pathing, wait for completion
            if (highwayObstaclePasser.get() && BaritoneAPI.getProvider().getPrimaryBaritone().getCustomGoalProcess().getGoal() != null) {
                return;
            }

            if (mc.player.squaredDistanceTo(lastUnstuckPos) < 25) {
                stuckTimer++;
            } else {
                stuckTimer = 0;
                lastUnstuckPos = mc.player.getPos();
            }

            if (highwayObstaclePasser.get() && mc.player.getPos().length() > 100 &&
                (mc.player.getY() < targetY.get() || mc.player.getY() > targetY.get() + 2 ||
                 (mc.player.horizontalCollision && !mc.player.collidedSoftly) ||
                 (portalTrap != null && portalTrap.getSquaredDistance(mc.player.getBlockPos()) < portalAvoidDistance.get() * portalAvoidDistance.get()) ||
                 waitingForChunksToLoad ||
                 stuckTimer > 50)) {

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
                    Vec3d unitYawVec = yawToDirection(yaw.get());
                    Vec3d travelVec = mc.player.getPos().subtract(startPos.get().toCenterPos());

                    double parallelCurrPosDot = travelVec.multiply(new Vec3d(1, 0, 1)).dotProduct(unitYawVec);
                    Vec3d parallelCurrPosComponent = unitYawVec.multiply(parallelCurrPosDot);

                    Vec3d pos = startPos.get().toCenterPos().add(parallelCurrPosComponent);
                    pos = positionInDirection(pos, yaw.get(), currDistance);

                    goal = new BlockPos((int) (Math.floor(pos.x)), targetY.get(), (int) Math.floor(pos.z));
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
                // keep jumping
                paused = false;
                if (!enabled()) return;

                if (!fakeFly.get()) {
                    if (mc.player.isOnGround() && (!motionYBoost.get() || Utils.getPlayerSpeed().multiply(1, 0, 1).length() < speed.get())) {
                        mc.player.jump();
                    }
                }

                // set yaw and pitch
                if (lockYaw.get()) {
                    mc.player.setYaw(yaw.get().floatValue());
                }
                if (lockPitch.get()) {
                    mc.player.setPitch(pitch.get().floatValue());
                }
            }
        }

        if (enabled()) {
            if (fakeFly.get()) {
                doGrimEflyStuff();
            } else {
                sendStartFlyingPacket();
            }
        }
    }

    public boolean enabled() {
        return this.isActive() && !paused && mc.player != null &&
            (fakeFly.get() || mc.player.getEquippedStack(EquipmentSlot.CHEST).getItem().equals(Items.ELYTRA));
    }

    private void doGrimEflyStuff() {
        FindItemResult itemResult = InvUtils.findInHotbar(Items.ELYTRA);
        if (!itemResult.found()) return;

        swapToItem(itemResult.slot());
        sendStartFlyingPacket();

        if (bounce.get() && mc.player.isOnGround() &&
            (!motionYBoost.get() || Utils.getPlayerSpeed().multiply(1, 0, 1).length() < speed.get())) {
            mc.player.jump();
        }

        swapToItem(itemResult.slot());
    }

    @EventHandler
    private void onPlaySound(PlaySoundEvent event) {
        if (!fakeFly.get()) return;
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

    // 38 is the Meteor inventory index for chestplate (PlayerInventory)
    // Server-side slot indices (PlayerScreenHandler): chest armor slot = 6; hotbar = 36..44
    private void swapToItem(int slot) {
        ItemStack chestItem = mc.player.getInventory().getStack(38);
        ItemStack hotbarSwapItem = mc.player.getInventory().getStack(slot);

        Int2ObjectMap<ItemStack> changedSlots = new Int2ObjectOpenHashMap<>();
        changedSlots.put(6, hotbarSwapItem.copy());
        changedSlots.put(slot + 36, chestItem.copy());

        sendSwapPacket(changedSlots, (byte) slot);
    }

    private void sendStartFlyingPacket() {
        if (mc.player == null) return;
        mc.player.networkHandler.sendPacket(new ClientCommandC2SPacket(
            mc.player,
            ClientCommandC2SPacket.Mode.START_FALL_FLYING
        ));
    }

    private void sendSwapPacket(Int2ObjectMap<ItemStack> changedSlots, byte buttonNum) {
        int syncId  = mc.player.currentScreenHandler.syncId;
        int stateId = mc.player.currentScreenHandler.getRevision();

        mc.player.networkHandler.sendPacket(new ClickSlotC2SPacket(
            syncId,
            stateId,
            (short) 6,
            buttonNum,
            SlotActionType.SWAP,
            ItemStack.EMPTY,
            changedSlots
        ));
    }

    @EventHandler
    private void onChunkData(ChunkDataEvent event) {
        if (!avoidPortalTraps.get() || !highwayObstaclePasser.get()) return;
        ChunkPos pos = event.chunk().getPos();

        BlockPos centerPos = pos.getCenterAtY(targetY.get());

        // Check if chunk is on the player's path
        Vec3d moveDir = yawToDirection(yaw.get());
        double distanceToHighway = distancePointToDirection(Vec3d.of(centerPos), moveDir, mc.player.getPos());

        if (distanceToHighway > 21) return;

        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                for (int y = targetY.get(); y < targetY.get() + 3; y++) {
                    BlockPos position = new BlockPos(pos.x * 16 + x, y, pos.z * 16 + z);

                    if (distancePointToDirection(Vec3d.of(position), moveDir, mc.player.getPos()) > portalScanWidth.get()) continue;

                    if (mc.world.getBlockState(position).getBlock().equals(Blocks.NETHER_PORTAL)) {
                        BlockPos posBehind = new BlockPos(
                            (int) Math.floor(position.getX() + moveDir.x),
                            position.getY(),
                            (int) Math.floor(position.getZ() + moveDir.z)
                        );

                        // Trap detected when a portal has a solid block or another portal behind it
                        if (mc.world.getBlockState(posBehind).isSolidBlock(mc.world, posBehind) ||
                            mc.world.getBlockState(posBehind).getBlock() == Blocks.NETHER_PORTAL) {
                            if (portalTrap == null || (
                                portalTrap.getSquaredDistance(posBehind) > 100 &&
                                mc.player.getBlockPos().getSquaredDistance(posBehind) <
                                    mc.player.getBlockPos().getSquaredDistance(portalTrap))) {
                                portalTrap = posBehind;
                            }
                        }
                    }
                }
            }
        }
    }
}
