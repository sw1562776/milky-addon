package com.milky.hunt.modules;

import com.milky.hunt.Addon;
import meteordevelopment.meteorclient.events.render.Render3DEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.renderer.ShapeMode;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.render.color.SettingColor;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.item.BlockItem;
import net.minecraft.item.Item;
import net.minecraft.item.Items;
import net.minecraft.network.packet.c2s.play.PlayerActionC2SPacket;
import net.minecraft.network.packet.c2s.play.PlayerInteractBlockC2SPacket;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;

import java.util.ArrayList;
import java.util.List;

public class AutoGolem extends Module {
    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    public enum GolemType {
    Snowman,
    Ironman,
    Wither
}
    private final Setting<GolemType> type = sgGeneral.add(new EnumSetting.Builder<GolemType>()
        .name("type")
        .description("The type of golem to build.")
        .defaultValue(GolemType.Snowman)
        .build()
    );

    private final Setting<Integer> placeDelay = sgGeneral.add(new IntSetting.Builder()
        .name("place-delay")
        .description("Ticks between each block placement.")
        .defaultValue(1)
        .sliderRange(1, 20)
        .build()
    );

    private final Setting<Integer> blocksPerTick = sgGeneral.add(new IntSetting.Builder()
        .name("blocks-per-tick")
        .description("How many blocks to place each tick.")
        .defaultValue(1)
        .sliderRange(1, 5)
        .build()
    );

    private final Setting<Boolean> continuous = sgGeneral.add(new BoolSetting.Builder()
        .name("continuous")
        .description("Continuously builds snow golems if materials are available.")
        .defaultValue(false)
        .visible(() -> type.get() != GolemType.Wither)
        .build()
    );

    private final Setting<Integer> loopDelay = sgGeneral.add(new IntSetting.Builder()
        .name("loop-delay")
        .description("Ticks to wait between snow golems when in continuous mode.")
        .defaultValue(20)
        .sliderRange(0, 200)
        .visible(continuous::get)
        .build()
    );

    private final Setting<Boolean> render = sgGeneral.add(new BoolSetting.Builder()
        .name("render")
        .description("Render the snowman frame while placing.")
        .defaultValue(true)
        .build()
    );

    private final Setting<ShapeMode> shapeMode = sgGeneral.add(new EnumSetting.Builder<ShapeMode>()
        .name("shape-mode")
        .description("How the box is rendered.")
        .defaultValue(ShapeMode.Both)
        .build()
    );

    private final Setting<SettingColor> sideColor = sgGeneral.add(new ColorSetting.Builder()
        .name("side-color")
        .defaultValue(new SettingColor(255, 255, 255, 20))
        .build()
    );

    private final Setting<SettingColor> lineColor = sgGeneral.add(new ColorSetting.Builder()
        .name("line-color")
        .defaultValue(new SettingColor(255, 255, 255, 200))
        .build()
    );

    private final List<BlockPos> snowmanBlocks = new ArrayList<>();
    private final List<BlockPos> ironmanBlocks = new ArrayList<>();
    private final List<BlockPos> witherBlocks = new ArrayList<>();
    
    private final List<BlockPos> waitingForBreak = new ArrayList<>();
    private int delay = 0;
    private int index = 0;

    private boolean waitingForNextLoop = false;
    private int loopDelayTimer = 0;
    private boolean waitingForSlotSync = false;

    public AutoGolem() {
        super(Addon.CATEGORY, "AutoGolem", "Automatically builds a snow golem, iron golem, or wither.");
    }

    @Override
    public void onActivate() {
        switch (type.get()) {
            case Snowman -> onActivateSnowman();
            case Ironman -> onActivateIronman();
            case Wither -> onActivateWither();
        }
    }

