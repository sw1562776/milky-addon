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
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.network.packet.c2s.play.PlayerActionC2SPacket;
import net.minecraft.screen.GenericContainerScreenHandler;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.screen.ShulkerBoxScreenHandler;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;

public class PitStop extends Module {
    // ---------------- Settings ----------------
    private final SettingGroup sg = settings.getDefaultGroup();

    private final Setting<Integer> delayTicks = sg.add(new IntSetting.Builder()
        .name("delay-ticks")
        .description("Ticks to wait between staged actions.")
        .defaultValue(5)
        .min(0)
        .build());

    private final Setting<Integer> range = sg.add(new IntSetting.Builder()
        .name("range")
        .description("Radius to search/place Ender Chest and shulker.")
        .defaultValue(3)
        .min(1)
        .sliderRange(1, 6)
        .build());

    private final Setting<Integer> threshold = sg.add(new IntSetting.Builder()
        .name("durability-threshold")
        .description("Elytras with remaining durability below this are considered damaged.")
        .defaultValue(10)
        .min(1)
        .sliderRange(1, 432)
        .build());

    private final Setting<String> boxName = sg.add(new StringSetting.Builder()
        .name("shulker-name")
        .description("Display name of shulker boxes to use.")
        .defaultValue("Milky Elytra")
        .build());

    private final Setting<Boolean> pickEnderChestAfter = sg.add(new BoolSetting.Builder()
        .name("pick-ender-chest-after")
        .description("Mine & pick up the Ender Chest when done.")
        .defaultValue(false)
        .build());

    private final Setting<Integer> pickupWait = sg.add(new IntSetting.Builder()
        .name("pickup-wait-ticks")
        .description("Extra ticks to wait when standing on drops.")
        .defaultValue(6)
        .min(0)
        .sliderRange(0, 20)
        .build());

    // ---------------- State ----------------
    private enum Stage { IDLE, ANALYZE, FIND_OR_PLACE_EC, OPEN_EC, TAKE_SHULKER, PLACE_SHULKER, OPEN_SHULKER, SWAP, CLOSE_SHULKER, MINING, WALK_TO_KIT_DROP, OPEN_EC_AGAIN, RETURN_SHULKER, CHECK_DONE, START_MINE_EC, WALK_TO_EC_DROP, DONE, FAIL }

    private Stage stage = Stage.IDLE;
    private int wait = 0;

    private BlockPos ecPos = null;
    private BlockPos kitPos = null;
    private int replacedThisRound = 0;

    // Mining state
    private boolean miningActive = false;
    private BlockPos miningPos = null;
    private Stage miningNext = Stage.DONE;
    private FindItemResult miningPick = null;

    public PitStop() {
        super(Addon.CATEGORY, "PitStop", "Swap low-durability Elytras using named shulkers from an Ender Chest.");
    }

    @Override
    public void onActivate() {
        stage = Stage.ANALYZE;
        wait = 0;
        ecPos = null;
        kitPos = null;
        replacedThisRound = 0;
        miningActive = false;
        miningPos = null;
        miningNext = Stage.DONE;
    }

