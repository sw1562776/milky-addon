package com.milky.hunt.modules;

import com.milky.hunt.Addon;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.player.FindItemResult;
import meteordevelopment.meteorclient.utils.player.InvUtils;
import meteordevelopment.meteorclient.utils.world.BlockUtils;
import meteordevelopment.orbit.EventHandler;
import meteordevelopment.orbit.EventPriority;
import net.minecraft.block.EnderChestBlock;
import net.minecraft.block.ShulkerBoxBlock;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import net.minecraft.entity.ItemEntity;
import net.minecraft.item.BlockItem;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.network.packet.c2s.play.PlayerActionC2SPacket;
import net.minecraft.network.packet.c2s.play.UpdateSelectedSlotC2SPacket;
import net.minecraft.screen.GenericContainerScreenHandler;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.screen.ShulkerBoxScreenHandler;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;

public class PitStop extends Module {
    private final SettingGroup sg = settings.getDefaultGroup();

    private final Setting<Integer> delayTicks = sg.add(new IntSetting.Builder()
        .name("delay-ticks")
        .description("Ticks to wait between staged actions.")
        .defaultValue(5)
        .min(0)
        .build());

    private final Setting<Integer> range = sg.add(new IntSetting.Builder()
        .name("range")
        .description("Radius to search/place Ender Chest and shulker (legacy, kept for compatibility).")
        .defaultValue(3)
        .min(1)
        .sliderRange(1, 6)
        .build());

    private final Setting<Integer> pickupWait = sg.add(new IntSetting.Builder()
        .name("pickup-wait-ticks")
        .description("Extra ticks to wait when standing on drops.")
        .defaultValue(6)
        .min(0)
        .sliderRange(0, 20)
        .build());

    private final Setting<Integer> preferredSlot = sg.add(new IntSetting.Builder()
        .name("preferred-hotbar-slot")
        .description("Selected before holding blocks to avoid conflicts.")
        .defaultValue(2)
        .min(0)
        .sliderRange(0, 8)
        .build());

    private final Setting<String> boxName = sg.add(new StringSetting.Builder()
        .name("elytra-shulker-name")
        .description("Shulker display name for Elytra.")
        .defaultValue("Milky Elytra")
        .build());

    private final Setting<Integer> threshold = sg.add(new IntSetting.Builder()
        .name("elytra-durability-threshold")
        .description("Elytras with remaining durability below this are considered damaged.")
        .defaultValue(10)
        .min(1)
        .sliderRange(1, 432)
        .build());

    private final Setting<Item> item1 = sg.add(new ItemSetting.Builder()
        .name("restock-item-1")
        .description("First item to restock.")
        .defaultValue(Items.FIREWORK_ROCKET)
        .build());

    private final Setting<Integer> item1Target = sg.add(new IntSetting.Builder()
        .name("restock-item-1-target")
        .description("Desired count for item 1 (0 = disabled).")
        .defaultValue(512)
        .min(0)
        .sliderRange(0, 2304)
        .build());

    private final Setting<String> item1BoxName = sg.add(new StringSetting.Builder()
        .name("restock-item-1-shulker-name")
        .description("Shulker display name for item 1.")
        .defaultValue("Milky Rocket")
        .build());

    private final Setting<Item> item2 = sg.add(new ItemSetting.Builder()
        .name("restock-item-2")
        .description("Second item to restock.")
        .defaultValue(Items.NETHERRACK)
        .build());

    private final Setting<Integer> item2Target = sg.add(new IntSetting.Builder()
        .name("restock-item-2-target")
        .description("Desired count for item 2 (0 = disabled).")
        .defaultValue(64)
        .min(0)
        .sliderRange(0, 2304)
        .build());

    private final Setting<String> item2BoxName = sg.add(new StringSetting.Builder()
        .name("restock-item-2-shulker-name")
        .description("Shulker display name for item 2.")
        .defaultValue("Milky Netherrack")
        .build());

    private final Setting<Boolean> pickEnderChestAfter = sg.add(new BoolSetting.Builder()
        .name("pick-ender-chest-after")
        .description("Mine & pick up the Ender Chest when done.")
        .defaultValue(true)
        .build());

    private enum Stage {
        IDLE,
        UNEQUIP_ELYTRA,
        ANALYZE,
        FIND_OR_PLACE_EC, OPEN_EC, TAKE_SHULKER,
        PLACE_SHULKER, OPEN_SHULKER,
        SWAP, RESTOCK_ITEM1, RESTOCK_ITEM2,
        CLOSE_SHULKER,
        MINING,
        VERIFY_MINE,
        POST_MINE_KIT, WALK_TO_KIT_DROP,
        WALK_HOME, CENTER_HOME,
        CENTER_FOR_PLACE,
        OPEN_EC_AGAIN, RETURN_SHULKER,
        CHECK_DONE,
        START_MINE_EC, POST_MINE_EC, WALK_TO_EC_DROP,
        DONE, FAIL
    }

    private enum Flow { Elytra, Item1, Item2 }

    private Stage stage = Stage.IDLE;
    private Flow flow = Flow.Elytra;
    private int wait = 0;

    // Ender chest and shulker positions (world)
    private BlockPos ecPos = null;
    private BlockPos kitPos = null;

    // Mining state
    private boolean miningActive = false;
    private BlockPos miningPos = null;
    private Stage miningNext = Stage.DONE;
    private FindItemResult miningPick = null;

    // 2x2 platform / home
    private BlockPos homeFeet = null;             // air block the player stands in
    private Vec3d homeCenter = null;             // center of homeFeet (x+0.5, z+0.5)
    private BlockPos[] platform = null;          // 4 air blocks at y = homeFeet.y (placement plane), size 4
    private Stage afterHome = Stage.DONE;