    public void onActivateSnowman() {
        snowmanBlocks.clear();
        waitingForBreak.clear();
        index = 0;
        delay = 0;
        loopDelayTimer = 0;
        waitingForNextLoop = false;
        waitingForSlotSync = false;

        Vec3d dir = mc.player.getRotationVec(1.0f);
        Vec3d horizontal = new Vec3d(dir.x, 0, dir.z).normalize().multiply(2.0);
        Vec3d target = mc.player.getPos().add(horizontal).add(0, 2, 0);
        BlockPos basePos = BlockPos.ofFloored(target);

        snowmanBlocks.add(basePos);
        snowmanBlocks.add(basePos.up());
        snowmanBlocks.add(basePos.up(2));

        int snowBlockCount = 0;
        int pumpkinCount = 0;
        for (int i = 0; i < 36; i++) {
            Item item = mc.player.getInventory().getStack(i).getItem();
            if (item == Items.SNOW_BLOCK) snowBlockCount += mc.player.getInventory().getStack(i).getCount();
            if (item == Items.CARVED_PUMPKIN) pumpkinCount += mc.player.getInventory().getStack(i).getCount();
        }

        if (snowBlockCount < 2) {
            error("Not enough snow blocks (need at least 2).");
            toggle();
            return;
        }

        if (pumpkinCount < 1) {
            error("Need at least 1 carved pumpkin.");
            toggle();
            return;
        }

        for (int i = 0; i < 9; i++) {
            Item item = mc.player.getInventory().getStack(i).getItem();
            if (item == Items.SNOW_BLOCK) {
                mc.player.getInventory().selectedSlot = i;
                break;
            }
        }
    }
    public void onActivateIronman() {
        ironmanBlocks.clear();
        waitingForBreak.clear();
        index = 0;
        delay = 0;
        loopDelayTimer = 0;
        waitingForNextLoop = false;
        waitingForSlotSync = false;

        Vec3d dir = mc.player.getRotationVec(1.0f);
        Vec3d horizontal = new Vec3d(dir.x, 0, dir.z).normalize().multiply(2.0);
        Vec3d target = mc.player.getPos().add(horizontal).add(0, 3, 0);
        BlockPos basePos = BlockPos.ofFloored(target);

        // Iron Golem body structure
        ironmanBlocks.add(basePos);                     // center iron block
        ironmanBlocks.add(basePos.south());                // upper iron block
        ironmanBlocks.add(basePos.west());              // left arm
        ironmanBlocks.add(basePos.east());              // right arm
        ironmanBlocks.add(basePos.north());               // pumpkin head

        int ironCount = 0;
        int pumpkinCount = 0;
        for (int i = 0; i < 36; i++) {
            Item item = mc.player.getInventory().getStack(i).getItem();
            if (item == Items.IRON_BLOCK) ironCount += mc.player.getInventory().getStack(i).getCount();
            if (item == Items.CARVED_PUMPKIN) pumpkinCount += mc.player.getInventory().getStack(i).getCount();
        }

        if (ironCount < 4) {
            error("Not enough iron blocks (need at least 4).");
            toggle();
            return;
        }

        if (pumpkinCount < 1) {
            error("Need at least 1 carved pumpkin.");
            toggle();
            return;
        }

        for (int i = 0; i < 9; i++) {
            Item item = mc.player.getInventory().getStack(i).getItem();
            if (item == Items.IRON_BLOCK) {
                mc.player.getInventory().selectedSlot = i;
                break;
            }
        }
    }
    public void onActivateWither() {
        
        witherBlocks.clear();
        waitingForBreak.clear();
        index = 0;
        delay = 0;
        waitingForSlotSync = false;

        Vec3d dir = mc.player.getRotationVec(1.0f);
        Vec3d horizontal = new Vec3d(dir.x, 0, dir.z).normalize().multiply(2.0);
        Vec3d target = mc.player.getPos().add(horizontal).add(0, 2, 0);
        BlockPos basePos = BlockPos.ofFloored(target);

        // Wither body structure
        witherBlocks.add(basePos);
        witherBlocks.add(basePos.west());
        witherBlocks.add(basePos.east());
        witherBlocks.add(basePos.down());
        witherBlocks.add(basePos.up().west());
        witherBlocks.add(basePos.up());
        witherBlocks.add(basePos.up().east());

        int soulCount = 0;
        int skullCount = 0;
        for (int i = 0; i < 36; i++) {
            Item item = mc.player.getInventory().getStack(i).getItem();
            if (item == Items.SOUL_SAND) soulCount += mc.player.getInventory().getStack(i).getCount();
            if (item == Items.WITHER_SKELETON_SKULL) skullCount += mc.player.getInventory().getStack(i).getCount();
        }

        if (soulCount < 4) {
            error("Not enough soul sand (need at least 4).");
            toggle();
            return;
        }

        if (skullCount < 3) {
            error("Need at least 3 Wither Skeleton Skulls.");
            toggle();
            return;
        }

        for (int i = 0; i < 9; i++) {
            Item item = mc.player.getInventory().getStack(i).getItem();
            if (item == Items.SOUL_SAND) {
                mc.player.getInventory().selectedSlot = i;
                break;
            }
        }
    }
    @Override
    public void onDeactivate() {
        switch (type.get()) {
            case Snowman -> onDeactivateSnowman();
            case Ironman -> onDeactivateIronman();
            case Wither -> onDeactivateWither();
        }
    }
    public void onDeactivateSnowman() {
        snowmanBlocks.clear();
        waitingForBreak.clear();
        index = 0;
        delay = 0;
        loopDelayTimer = 0;
        waitingForNextLoop = false;
        waitingForSlotSync = false;
    }
    