    @Override
    public void onDeactivate() {
        try {
            mc.options.forwardKey.setPressed(false);
        } catch (Throwable ignored) {}
        stage = Stage.IDLE;
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    private void onTick(TickEvent.Pre e) {
        if (!isActive() || mc.player == null || mc.world == null) return;

        if (wait > 0) {
            wait--;
            return;
        }

        try {
            switch (stage) {
                case ANALYZE -> analyze();
                case FIND_OR_PLACE_EC -> findOrPlaceEnderChest();
                case OPEN_EC -> openBlockAt(ecPos, Stage.TAKE_SHULKER, Stage.FIND_OR_PLACE_EC);
                case TAKE_SHULKER -> takeShulkerFromEC();
                case PLACE_SHULKER -> placeShulker();
                case OPEN_SHULKER -> openBlockAt(kitPos, Stage.SWAP, Stage.PLACE_SHULKER);
                case SWAP -> swapInKit();
                case CLOSE_SHULKER -> closeContainerThen(Stage.MINING);
                case MINING -> continueMining();
                case WALK_TO_KIT_DROP -> walkToDrop(true);
                case OPEN_EC_AGAIN -> openBlockAt(ecPos, Stage.RETURN_SHULKER, Stage.FIND_OR_PLACE_EC);
                case RETURN_SHULKER -> returnShulkerToEC();
                case CHECK_DONE -> checkDoneOrNext();
                case START_MINE_EC -> startMining(ecPos, Stage.WALK_TO_EC_DROP);
                case WALK_TO_EC_DROP -> walkToDrop(false);
                case DONE -> toggle();
                case FAIL -> toggle();
                default -> {}
            }
        } catch (Exception ex) {
            error("PitStop error: " + ex.getMessage());
            stage = Stage.FAIL;
        }
    }

    // ---------------- Steps ----------------
    private void analyze() {
        // If chest slot has Elytra, leave it as-is; swapping works on inventory only.
        stage = Stage.FIND_OR_PLACE_EC;
    }

    private void findOrPlaceEnderChest() {
        // Try to find an existing EC near us; else place one
        BlockPos best = null;
        double bestDist = Double.MAX_VALUE;
        int r = range.get();
        BlockPos base = mc.player.getBlockPos();
        for (int dx = -r; dx <= r; dx++) {
            for (int dy = -1; dy <= 1; dy++) {
                for (int dz = -r; dz <= r; dz++) {
                    BlockPos p = base.add(dx, dy, dz);
                    if (mc.world.getBlockState(p).getBlock() instanceof EnderChestBlock) {
                        ecPos = p;
                        stage = Stage.OPEN_EC;
                        wait = delayTicks.get();
                        return;
                    }
                    if (!mc.world.isAir(p)) continue;
                    if (!BlockUtils.canPlace(p, true)) continue;
                    double d = mc.player.squaredDistanceTo(Vec3d.ofCenter(p));
                    if (d < bestDist) {
                        bestDist = d;
                        best = p;
                    }
                }
            }
        }

        if (best == null) {
            error("PitStop: no place for Ender Chest.");
            stage = Stage.FAIL;
            return;
        }

        FindItemResult ec = InvUtils.find(Items.ENDER_CHEST);
        if (!ec.found()) {
            error("PitStop: no Ender Chest in inventory.");
            stage = Stage.FAIL;
            return;
        }

        int selected = mc.player.getInventory().selectedSlot; // 0..8
        if (!ec.isHotbar()) InvUtils.move().from(ec.slot()).toHotbar(selected);
        InvUtils.swap(selected, true);
        boolean placed = BlockUtils.place(best, new FindItemResult(mc.player.getInventory().selectedSlot, 1), true, 0, true);
        if (!placed) {
            error("PitStop: failed to place Ender Chest.");
            stage = Stage.FAIL;
            return;
        }

        ecPos = best;
        stage = Stage.OPEN_EC;
        wait = delayTicks.get();
    }

    private void openBlockAt(BlockPos pos, Stage nextIfOpened, Stage fallbackIfMissing) {
        if (pos == null || mc.world.isAir(pos)) {
            stage = fallbackIfMissing;
            return;
        }
        lookAt(pos);
        BlockHitResult hit = new BlockHitResult(Vec3d.ofCenter(pos), Direction.UP, pos, false);
        mc.interactionManager.interactBlock(mc.player, Hand.MAIN_HAND, hit);
        mc.player.swingHand(Hand.MAIN_HAND);
        wait = delayTicks.get();
        stage = nextIfOpened;
    }

    private void takeShulkerFromEC() {
        if (!(mc.player.currentScreenHandler instanceof GenericContainerScreenHandler gh)) {
            stage = Stage.OPEN_EC;
            return;
        }
        int found = -1;
        for (int i = 0; i < 27; i++) {
            ItemStack s = gh.getSlot(i).getStack();
            if (isNamedShulker(s)) {
                found = i;
                break;
            }
        }
        if (found == -1) {
            error("PitStop: no matching shulker in Ender Chest.");
            stage = Stage.FAIL;
            return;
        }
        FindItemResult empty = InvUtils.findEmpty();
        if (!empty.found()) {
            error("PitStop: no empty slot for shulker.");
            stage = Stage.FAIL;
            return;
        }
        InvUtils.move().fromId(found).to(empty.slot());
        wait = delayTicks.get();
        closeContainerThen(Stage.PLACE_SHULKER);
    }

    private void placeShulker() {
        FindItemResult kit = InvUtils.find(this::isNamedShulker);
        if (!kit.found()) {
            error("PitStop: shulker not found in inventory.");
            stage = Stage.FAIL;
            return;
        }
        BlockPos best = null;
        double bestDist = Double.MAX_VALUE;
        int r = Math.max(1, range.get());
        BlockPos base = mc.player.getBlockPos();
        for (int dx = -r; dx <= r; dx++) {
            for (int dy = -1; dy <= 1; dy++) {
                for (int dz = -r; dz <= r; dz++) {
                    BlockPos p = base.add(dx, dy, dz);
                    if (!BlockUtils.canPlace(p, true)) continue;
                    double d = mc.player.squaredDistanceTo(Vec3d.ofCenter(p));
                    if (d < bestDist) {
                        bestDist = d;
                        best = p;
                    }
                }
            }
        }
        if (best == null) {
            error("PitStop: no place for shulker.");
            stage = Stage.FAIL;
            return;
        }
        InvUtils.swap(kit.slot(), true);
        boolean placed = BlockUtils.place(best, new FindItemResult(mc.player.getInventory().selectedSlot, 1), true, 0, true);
        if (!placed) {
            error("PitStop: failed to place shulker.");
            stage = Stage.FAIL;
            return;
        }
        kitPos = best;
        stage = Stage.OPEN_SHULKER;
        wait = delayTicks.get();
    }

    private void swapInKit() {
        if (!(mc.player.currentScreenHandler instanceof ShulkerBoxScreenHandler sh)) {
            stage = Stage.OPEN_SHULKER;
            return;
        }

        replacedThisRound = 0;
        for (int ci = 0; ci < 27; ci++) {
            ItemStack kitStack = sh.getSlot(ci).getStack();
            if (!(isElytra(kitStack) && !isBad(kitStack))) continue;

            int pv = findDamagedSlotInContainerView(sh);
            if (pv == -1) break; // no more damaged

            // three-click swap: player(damaged)->cursor, click kit(ci) swap, click back to player
            clickContainerSlot(sh, pv, SlotActionType.PICKUP);
            clickContainerSlot(sh, ci, SlotActionType.PICKUP);
            clickContainerSlot(sh, pv, SlotActionType.PICKUP);
            replacedThisRound++;
        }

        stage = Stage.CLOSE_SHULKER;
        wait = Math.max(1, delayTicks.get());
    }

    private void closeContainerThen(Stage next) {
        if (mc.currentScreen instanceof HandledScreen<?>) mc.player.closeHandledScreen();

        if (next == Stage.MINING) {
            // NEW: wait a few ticks after closing the shulker UI before starting to mine it
            // to avoid breaking while the container is still closing.
            wait = Math.max(wait, delayTicks.get());
            startMining(kitPos, Stage.WALK_TO_KIT_DROP);
        } else {
            stage = next;
            wait = delayTicks.get();
        }
    }

    // --------------- Mining / Walk-to-pickup ---------------
    private void startMining(BlockPos pos, Stage next) {
        if (pos == null) {
            stage = Stage.FAIL;
            return;
        }
        miningPos = pos;
        miningNext = next;
        miningActive = false;
        stage = Stage.MINING;
    }

    private void continueMining() {
        if (miningPos == null) {
            stage = Stage.FAIL;
            return;
        }

        if (mc.world.isAir(miningPos)) {
            miningActive = false;
            stage = miningNext;
            return;
        }

        Direction dir = BlockUtils.getDirection(miningPos);

        if (!miningActive) {
            miningPick = InvUtils.find(stack -> stack.isOf(Items.NETHERITE_PICKAXE) || stack.isOf(Items.DIAMOND_PICKAXE));
            if (!miningPick.found()) {
                error("PitStop: no pickaxe.");
                stage = Stage.FAIL;
                return;
            }
            InvUtils.swap(miningPick.slot(), true);
            lookAt(miningPos);
            mc.getNetworkHandler().sendPacket(new PlayerActionC2SPacket(PlayerActionC2SPacket.Action.START_DESTROY_BLOCK, miningPos, dir));
            mc.player.swingHand(Hand.MAIN_HAND);
            miningActive = true;
            return;
        }

        lookAt(miningPos);
        boolean cont = mc.interactionManager.updateBlockBreakingProgress(miningPos, dir);
        if (!cont) {
            mc.getNetworkHandler().sendPacket(new PlayerActionC2SPacket(PlayerActionC2SPacket.Action.START_DESTROY_BLOCK, miningPos, dir));
        }

        if (mc.world.isAir(miningPos)) {
            mc.getNetworkHandler().sendPacket(new PlayerActionC2SPacket(PlayerActionC2SPacket.Action.STOP_DESTROY_BLOCK, miningPos, dir));
            miningActive = false;
            stage = miningNext;
        }
    }

    private void walkToDrop(boolean kit) {
        // already picked?
        if (kit) {
            if (InvUtils.find(this::isNamedShulker).found()) {
                onPickupArrived(true);
                return;
            }
        } else {
            if (InvUtils.find(Items.ENDER_CHEST).found()) {
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
        float yaw = (float) (Math.toDegrees(Math.atan2(-dx, dz)));
        mc.player.setYaw(yaw);

        try {
            mc.options.forwardKey.setPressed(true);
        } catch (Throwable ignored) {}

        double distSq = mc.player.squaredDistanceTo(tp);
        if (distSq < 2.0) {
            try {
                mc.options.forwardKey.setPressed(false);
            } catch (Throwable ignored) {}
            wait = pickupWait.get();
            onPickupArrived(kit);
        } else {
            stage = kit ? Stage.WALK_TO_KIT_DROP : Stage.WALK_TO_EC_DROP;
        }
    }

    private void onPickupArrived(boolean kit) {
        if (kit) {
            kitPos = null;
            stage = Stage.OPEN_EC_AGAIN;
        } else {
            stage = Stage.CHECK_DONE;
        }
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
                if (!s.getName().getString().equals(boxName.get())) continue;
            } else {
                if (!s.isOf(Items.ENDER_CHEST)) continue;
            }
            double d = mc.player.squaredDistanceTo(it.getPos());
            if (d < bestDist) {
                bestDist = d;
                best = it;
            }
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

        int dest = -1;
        for (int i = 0; i < 27; i++) {
            if (gh.getSlot(i).getStack().isEmpty()) {
                dest = i;
                break;
            }
        }
        if (dest == -1) {
            error("PitStop: Ender Chest is full.");
            stage = Stage.FAIL;
            return;
        }

        InvUtils.move().from(kit.slot()).toId(dest);
        wait = delayTicks.get();
        stage = Stage.CHECK_DONE;
    }

    private void checkDoneOrNext() {
        // If any damaged elytra remain in inventory, loop; else end (optionally mine EC)
        int remaining = countDamagedInInventory();
        if (remaining <= 0) {
            stage = pickEnderChestAfter.get() ? Stage.START_MINE_EC : Stage.DONE;
            return;
        }
        stage = Stage.OPEN_EC;
        wait = delayTicks.get();
    }

    // ---------------- Helpers ----------------
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
        return name.equals(boxName.get());
    }

    private ItemStack getPlayerSlotStack(int slot) {
        ScreenHandler h = mc.player.playerScreenHandler;
        if (slot < 0 || slot >= h.slots.size()) return ItemStack.EMPTY;
        return h.getSlot(slot).getStack();
    }

    private void clickPlayerSlot(int slot, int button, SlotActionType type) {
        ScreenHandler h = mc.player.playerScreenHandler;
        mc.interactionManager.clickSlot(h.syncId, slot, button, type, mc.player);
    }

    private int countDamagedInInventory() {
        int c = 0;
        for (int i = 0; i <= 35; i++) {
            ItemStack s = getPlayerSlotStack(i);
            if (isElytra(s) && isBad(s)) c++;
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

    private void lookAt(BlockPos pos) {
        if (mc.player == null) return;
        Vec3d eye = mc.player.getEyePos();
        Vec3d target = Vec3d.ofCenter(pos);
        Vec3d diff = target.subtract(eye);
        double dx = diff.x;
        double dy = diff.y;
        double dz = diff.z;
        float yaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
        float pitch = (float) Math.toDegrees(-Math.atan2(dy, Math.sqrt(dx * dx + dz * dz)));
        mc.player.setYaw(yaw);
        mc.player.setPitch(pitch);
    }
}