    // Chosen placement cells on platform
    private BlockPos ecPlace = null;
    private BlockPos shulkerPlace = null;

    // Centering tolerance on home block (general returning)
    private static final double CENTER_EPS = 0.12;

    // Placement centering (AirLanding-like), requested eps=0.09
    private static final double PLACE_CENTER_EPS = 0.09;
    private static final int PLACE_CENTER_MAX_TICKS = 30;
    private int placeCenterTicks = 0;
    private Stage placeCenterAfter = Stage.FIND_OR_PLACE_EC;

    // Mining verification (2b2t lag / rubberband protection)
    private static final int MINE_VERIFY_TICKS = 6;
    private int mineVerify = 0;

    // Inventory slot range in playerScreenHandler (per your screenshot)
    // 9-35: main inventory, 36-44: hotbar
    private static final int INV_SLOT_START = 9;
    private static final int INV_SLOT_END = 44;

    // Ensure UNEQUIP stage is robust under lag/rubberband
    private static final int UNEQUIP_MAX_ATTEMPTS = 12;
    private int unequipAttempts = 0;
    private int unequipCooldown = 0;

    // EnderChest pickup should NOT be "found()" (you might already have some).
    // Arm a target count before mining EC, and only proceed when count reaches target.
    private boolean ecPickupArmed = false;
    private int ecPickupTargetCount = -1;

    // New: iterate through multiple name-matching shulkers in Ender Chest (per-flow), and return to original slot.
    private final boolean[][] triedShulkerSlots = new boolean[Flow.values().length][27];
    private int currentEcSlot = -1;        // 0..26 in EC container
    private Flow currentEcFlow = null;     // which flow the currentEcSlot belongs to

    public PitStop() {
        super(Addon.MilkyModCategory, "PitStop",
            "Swap low-durability Elytras using named shulkers from an Ender Chest and restock two extra items (2x2 platform aware).");
    }

    @Override
    public void onActivate() {
        stage = Stage.UNEQUIP_ELYTRA;
        flow = Flow.Elytra;
        wait = 0;

        ecPos = null;
        kitPos = null;

        miningActive = false;
        miningPos = null;
        miningNext = Stage.DONE;

        homeFeet = null;
        homeCenter = null;
        platform = null;
        afterHome = Stage.DONE;

        ecPlace = null;
        shulkerPlace = null;

        placeCenterTicks = 0;
        placeCenterAfter = Stage.FIND_OR_PLACE_EC;

        unequipAttempts = 0;
        unequipCooldown = 0;

        ecPickupArmed = false;
        ecPickupTargetCount = -1;

        // Reset tried markers.
        for (int f = 0; f < triedShulkerSlots.length; f++) {
            for (int i = 0; i < 27; i++) triedShulkerSlots[f][i] = false;
        }
        currentEcSlot = -1;
        currentEcFlow = null;
    }