    public void onDeactivateIronman() {
        ironmanBlocks.clear();
        waitingForBreak.clear();
        index = 0;
        delay = 0;
        loopDelayTimer = 0;
        waitingForNextLoop = false;
        waitingForSlotSync = false;
    }
    
    public void onDeactivateWither() {
        witherBlocks.clear();
        waitingForBreak.clear();
        index = 0;
        delay = 0;
        waitingForSlotSync = false;
    }

    @EventHandler
    private void onTick(TickEvent.Post event) {
    switch (type.get()) {
        case Snowman -> onTickSnowman(event);
        case Ironman -> onTickIronman(event);
        case Wither -> onTickWither(event);
    }
}
    
     private void onTickSnowman(TickEvent.Post event) {
    if (mc.player == null || mc.world == null) return;

    if (waitingForNextLoop) {
        loopDelayTimer++;
        if (loopDelayTimer >= loopDelay.get()) {
            waitingForNextLoop = false;
            onActivate();
        }
        return;
    }

    if (waitingForSlotSync) {
        waitingForSlotSync = false;
        return;
    }

    if (index >= snowmanBlocks.size()) {
        info("Snowman complete.");
        if (continuous.get()) {
            waitingForNextLoop = true;
            loopDelayTimer = 0;
        } else {
            toggle();
        }
        return;
    }

    delay++;
    if (delay < placeDelay.get()) return;

    for (int i = 0; i < blocksPerTick.get() && index < snowmanBlocks.size(); i++) {
        BlockPos pos = snowmanBlocks.get(index);

        if (!mc.world.getBlockState(pos).isReplaceable()) {
            if (!waitingForBreak.contains(pos)) {
                mc.interactionManager.attackBlock(pos, Direction.UP);
                mc.player.swingHand(Hand.MAIN_HAND);
                waitingForBreak.add(pos);
            }
            return;
        }

        waitingForBreak.remove(pos);

        Item needed = (index < 2) ? Items.SNOW_BLOCK : Items.CARVED_PUMPKIN;

        int slotToSelect = -1;
        boolean foundItem = false;
        for (int slot = 0; slot < 9; slot++) {
            if (mc.player.getInventory().getStack(slot).getItem() == needed) {
                slotToSelect = slot;
                foundItem = true;
                break;
            }
        }

        if (!foundItem) {
            error("Missing required block: " + needed.getName().getString());
            toggle();
            return;
        }

        if (mc.player.getInventory().selectedSlot != slotToSelect) {
            mc.player.getInventory().selectedSlot = slotToSelect;
            waitingForSlotSync = true;
            return;
        }

        if (!(mc.player.getMainHandStack().getItem() instanceof BlockItem)) {
            error("Main hand item is not a block.");
            toggle();
            return;
        }

        BlockHitResult bhr = new BlockHitResult(Vec3d.ofCenter(pos), Direction.UP, pos, false);

        mc.player.networkHandler.sendPacket(new PlayerActionC2SPacket(
            PlayerActionC2SPacket.Action.SWAP_ITEM_WITH_OFFHAND, BlockPos.ORIGIN, Direction.DOWN));
        mc.player.networkHandler.sendPacket(new PlayerInteractBlockC2SPacket(
            Hand.OFF_HAND, bhr, mc.player.currentScreenHandler.getRevision() + 2));
        mc.player.networkHandler.sendPacket(new PlayerActionC2SPacket(
            PlayerActionC2SPacket.Action.SWAP_ITEM_WITH_OFFHAND, BlockPos.ORIGIN, Direction.DOWN));
        mc.player.swingHand(Hand.MAIN_HAND);

        index++;
    }

    delay = 0;
}

