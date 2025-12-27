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
        .description("Radius to search/place Ender Chest and shulker.")
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
        IDLE, ANALYZE,
        FIND_OR_PLACE_EC, OPEN_EC, TAKE_SHULKER,
        PLACE_SHULKER, OPEN_SHULKER,
        SWAP,
        RESTOCK_ITEM1, RESTOCK_ITEM2,
        CLOSE_SHULKER, MINING, WALK_TO_KIT_DROP,
        OPEN_EC_AGAIN, RETURN_SHULKER,
        CHECK_DONE, START_MINE_EC, WALK_TO_EC_DROP,
        DONE, FAIL
    }

    private enum Flow { Elytra, Item1, Item2 }

    private Stage stage = Stage.IDLE;
    private Flow flow = Flow.Elytra;
    private int wait = 0;

    private BlockPos ecPos = null;
    private BlockPos kitPos = null;
    private int replacedThisRound = 0;

    private boolean miningActive = false;
    private BlockPos miningPos = null;
    private Stage miningNext = Stage.DONE;
    private FindItemResult miningPick = null;

    public PitStop() {
        super(Addon.MilkyModCategory, "PitStop", "Swap low-durability Elytras using named shulkers from an Ender Chest and restock two extra items.");
    }

    @Override
    public void onActivate() {
        stage = Stage.ANALYZE;
        flow = Flow.Elytra;
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
        try { mc.options.forwardKey.setPressed(false); } catch (Throwable ignored) {}
        stage = Stage.IDLE;
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    private void onTick(TickEvent.Pre e) {
        if (!isActive() || mc.player == null || mc.world == null) return;

        if (wait > 0) { wait--; return; }

        try {
            switch (stage) {
                case ANALYZE -> analyze();
                case FIND_OR_PLACE_EC -> findOrPlaceEnderChest();
                case OPEN_EC -> openBlockAt(ecPos, Stage.TAKE_SHULKER, Stage.FIND_OR_PLACE_EC);
                case TAKE_SHULKER -> takeShulkerFromEC();
                case PLACE_SHULKER -> placeShulker();
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
                case WALK_TO_KIT_DROP -> walkToDrop(true);
                case OPEN_EC_AGAIN -> openBlockAt(ecPos, Stage.RETURN_SHULKER, Stage.FIND_OR_PLACE_EC);
                case RETURN_SHULKER -> returnShulkerToEC();
                case CHECK_DONE -> checkDoneOrNext();
                case START_MINE_EC -> startMining(ecPos, Stage.WALK_TO_EC_DROP);
                case WALK_TO_EC_DROP -> walkToDrop(false);
                case DONE, FAIL -> toggle();
                default -> {}
            }
        } catch (Exception ex) {
            error("PitStop error: " + ex.getMessage());
            stage = Stage.FAIL;
        }
    }

    private void analyze() {
        stage = Stage.FIND_OR_PLACE_EC;
    }

    private void findOrPlaceEnderChest() {
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
                    if (d < bestDist) { bestDist = d; best = p; }
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

        selectPreferredHotbarSlot();

        if (!bringToSelectedViaSwap(ec)) {
            error("PitStop: unable to hold Ender Chest.");
            stage = Stage.FAIL;
            return;
        }

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
        if (pos == null || mc.world.isAir(pos)) { stage = fallbackIfMissing; return; }
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

        if (flow == Flow.Item1 && !needsItem1()) {
            closeContainerThen(Stage.CHECK_DONE);
            return;
        }
        if (flow == Flow.Item2 && !needsItem2()) {
            closeContainerThen(Stage.CHECK_DONE);
            return;
        }

        int found = -1;
        for (int i = 0; i < 27; i++) {
            ItemStack s = gh.getSlot(i).getStack();
            if (isNamedShulker(s)) { found = i; break; }
        }

        if (found == -1) {
            error("PitStop: missing shulker \"" + activeBoxName() + "\" in Ender Chest.");
            closeContainerThen(Stage.FAIL);
            return;
        }

        FindItemResult empty = InvUtils.findEmpty();
        if (!empty.found()) {
            error("PitStop: no empty slot for shulker.");
            closeContainerThen(Stage.FAIL);
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
                    if (d < bestDist) { bestDist = d; best = p; }
                }
            }
        }
        if (best == null) {
            error("PitStop: no place for shulker.");
            stage = Stage.FAIL;
            return;
        }

        selectPreferredHotbarSlot();

        if (!bringToSelectedViaSwap(kit)) {
            error("PitStop: unable to hold Shulker.");
            stage = Stage.FAIL;
            return;
        }

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
            if (pv == -1) break;
            clickContainerSlot(sh, pv, SlotActionType.PICKUP);
            clickContainerSlot(sh, ci, SlotActionType.PICKUP);
            clickContainerSlot(sh, pv, SlotActionType.PICKUP);
            replacedThisRound++;
        }
        stage = Stage.CLOSE_SHULKER;
        wait = Math.max(1, delayTicks.get());
    }

    private void restockInKit(Item want, int target) {
        if (!(mc.player.currentScreenHandler instanceof ShulkerBoxScreenHandler sh)) {
            stage = Stage.OPEN_SHULKER;
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
            startMining(kitPos, Stage.WALK_TO_KIT_DROP);
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
        stage = Stage.MINING;
    }

    private void continueMining() {
        if (miningPos == null) { stage = Stage.FAIL; return; }
        if (mc.world.isAir(miningPos)) { miningActive = false; stage = miningNext; return; }
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
            stage = miningNext;
        }
    }

    private void walkToDrop(boolean kit) {
        if (kit) {
            if (InvUtils.find(this::isNamedShulker).found()) { onPickupArrived(true); return; }
        } else {
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
            try { mc.options.forwardKey.setPressed(false); } catch (Throwable ignored) {}
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
            ecPos = null;
            miningPos = null;
            stage = Stage.DONE;
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

        int dest = -1;
        for (int i = 0; i < 27; i++) {
            if (gh.getSlot(i).getStack().isEmpty()) { dest = i; break; }
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
        if (flow == Flow.Elytra) {
            int remaining = countDamagedInInventory();
            if (remaining > 0) { stage = Stage.OPEN_EC; wait = delayTicks.get(); return; }
            if (needsItem1()) { flow = Flow.Item1; stage = Stage.OPEN_EC; wait = delayTicks.get(); return; }
            if (needsItem2()) { flow = Flow.Item2; stage = Stage.OPEN_EC; wait = delayTicks.get(); return; }
            stage = pickEnderChestAfter.get() ? Stage.START_MINE_EC : Stage.DONE;
            return;
        }

        if (flow == Flow.Item1) {
            if (needsItem1()) { stage = Stage.OPEN_EC; wait = delayTicks.get(); return; }
            if (needsItem2()) { flow = Flow.Item2; stage = Stage.OPEN_EC; wait = delayTicks.get(); return; }
            stage = pickEnderChestAfter.get() ? Stage.START_MINE_EC : Stage.DONE;
            return;
        }

        if (flow == Flow.Item2) {
            if (needsItem2()) { stage = Stage.OPEN_EC; wait = delayTicks.get(); return; }
            stage = pickEnderChestAfter.get() ? Stage.START_MINE_EC : Stage.DONE;
        }
    }

    private boolean needsItem1() {
        int target = item1Target.get();
        return countItemInInventory(item1.get()) < target;
    }

    private boolean needsItem2() {
        int target = item2Target.get();
        return countItemInInventory(item2.get()) < target;
    }

    private String activeBoxName() {
        return switch (flow) {
            case Elytra -> boxName.get();
            case Item1 -> item1BoxName.get();
            case Item2 -> item2BoxName.get();
        };
    }

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

    private int countItemInInventory(Item item) {
        int c = 0;
        for (int i = 0; i <= 35; i++) {
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
}