    @Override
    public void onDeactivate() {
        stopAllMovement();
        stage = Stage.IDLE;
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    private void onTick(TickEvent.Pre e) {
        if (!isActive() || mc.player == null || mc.world == null) return;

        if (wait > 0) { wait--; return; }

        try {
            switch (stage) {
                case UNEQUIP_ELYTRA -> unequipWornElytraThenAnalyze();
                case ANALYZE -> analyze();
                case FIND_OR_PLACE_EC -> findOrPlaceEnderChestOnPlatform();
                case OPEN_EC -> openBlockAt(ecPos, Stage.TAKE_SHULKER, Stage.FIND_OR_PLACE_EC);
                case TAKE_SHULKER -> takeShulkerFromEC();
                case PLACE_SHULKER -> placeShulkerOnPlatform();
                case OPEN_SHULKER -> {
                    if (flow == Flow.Elytra) openBlockAt(kitPos, Stage.SWAP, Stage.PLACE_SHULKER);
                    else if (flow == Flow.Item1) openBlockAt(kitPos, Stage.RESTOCK_ITEM1, Stage.PLACE_SHULKER);
                    else openBlockAt(kitPos, Stage.RESTOCK_ITEM2, Stage.PLACE_SHULKER);
                }
                case SWAP -> swapInKit();
                case RESTOCK_ITEM1 -> restockInKit(item1.get(), item1Target.get());
                case RESTOCK_ITEM2 -> restockInKit(item2.get(), item2Target.get());
                case CLOSE_SHULKER -> closeContainerThen(Stage.MINING);
                case MINING -> continueMining();
                case VERIFY_MINE -> verifyMining();
                case POST_MINE_KIT -> postMineKit();
                case WALK_TO_KIT_DROP -> walkToDrop(true);
                case WALK_HOME -> walkHome();
                case CENTER_HOME -> centerHome();
                case CENTER_FOR_PLACE -> centerForPlace();
                case OPEN_EC_AGAIN -> openBlockAt(ecPos, Stage.RETURN_SHULKER, Stage.FIND_OR_PLACE_EC);
                case RETURN_SHULKER -> returnShulkerToEC();
                case CHECK_DONE -> checkDoneOrNext();
                case START_MINE_EC -> startMining(ecPos, Stage.POST_MINE_EC);
                case POST_MINE_EC -> postMineEc();
                case WALK_TO_EC_DROP -> walkToDrop(false);
                case DONE, FAIL -> toggle();
                default -> {}
            }
        } catch (Exception ex) {
            error("PitStop error: " + ex.getMessage());
            stage = Stage.FAIL;
        }
    }

    /* =========================
     *  New: always unequip worn Elytra first (robust under lag)
     * ========================= */

    private static final int ARMOR_CHEST_INDEX = 2;

    private void unequipWornElytraThenAnalyze() {
        if (mc.player == null) { stage = Stage.FAIL; return; }

        if (unequipCooldown > 0) {
            unequipCooldown--;
            wait = 0;
            return;
        }

        // If wearing Elytra, move it to hotbar/inventory first so swap logic can see it.
        ItemStack chest = mc.player.getInventory().getArmorStack(ARMOR_CHEST_INDEX);
        if (chest != null && !chest.isEmpty() && chest.isOf(Items.ELYTRA)) {
            if (unequipAttempts >= UNEQUIP_MAX_ATTEMPTS) {
                error("PitStop: failed to unequip Elytra (no space / lag / rollback?).");
                stage = Stage.FAIL;
                return;
            }

            int target = findEmptyHotbarSlot();
            if (target < 0) target = getFreeHotbarSlotExcluding(preferredSlot.get());

            // Attempt move; under lag/rubberband this might not apply immediately.
            InvUtils.move().fromArmor(ARMOR_CHEST_INDEX).toHotbar(target);

            unequipAttempts++;
            unequipCooldown = 1; // give the server a moment
            wait = 1;
            return; // stay in UNEQUIP_ELYTRA until armor slot is truly cleared
        }

        // Armor slot is clear (or not Elytra) -> proceed
        unequipAttempts = 0;
        stage = Stage.ANALYZE;
        wait = 1;
    }

    private int findEmptyHotbarSlot() {
        if (mc.player == null) return -1;
        for (int i = 0; i < 9; i++) {
            if (mc.player.getInventory().getStack(i).isEmpty()) return i;
        }
        return -1;
    }

    /* =========================
     *  Stage logic
     * ========================= */

    private void analyze() {
        // Establish home and platform once.
        if (homeFeet == null) {
            homeFeet = mc.player.getBlockPos();
            homeCenter = Vec3d.ofCenter(homeFeet);
            platform = detect2x2Platform(homeFeet);
            if (platform == null) {
                error("PitStop: cannot detect 2x2 platform under/around you. Stand on the 2x2 floor first.");
                stage = Stage.FAIL;
                return;
            }
        }

        // Decide first needed flow. If nothing needed, do nothing (no EC open, no shulker).
        Flow next = nextNeededFlow();
        if (next == null) {
            stage = Stage.DONE;
            return;
        }

        flow = next;
        stage = Stage.FIND_OR_PLACE_EC;
    }

    private Flow nextNeededFlow() {
        if (needsElytraSwap()) return Flow.Elytra;
        if (needsItem1()) return Flow.Item1;
        if (needsItem2()) return Flow.Item2;
        return null;
    }

    private boolean needsElytraSwap() {
        return countDamagedInInventory() > 0;
    }

    /**
     * Find an Ender Chest already placed ON the 2x2 platform plane; otherwise place one on the
     * farthest platform cell from home (excluding the player's home cell).
     */
    private void findOrPlaceEnderChestOnPlatform() {
        if (!ensurePlatformReady()) { stage = Stage.FAIL; return; }

        // If we already have a valid EC on platform, just open it.
        BlockPos existing = findExistingEnderChestOnPlatform();
        if (existing != null) {
            ecPos = existing;
            stage = Stage.OPEN_EC;
            wait = delayTicks.get();
            return;
        }

        // Choose farthest cell (diagonal) excluding homeFeet.
        ecPlace = chooseFarthestPlatformCellExcluding(homeFeet, null);
        if (ecPlace == null) {
            error("PitStop: no valid platform cell to place Ender Chest.");
            stage = Stage.FAIL;
            return;
        }

        if (!mc.world.isAir(ecPlace)) {
            error("PitStop: platform cell for Ender Chest is not empty.");
            stage = Stage.FAIL;
            return;
        }

        FindItemResult ec = InvUtils.find(Items.ENDER_CHEST);
        if (!ec.found()) {
            error("PitStop: no Ender Chest in inventory.");
            stage = Stage.FAIL;
            return;
        }

        // New: AirLanding-like centering before placement (eps=0.09).
        if (!isCenteredOnHome(PLACE_CENTER_EPS)) {
            enterPlaceCentering(Stage.FIND_OR_PLACE_EC);
            return;
        }

        selectPreferredHotbarSlot();
        if (!bringToSelectedViaSwap(ec)) {
            error("PitStop: unable to hold Ender Chest.");
            stage = Stage.FAIL;
            return;
        }

        boolean placed = placeOnFloorUp(ecPlace);
        if (!placed) placed = BlockUtils.place(ecPlace, new FindItemResult(mc.player.getInventory().selectedSlot, 1), true, 0, true);

        if (!placed) {
            error("PitStop: failed to place Ender Chest on platform.");
            stage = Stage.FAIL;
            return;
        }

        ecPos = ecPlace;
        stage = Stage.OPEN_EC;
        wait = delayTicks.get();
    }

    private void openBlockAt(BlockPos pos, Stage nextIfOpened, Stage fallbackIfMissing) {
        if (pos == null || mc.world.isAir(pos)) { stage = fallbackIfMissing; return; }
        lookAt(pos);
        BlockHitResult hit = new BlockHitResult(Vec3d.ofCenter(pos), Direction.UP, pos, false);
        mc.interactionManager.interactBlock(mc.player, Hand.MAIN_HAND, hit);
        mc.player.swingHand(Hand.MAIN_HAND);
        wait = delayTicks.get();
        stage = nextIfOpened;
    }

    private void takeShulkerFromEC() {
        // If this flow is no longer needed (e.g., counts changed), skip without doing anything.
        if (!isFlowStillNeeded()) {
            closeContainerThen(Stage.CHECK_DONE);
            return;
        }

        if (!(mc.player.currentScreenHandler instanceof GenericContainerScreenHandler gh)) {
            stage = Stage.OPEN_EC;
            return;
        }

        int found = findNextUntriedNamedShulkerSlot(gh);
        if (found == -1) {
            // If there is at least one matching shulker but all are tried -> hard fail (prevents infinite loops).
            if (hasAnyNamedShulker(gh)) {
                error("PitStop: all shulkers named \"" + activeBoxName() + "\" were tried, but you still need more items.");
            } else {
                error("PitStop: missing shulker \"" + activeBoxName() + "\" in Ender Chest.");
            }
            closeContainerThen(Stage.FAIL);
            return;
        }

        FindItemResult empty = InvUtils.findEmpty();
        if (!empty.found()) {
            error("PitStop: no empty slot for shulker.");
            closeContainerThen(Stage.FAIL);
            return;
        }

        // Remember where we took it from, so we can return it to the original slot, and so we can mark it as tried later.
        currentEcSlot = found;
        currentEcFlow = flow;

        InvUtils.move().fromId(found).to(empty.slot());
        wait = delayTicks.get();
        closeContainerThen(Stage.PLACE_SHULKER);
    }

    private int findNextUntriedNamedShulkerSlot(GenericContainerScreenHandler gh) {
        boolean[] tried = triedShulkerSlots[flow.ordinal()];
        for (int i = 0; i < 27; i++) {
            if (tried[i]) continue;
            ItemStack s = gh.getSlot(i).getStack();
            if (isNamedShulker(s)) return i;
        }
        return -1;
    }

    private boolean hasAnyNamedShulker(GenericContainerScreenHandler gh) {
        for (int i = 0; i < 27; i++) {
            ItemStack s = gh.getSlot(i).getStack();
            if (isNamedShulker(s)) return true;
        }
        return false;
    }

    private void markCurrentShulkerTriedIfNeeded() {
        if (currentEcFlow == null) return;
        if (currentEcSlot < 0 || currentEcSlot >= 27) return;
        triedShulkerSlots[currentEcFlow.ordinal()][currentEcSlot] = true;
    }

    private void placeShulkerOnPlatform() {
        if (!ensurePlatformReady()) { stage = Stage.FAIL; return; }

        FindItemResult kit = InvUtils.find(this::isNamedShulker);
        if (!kit.found()) {
            error("PitStop: shulker not found in inventory.");
            stage = Stage.FAIL;
            return;
        }

        // Choose "second farthest": farthest excluding homeFeet and ecPos.
        shulkerPlace = chooseFarthestPlatformCellExcluding(homeFeet, ecPos);
        if (shulkerPlace == null) {
            error("PitStop: no valid platform cell to place shulker.");
            stage = Stage.FAIL;
            return;
        }

        if (!mc.world.isAir(shulkerPlace)) {
            error("PitStop: platform cell for shulker is not empty.");
            stage = Stage.FAIL;
            return;
        }

        // New: AirLanding-like centering before placement (eps=0.09).
        if (!isCenteredOnHome(PLACE_CENTER_EPS)) {
            enterPlaceCentering(Stage.PLACE_SHULKER);
            return;
        }

        selectPreferredHotbarSlot();
        if (!bringToSelectedViaSwap(kit)) {
            error("PitStop: unable to hold Shulker.");
            stage = Stage.FAIL;
            return;
        }

        boolean placed = placeOnFloorUp(shulkerPlace);
        if (!placed) placed = BlockUtils.place(shulkerPlace, new FindItemResult(mc.player.getInventory().selectedSlot, 1), true, 0, true);

        if (!placed) {
            error("PitStop: failed to place shulker on platform.");
            stage = Stage.FAIL;
            return;
        }

        kitPos = shulkerPlace;
        stage = Stage.OPEN_SHULKER;
        wait = delayTicks.get();
    }

    private void swapInKit() {
        if (!(mc.player.currentScreenHandler instanceof ShulkerBoxScreenHandler sh)) {
            stage = Stage.OPEN_SHULKER;
            return;
        }

        // If we no longer need swap, just close and return shulker.
        if (!needsElytraSwap()) {
            stage = Stage.CLOSE_SHULKER;
            wait = Math.max(1, delayTicks.get());
            return;
        }

        for (int ci = 0; ci < 27; ci++) {
            ItemStack kitStack = sh.getSlot(ci).getStack();
            if (!(isElytra(kitStack) && !isBad(kitStack))) continue;

            int pv = findDamagedSlotInContainerView(sh);
            if (pv == -1) break;

            clickContainerSlot(sh, pv, SlotActionType.PICKUP);
            clickContainerSlot(sh, ci, SlotActionType.PICKUP);
            clickContainerSlot(sh, pv, SlotActionType.PICKUP);
        }

        stage = Stage.CLOSE_SHULKER;
        wait = Math.max(1, delayTicks.get());
    }

    private void restockInKit(Item want, int target) {
        if (!(mc.player.currentScreenHandler instanceof ShulkerBoxScreenHandler sh)) {
            stage = Stage.OPEN_SHULKER;
            return;
        }

        // If target disabled or already satisfied, close immediately.
        if (target <= 0 || countItemInInventory(want) >= target) {
            stage = Stage.CLOSE_SHULKER;
            wait = Math.max(1, delayTicks.get());
            return;
        }

        int have = countItemInInventory(want);
        for (int ci = 0; ci < 27 && have < target; ci++) {
            ItemStack s = sh.getSlot(ci).getStack();
            if (!s.isEmpty() && s.getItem() == want) {
                mc.interactionManager.clickSlot(sh.syncId, ci, 0, SlotActionType.QUICK_MOVE, mc.player);
                wait = 1;
                have = countItemInInventory(want);
            }
        }

        stage = Stage.CLOSE_SHULKER;
        wait = Math.max(1, delayTicks.get());
    }

    private void closeContainerThen(Stage next) {
        if (mc.currentScreen instanceof HandledScreen<?>) mc.player.closeHandledScreen();

        if (next == Stage.MINING) {
            wait = Math.max(wait, delayTicks.get());
            // Mine the placed shulker
            startMining(kitPos, Stage.POST_MINE_KIT);
        } else {
            stage = next;
            wait = delayTicks.get();
        }
    }

    private void startMining(BlockPos pos, Stage next) {
        if (pos == null) { stage = Stage.FAIL; return; }
        miningPos = pos;
        miningNext = next;
        miningActive = false;

        // Arm EC pickup target BEFORE we start mining it.
        if (next == Stage.POST_MINE_EC) {
            armEnderChestPickupTarget();
        }

        stage = Stage.MINING;
    }

    private void armEnderChestPickupTarget() {
        int now = countItemInInventory(Items.ENDER_CHEST);
        ecPickupTargetCount = now + 1;
        ecPickupArmed = true;
    }

    private void disarmEnderChestPickupTarget() {
        ecPickupArmed = false;
        ecPickupTargetCount = -1;
    }

    private void continueMining() {
        if (miningPos == null) { stage = Stage.FAIL; return; }

        if (mc.world.isAir(miningPos)) {
            miningActive = false;
            beginMineVerify();
            return;
        }

        Direction dir = BlockUtils.getDirection(miningPos);

        if (!miningActive) {
            miningPick = InvUtils.find(stack -> stack.isOf(Items.NETHERITE_PICKAXE) || stack.isOf(Items.DIAMOND_PICKAXE));
            if (!miningPick.found()) { error("PitStop: no pickaxe."); stage = Stage.FAIL; return; }

            InvUtils.swap(miningPick.slot(), true);
            lookAt(miningPos);
            mc.getNetworkHandler().sendPacket(new PlayerActionC2SPacket(PlayerActionC2SPacket.Action.START_DESTROY_BLOCK, miningPos, dir));
            mc.player.swingHand(Hand.MAIN_HAND);
            miningActive = true;
            return;
        }

        lookAt(miningPos);
        boolean cont = mc.interactionManager.updateBlockBreakingProgress(miningPos, dir);
        if (!cont) mc.getNetworkHandler().sendPacket(new PlayerActionC2SPacket(PlayerActionC2SPacket.Action.START_DESTROY_BLOCK, miningPos, dir));

        if (mc.world.isAir(miningPos)) {
            mc.getNetworkHandler().sendPacket(new PlayerActionC2SPacket(PlayerActionC2SPacket.Action.STOP_DESTROY_BLOCK, miningPos, dir));
            miningActive = false;
            beginMineVerify();
        }
    }

    private void beginMineVerify() {
        mineVerify = MINE_VERIFY_TICKS;
        stage = Stage.VERIFY_MINE;
        wait = 1;
    }

    private void verifyMining() {
        if (miningPos == null) { stage = Stage.FAIL; return; }

        // If server rubber-banded the block back, restart mining immediately.
        if (!mc.world.isAir(miningPos)) {
            miningActive = false;
            stage = Stage.MINING;
            wait = 0;
            return;
        }

        mineVerify--;
        if (mineVerify <= 0) {
            stage = miningNext;
            wait = 0;
        } else {
            wait = 1;
        }
    }

    private void postMineKit() {
        // If shulker already in inventory, no need to walk to drop.
        if (InvUtils.find(this::isNamedShulker).found()) {
            // Mark the EC slot as tried only after we have successfully picked up the shulker.
            markCurrentShulkerTriedIfNeeded();

            kitPos = null;
            afterHome = Stage.OPEN_EC_AGAIN;
            stage = Stage.WALK_HOME;
            wait = 1;
            return;
        }

        stage = Stage.WALK_TO_KIT_DROP;
        wait = 1;
    }

    private void postMineEc() {
        // FIX: don't use "found()" (you might already have EC).
        // Only proceed when EC count reaches the armed target (baseline + 1).
        if (ecPickupArmed && countItemInInventory(Items.ENDER_CHEST) >= ecPickupTargetCount) {
            disarmEnderChestPickupTarget();
            ecPos = null;
            miningPos = null;
            afterHome = Stage.DONE;
            stage = Stage.WALK_HOME;
            wait = 1;
            return;
        }

        stage = Stage.WALK_TO_EC_DROP;
        wait = 1;
    }

    private void walkToDrop(boolean kit) {
        // If already picked up, go home and continue.
        if (kit) {
            if (InvUtils.find(this::isNamedShulker).found()) {
                onPickupArrived(true);
                return;
            }
        } else {
            // FIX: use armed EC target count, not "found()"
            if (ecPickupArmed && countItemInInventory(Items.ENDER_CHEST) >= ecPickupTargetCount) {
                onPickupArrived(false);
                return;
            }
        }

        ItemEntity target = findClosestDropEntity(kit);
        if (target == null) {
            wait = Math.max(wait, pickupWait.get());
            stage = kit ? Stage.WALK_TO_KIT_DROP : Stage.WALK_TO_EC_DROP;
            return;
        }

        Vec3d tp = target.getPos();
        Vec3d pp = mc.player.getPos();
        double dx = tp.x - pp.x, dz = tp.z - pp.z;

        float yaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
        mc.player.setYaw(yaw);

        try { mc.options.forwardKey.setPressed(true); } catch (Throwable ignored) {}

        double distSq = mc.player.squaredDistanceTo(tp);
        if (distSq < 2.0) {
            stopAllMovement();
            wait = pickupWait.get();
            onPickupArrived(kit);
        } else {
            stage = kit ? Stage.WALK_TO_KIT_DROP : Stage.WALK_TO_EC_DROP;
        }
    }

    private void onPickupArrived(boolean kit) {
        if (kit) {
            // Mark the EC slot as tried only after we have successfully picked up the shulker.
            markCurrentShulkerTriedIfNeeded();

            kitPos = null;
            afterHome = Stage.OPEN_EC_AGAIN;   // return shulker then decide next
            stage = Stage.WALK_HOME;
        } else {
            disarmEnderChestPickupTarget();
            ecPos = null;
            miningPos = null;
            afterHome = Stage.DONE;
            stage = Stage.WALK_HOME;
        }
        wait = 1;
    }

    private void walkHome() {
        if (homeFeet == null) { stage = Stage.FAIL; return; }

        // If we're already on the home block, start centering.
        if (mc.player.getBlockPos().equals(homeFeet)) {
            stopAllMovement();
            stage = Stage.CENTER_HOME;
            wait = 1;
            return;
        }

        Vec3d pp = mc.player.getPos();
        Vec3d tp = homeCenter != null ? homeCenter : Vec3d.ofCenter(homeFeet);

        double dx = tp.x - pp.x, dz = tp.z - pp.z;
        float yaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
        mc.player.setYaw(yaw);

        try { mc.options.forwardKey.setPressed(true); } catch (Throwable ignored) {}

        // Close enough in XZ (within about a block) -> attempt center
        double distSqXZ = dx * dx + dz * dz;
        if (distSqXZ < 0.8) {
            stopAllMovement();
            stage = Stage.CENTER_HOME;
            wait = 1;
        }
    }

    private void centerHome() {
        if (homeFeet == null) { stage = Stage.FAIL; return; }

        double tx = homeFeet.getX() + 0.5;
        double tz = homeFeet.getZ() + 0.5;

        Vec3d pp = mc.player.getPos();
        double dx = tx - pp.x;
        double dz = tz - pp.z;

        double distSq = dx * dx + dz * dz;
        if (distSq <= CENTER_EPS * CENTER_EPS) {
            stopAllMovement();
            stage = afterHome;
            wait = delayTicks.get();
            return;
        }

        // Simple centering: face the target center and walk forward a bit.
        float yaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
        mc.player.setYaw(yaw);

        try { mc.options.forwardKey.setPressed(true); } catch (Throwable ignored) {}
        wait = 1; // re-evaluate next tick
    }

    /* =========================
     *  New: placement centering stage (AirLanding-like, eps=0.09)
     * ========================= */

    private boolean isCenteredOnHome(double eps) {
        if (mc.player == null || homeFeet == null) return true;
        double tx = homeFeet.getX() + 0.5;
        double tz = homeFeet.getZ() + 0.5;
        double cx = mc.player.getX();
        double cz = mc.player.getZ();
        return Math.abs(tx - cx) <= eps && Math.abs(tz - cz) <= eps;
    }

    private void enterPlaceCentering(Stage after) {
        placeCenterAfter = after;
        placeCenterTicks = 0;
        stage = Stage.CENTER_FOR_PLACE;
        stopAllMovement();
        wait = 0;
    }

    private void centerForPlace() {
        if (homeFeet == null || mc.player == null) { stage = Stage.FAIL; return; }

        // Only center when on ground to avoid weird drift.
        if (!mc.player.isOnGround()) {
            stopAllMovement();
            wait = 1;
            return;
        }

        double tx = homeFeet.getX() + 0.5;
        double tz = homeFeet.getZ() + 0.5;

        double cx = mc.player.getX();
        double cz = mc.player.getZ();

        double dx = tx - cx;
        double dz = tz - cz;

        if (Math.abs(dx) <= PLACE_CENTER_EPS && Math.abs(dz) <= PLACE_CENTER_EPS) {
            stopAllMovement();
            stage = placeCenterAfter;
            wait = 0;
            return;
        }

        placeCenterTicks++;
        if (placeCenterTicks > PLACE_CENTER_MAX_TICKS) {
            // Best-effort: stop and continue even if not perfectly centered.
            stopAllMovement();
            stage = placeCenterAfter;
            wait = 0;
            return;
        }

        float yaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
        mc.player.setYaw(yaw);

        try { mc.options.forwardKey.setPressed(true); } catch (Throwable ignored) {}
        wait = 1; // re-evaluate next tick
    }

    private ItemEntity findClosestDropEntity(boolean kit) {
        Vec3d center = miningPos != null ? Vec3d.ofCenter(miningPos) : mc.player.getPos();
        Box box = new Box(center, center).expand(6.0);
        ItemEntity best = null;
        double bestDist = Double.MAX_VALUE;

        for (ItemEntity it : mc.world.getEntitiesByClass(ItemEntity.class, box, e -> true)) {
            ItemStack s = it.getStack();
            if (kit) {
                if (!(s.getItem() instanceof BlockItem bi) || !(bi.getBlock() instanceof ShulkerBoxBlock)) continue;
                if (!s.getName().getString().equals(activeBoxName())) continue;
            } else {
                if (!s.isOf(Items.ENDER_CHEST)) continue;
            }
            double d = mc.player.squaredDistanceTo(it.getPos());
            if (d < bestDist) { bestDist = d; best = it; }
        }
        return best;
    }

    private void returnShulkerToEC() {
        if (!(mc.player.currentScreenHandler instanceof GenericContainerScreenHandler gh)) {
            stage = Stage.OPEN_EC_AGAIN;
            return;
        }

        FindItemResult kit = InvUtils.find(this::isNamedShulker);
        if (!kit.found()) {
            stage = Stage.CHECK_DONE;
            wait = delayTicks.get();
            return;
        }

        if (currentEcFlow == null || currentEcSlot < 0 || currentEcSlot >= 27) {
            error("PitStop: internal error: missing original Ender Chest slot for shulker return.");
            stage = Stage.FAIL;
            return;
        }

        if (!gh.getSlot(currentEcSlot).getStack().isEmpty()) {
            error("PitStop: Ender Chest slot " + currentEcSlot + " is not empty; cannot return shulker to original slot.");
            stage = Stage.FAIL;
            return;
        }

        // Return to original slot (required to reliably iterate over multiple same-name shulkers).
        InvUtils.move().from(kit.slot()).toId(currentEcSlot);

        // Clear current slot tracking after successful return.
        currentEcSlot = -1;
        currentEcFlow = null;

        wait = delayTicks.get();
        stage = Stage.CHECK_DONE;
    }

    private void checkDoneOrNext() {
        // Decide next needed flow without doing any extra EC/shulker work.
        Flow next = nextNeededFlow();

        if (next != null) {
            flow = next;
            stage = Stage.FIND_OR_PLACE_EC;
            wait = delayTicks.get();
            return;
        }

        // Nothing else needed.
        if (pickEnderChestAfter.get()) {
            // Only mine EC if it exists and is on platform.
            BlockPos existing = findExistingEnderChestOnPlatform();
            if (existing != null) {
                ecPos = existing;
                stage = Stage.START_MINE_EC;
                wait = delayTicks.get();
            } else {
                stage = Stage.DONE;
            }
        } else {
            stage = Stage.DONE;
        }
    }

    /* =========================
     *  Flow predicates
     * ========================= */

    private boolean isFlowStillNeeded() {
        return switch (flow) {
            case Elytra -> needsElytraSwap();
            case Item1 -> needsItem1();
            case Item2 -> needsItem2();
        };
    }

    private boolean needsItem1() {
        int target = item1Target.get();
        if (target <= 0) return false;
        return countItemInInventory(item1.get()) < target;
    }

    private boolean needsItem2() {
        int target = item2Target.get();
        if (target <= 0) return false;
        return countItemInInventory(item2.get()) < target;
    }

    private String activeBoxName() {
        return switch (flow) {
            case Elytra -> boxName.get();
            case Item1 -> item1BoxName.get();
            case Item2 -> item2BoxName.get();
        };
    }

    /* =========================
     *  Inventory / item checks
     * ========================= */

    private boolean isElytra(ItemStack s) {
        return s != null && s.getItem() == Items.ELYTRA;
    }

    private boolean isBad(ItemStack s) {
        if (!isElytra(s)) return false;
        int remaining = s.getMaxDamage() - s.getDamage();
        return remaining < threshold.get();
    }

    private boolean isNamedShulker(ItemStack s) {
        if (s == null || s.isEmpty()) return false;
        if (!(s.getItem() instanceof BlockItem bi)) return false;
        if (!(bi.getBlock() instanceof ShulkerBoxBlock)) return false;
        String name = s.getName().getString();
        return name.equals(activeBoxName());
    }

    private ItemStack getPlayerSlotStack(int slot) {
        ScreenHandler h = mc.player.playerScreenHandler;
        if (slot < 0 || slot >= h.slots.size()) return ItemStack.EMPTY;
        return h.getSlot(slot).getStack();
    }

    private int countDamagedInInventory() {
        int c = 0;
        // FIX: only count real inventory + hotbar slots in playerScreenHandler: 9..44 (per your screenshot)
        for (int i = INV_SLOT_START; i <= INV_SLOT_END; i++) {
            ItemStack s = getPlayerSlotStack(i);
            if (isElytra(s) && isBad(s)) c++;
        }
        return c;
    }

    private int countItemInInventory(Item item) {
        int c = 0;
        // FIX: only count real inventory + hotbar slots in playerScreenHandler: 9..44 (per your screenshot)
        for (int i = INV_SLOT_START; i <= INV_SLOT_END; i++) {
            ItemStack s = getPlayerSlotStack(i);
            if (!s.isEmpty() && s.getItem() == item) c += s.getCount();
        }
        return c;
    }

    private int findDamagedSlotInContainerView(ShulkerBoxScreenHandler sh) {
        for (int i = 27; i <= 62; i++) {
            ItemStack s = sh.getSlot(i).getStack();
            if (isElytra(s) && isBad(s)) return i;
        }
        return -1;
    }

    private void clickContainerSlot(ShulkerBoxScreenHandler sh, int slot, SlotActionType type) {
        mc.interactionManager.clickSlot(sh.syncId, slot, 0, type, mc.player);
    }

    /* =========================
     *  Platform helpers
     * ========================= */

    private boolean ensurePlatformReady() {
        if (homeFeet == null || platform == null) {
            homeFeet = mc.player.getBlockPos();
            homeCenter = Vec3d.ofCenter(homeFeet);
            platform = detect2x2Platform(homeFeet);
            if (platform == null) {
                error("PitStop: cannot detect 2x2 platform. Stand on it first.");
                return false;
            }
        }
        return true;
    }

    private BlockPos[] detect2x2Platform(BlockPos feet) {
        // Enumerate 4 possible 2x2 corners that include feet.
        BlockPos[] corners = new BlockPos[] {
            feet,
            feet.add(-1, 0, 0),
            feet.add(0, 0, -1),
            feet.add(-1, 0, -1)
        };

        int yTop = feet.getY();
        int yFloor = yTop - 1;

        for (BlockPos corner : corners) {
            BlockPos[] tops = new BlockPos[] {
                new BlockPos(corner.getX(),     yTop, corner.getZ()),
                new BlockPos(corner.getX() + 1, yTop, corner.getZ()),
                new BlockPos(corner.getX(),     yTop, corner.getZ() + 1),
                new BlockPos(corner.getX() + 1, yTop, corner.getZ() + 1)
            };

            // Ensure feet is within these 4 top positions.
            boolean contains = false;
            for (BlockPos p : tops) {
                if (p.equals(feet)) { contains = true; break; }
            }
            if (!contains) continue;

            // Validate floor blocks exist (not air).
            boolean ok = true;
            for (BlockPos t : tops) {
                BlockPos floor = new BlockPos(t.getX(), yFloor, t.getZ());
                if (mc.world.isAir(floor)) { ok = false; break; }
            }
            if (!ok) continue;

            return tops;
        }

        return null;
    }

    private BlockPos findExistingEnderChestOnPlatform() {
        if (platform == null) return null;
        for (BlockPos p : platform) {
            if (mc.world.getBlockState(p).getBlock() instanceof EnderChestBlock) return p;
        }
        return null;
    }

    /**
     * Picks the farthest empty platform cell from homeCenter excluding up to two cells.
     * This implements:
     *  - EnderChest at diagonal/farthest
     *  - Shulker at one of the second-farthest cells (we pick the farther of the remaining)
     */
    private BlockPos chooseFarthestPlatformCellExcluding(BlockPos excludeA, BlockPos excludeB) {
        if (platform == null || homeCenter == null) return null;

        BlockPos best = null;
        double bestDist = -1;

        for (BlockPos p : platform) {
            if (excludeA != null && p.equals(excludeA)) continue;
            if (excludeB != null && p.equals(excludeB)) continue;

            // Strict: must be on the same plane as the player stands.
            if (homeFeet != null && p.getY() != homeFeet.getY()) continue;

            // Strict: empty cell only.
            if (!mc.world.isAir(p)) continue;

            double d = homeCenter.squaredDistanceTo(Vec3d.ofCenter(p));
            if (d > bestDist) { bestDist = d; best = p; }
        }

        return best;
    }

    /* =========================
     *  Placement / view helpers
     * ========================= */

    private boolean placeOnFloorUp(BlockPos placePos) {
        if (placePos == null) return false;
        BlockPos floor = placePos.down();
        if (mc.world.isAir(floor)) return false;

        lookAt(placePos);

        Vec3d hitVec = Vec3d.ofCenter(floor).add(0, 0.5, 0);
        BlockHitResult hit = new BlockHitResult(hitVec, Direction.UP, floor, false);

        ActionResult ar = mc.interactionManager.interactBlock(mc.player, Hand.MAIN_HAND, hit);
        mc.player.swingHand(Hand.MAIN_HAND);

        return ar != ActionResult.FAIL;
    }

    private void lookAt(BlockPos pos) {
        if (mc.player == null) return;
        Vec3d eye = mc.player.getEyePos();
        Vec3d target = Vec3d.ofCenter(pos);
        Vec3d diff = target.subtract(eye);
        double dx = diff.x, dy = diff.y, dz = diff.z;
        float yaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
        float pitch = (float) Math.toDegrees(-Math.atan2(dy, Math.sqrt(dx * dx + dz * dz)));
        mc.player.setYaw(yaw);
        mc.player.setPitch(pitch);
    }

    private void stopAllMovement() {
        try { mc.options.forwardKey.setPressed(false); } catch (Throwable ignored) {}
        try { mc.options.backKey.setPressed(false); } catch (Throwable ignored) {}
        try { mc.options.leftKey.setPressed(false); } catch (Throwable ignored) {}
        try { mc.options.rightKey.setPressed(false); } catch (Throwable ignored) {}
        try { mc.options.jumpKey.setPressed(false); } catch (Throwable ignored) {}
        try { mc.options.sneakKey.setPressed(false); } catch (Throwable ignored) {}
    }

    private void selectPreferredHotbarSlot() {
        if (mc == null || mc.player == null) return;
        int preferred = Math.max(0, Math.min(8, preferredSlot.get()));
        if (mc.player.getInventory().selectedSlot != preferred) {
            mc.player.getInventory().selectedSlot = preferred;
            if (mc.getNetworkHandler() != null) {
                mc.getNetworkHandler().sendPacket(new UpdateSelectedSlotC2SPacket(preferred));
            }
        }
    }

    private boolean bringToSelectedViaSwap(FindItemResult it) {
        if (!it.found()) return false;

        int selected = mc.player.getInventory().selectedSlot;

        if (it.isHotbar()) {
            InvUtils.swap(it.slot(), true);
            return true;
        }

        int selectedContainerSlot = 36 + selected;
        ScreenHandler h = mc.player.playerScreenHandler;

        try {
            mc.interactionManager.clickSlot(h.syncId, it.slot(), 0, SlotActionType.PICKUP, mc.player);
            mc.interactionManager.clickSlot(h.syncId, selectedContainerSlot, 0, SlotActionType.PICKUP, mc.player);
            mc.interactionManager.clickSlot(h.syncId, it.slot(), 0, SlotActionType.PICKUP, mc.player);
            return true;
        } catch (Exception ex) {
            return false;
        }
    }

    private int getFreeHotbarSlotExcluding(int exclude) {
        if (mc.player == null) return 0;
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
}