    private void onTickIronman(TickEvent.Post event) {
        
        if (mc.player == null || mc.world == null) return;

        if (waitingForNextLoop) {
            loopDelayTimer++;
            if (loopDelayTimer >= loopDelay.get()) {
                waitingForNextLoop = false;
                onActivate();
            }
            return;
        }

        if (waitingForSlotSync) {
            waitingForSlotSync = false;
            return;
        }

        if (index >= ironmanBlocks.size()) {
            info("Iron Golem complete.");
            if (continuous.get()) {
                waitingForNextLoop = true;
                loopDelayTimer = 0;
            } else {
                toggle();
            }
            return;
        }

        delay++;
        if (delay < placeDelay.get()) return;

        for (int i = 0; i < blocksPerTick.get() && index < ironmanBlocks.size(); i++) {
            BlockPos pos = ironmanBlocks.get(index);

            if (!mc.world.getBlockState(pos).isReplaceable()) {
                if (!waitingForBreak.contains(pos)) {
                    mc.interactionManager.attackBlock(pos, Direction.UP);
                    mc.player.swingHand(Hand.MAIN_HAND);
                    waitingForBreak.add(pos);
                }
                return;
            }

            waitingForBreak.remove(pos);

            Item needed = (index < 4) ? Items.IRON_BLOCK : Items.CARVED_PUMPKIN;

            int slotToSelect = -1;
            boolean foundItem = false;
            for (int slot = 0; slot < 9; slot++) {
                if (mc.player.getInventory().getStack(slot).getItem() == needed) {
                    slotToSelect = slot;
                    foundItem = true;
                    break;
                }
            }

            if (!foundItem) {
                error("Missing required block: " + needed.getName().getString());
                toggle();
                return;
            }

            if (mc.player.getInventory().selectedSlot != slotToSelect) {
                mc.player.getInventory().selectedSlot = slotToSelect;
                waitingForSlotSync = true;
                return;
            }

            if (!(mc.player.getMainHandStack().getItem() instanceof BlockItem)) {
                error("Main hand item is not a block.");
                toggle();
                return;
            }

            BlockHitResult bhr = new BlockHitResult(Vec3d.ofCenter(pos), Direction.UP, pos, false);

            mc.player.networkHandler.sendPacket(new PlayerActionC2SPacket(
                PlayerActionC2SPacket.Action.SWAP_ITEM_WITH_OFFHAND, BlockPos.ORIGIN, Direction.DOWN));
            mc.player.networkHandler.sendPacket(new PlayerInteractBlockC2SPacket(
                Hand.OFF_HAND, bhr, mc.player.currentScreenHandler.getRevision() + 2));
            mc.player.networkHandler.sendPacket(new PlayerActionC2SPacket(
                PlayerActionC2SPacket.Action.SWAP_ITEM_WITH_OFFHAND, BlockPos.ORIGIN, Direction.DOWN));
            mc.player.swingHand(Hand.MAIN_HAND);

            index++;
        }

        delay = 0;
}
    private void onTickWither(TickEvent.Post event) {
        
        if (mc.player == null || mc.world == null) return;

        if (waitingForSlotSync) {
            waitingForSlotSync = false;
            return;
        }

        if (index >= witherBlocks.size()) {
            info("Wither complete.");
            toggle();
            return;
        }

        delay++;
        if (delay < placeDelay.get()) return;

        for (int i = 0; i < blocksPerTick.get() && index < witherBlocks.size(); i++) {
            BlockPos pos = witherBlocks.get(index);

            if (!mc.world.getBlockState(pos).isReplaceable()) {
                if (!waitingForBreak.contains(pos)) {
                    mc.interactionManager.attackBlock(pos, Direction.UP);
                    mc.player.swingHand(Hand.MAIN_HAND);
                    waitingForBreak.add(pos);
                }
                return;
            }

            waitingForBreak.remove(pos);

            Item needed = (index < 4) ? Items.SOUL_SAND : Items.WITHER_SKELETON_SKULL;

            int slotToSelect = -1;
            boolean foundItem = false;
            for (int slot = 0; slot < 9; slot++) {
                if (mc.player.getInventory().getStack(slot).getItem() == needed) {
                    slotToSelect = slot;
                    foundItem = true;
                    break;
                }
            }

            if (!foundItem) {
                error("Missing required block: " + needed.getName().getString());
                toggle();
                return;
            }

            if (mc.player.getInventory().selectedSlot != slotToSelect) {
                mc.player.getInventory().selectedSlot = slotToSelect;
                waitingForSlotSync = true;
                return;
            }

            if (!(mc.player.getMainHandStack().getItem() instanceof BlockItem)) {
                error("Main hand item is not a block.");
                toggle();
                return;
            }

            BlockPos placeOn = pos;
            Direction direction = Direction.UP;

            if (needed == Items.WITHER_SKELETON_SKULL) {
                placeOn = pos.down();
                direction = Direction.UP;
            }

            BlockHitResult bhr = new BlockHitResult(Vec3d.ofCenter(placeOn), direction, placeOn, false);


            mc.player.networkHandler.sendPacket(new PlayerActionC2SPacket(
                PlayerActionC2SPacket.Action.SWAP_ITEM_WITH_OFFHAND, BlockPos.ORIGIN, Direction.DOWN));
            mc.player.networkHandler.sendPacket(new PlayerInteractBlockC2SPacket(
                Hand.OFF_HAND, bhr, mc.player.currentScreenHandler.getRevision() + 2));
            mc.player.networkHandler.sendPacket(new PlayerActionC2SPacket(
                PlayerActionC2SPacket.Action.SWAP_ITEM_WITH_OFFHAND, BlockPos.ORIGIN, Direction.DOWN));
            mc.player.swingHand(Hand.MAIN_HAND);

            index++;
        }

        delay = 0;
}

    @EventHandler
    private void onRender(Render3DEvent event) {
         if (!render.get()) return;
        
        switch (type.get()) {
        case Snowman -> onRenderSnowman(event);
        case Ironman -> onRenderIronman(event);
        case Wither  -> onRenderWither(event);
    }
}

    private void onRenderSnowman(Render3DEvent event) {
        if (!render.get()) return;
        for (int i = index; i < snowmanBlocks.size(); i++) {
            BlockPos pos = snowmanBlocks.get(i);
            event.renderer.box(pos, sideColor.get(), lineColor.get(), shapeMode.get(), 0);
        }
    }
    
    private void onRenderIronman(Render3DEvent event) {
        if (!render.get()) return;
        for (int i = index; i < ironmanBlocks.size(); i++) {
            BlockPos pos = ironmanBlocks.get(i);
            event.renderer.box(pos, sideColor.get(), lineColor.get(), shapeMode.get(), 0);
        }
    }
    
    private void onRenderWither(Render3DEvent event) {
        if (!render.get()) return;
        for (int i = index; i < witherBlocks.size(); i++) {
            BlockPos pos = witherBlocks.get(i);
            event.renderer.box(pos, sideColor.get(), lineColor.get(), shapeMode.get(), 0);
        }
    }
}
