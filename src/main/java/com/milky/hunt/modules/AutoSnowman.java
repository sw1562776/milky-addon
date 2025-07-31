package your.package.name;

import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.block.Blocks;
import net.minecraft.item.BlockItem;
import net.minecraft.item.Items;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec2f;
import net.minecraft.util.math.Vec3d;

import java.util.ArrayList;
import java.util.List;

public class AutoSnowman extends Module {
    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    private final Setting<Boolean> continuous = sgGeneral.add(new BoolSetting.Builder()
        .name("continuous")
        .description("Continuously build snow golems.")
        .defaultValue(false)
        .build()
    );

    private final Setting<Integer> loopDelay = sgGeneral.add(new IntSetting.Builder()
        .name("loop-delay")
        .description("Delay between building snow golems in continuous mode (ticks).")
        .defaultValue(20)
        .sliderRange(0, 200)
        .visible(continuous::get)
        .build()
    );

    private final Setting<Integer> placeDelay = sgGeneral.add(new IntSetting.Builder()
        .name("place-delay")
        .description("Delay between placing each block (ticks).")
        .defaultValue(2)
        .sliderRange(0, 20)
        .build()
    );

    private final List<BlockPos> snowmanBlocks = new ArrayList<>();
    private final List<BlockPos> waitingForBreak = new ArrayList<>();

    private int index = 0;
    private int delay = 0;
    private boolean waitingForNextLoop = false;
    private int loopDelayTimer = 0;

    public AutoSnowman() {
        super(MainAddon.CATEGORY, "auto-snowman", "Automatically builds snow golems.");
    }

    @Override
    public void onActivate() {
        snowmanBlocks.clear();
        waitingForBreak.clear();
        index = 0;
        delay = 0;
        loopDelayTimer = 0;
        waitingForNextLoop = false;

        if (mc.player == null || mc.world == null) {
            error("Player or world not available.");
            toggle();
            return;
        }

        // 计算水平朝向偏移
        Vec3d lookVec = mc.player.getRotationVec(1.0f);
        Vec2f horizontal = new Vec2f((float) lookVec.x, (float) lookVec.z);
        if (horizontal.lengthSquared() < 1e-5) {
            error("Can't determine direction.");
            toggle();
            return;
        }

        horizontal = horizontal.normalize().multiply(2);

        BlockPos basePos = new BlockPos(
            mc.player.getX() + horizontal.x,
            mc.player.getY() - 1,
            mc.player.getZ() + horizontal.y
        );

        snowmanBlocks.add(basePos);             // 雪块1
        snowmanBlocks.add(basePos.up());        // 雪块2
        snowmanBlocks.add(basePos.up(2));       // 南瓜头
    }

    @Override
    public void onDeactivate() {
        snowmanBlocks.clear();
        waitingForBreak.clear();
        index = 0;
        delay = 0;
        loopDelayTimer = 0;
        waitingForNextLoop = false;
    }

    @EventHandler
    private void onTick(TickEvent.Post event) {
        if (index >= snowmanBlocks.size()) {
            if (continuous.get()) {
                if (!waitingForNextLoop) {
                    waitingForNextLoop = true;
                    loopDelayTimer = 0;
                    info("Snowman complete. Waiting...");
                } else {
                    loopDelayTimer++;
                    if (loopDelayTimer >= loopDelay.get()) {
                        waitingForNextLoop = false;
                        onActivate();
                    }
                }
            } else {
                info("Snowman complete. AutoSnowman disabled.");
                toggle();
            }
            return;
        }

        if (delay > 0) {
            delay--;
            return;
        }

        BlockPos pos = snowmanBlocks.get(index);
        if (!canPlace(pos)) {
            waitingForBreak.add(pos);
            index++;
            return;
        }

        if (placeBlock(pos)) {
            delay = placeDelay.get();
            index++;
        }
    }

    private boolean canPlace(BlockPos pos) {
        return mc.world.getBlockState(pos).isReplaceable();
    }

    private boolean placeBlock(BlockPos pos) {
        if (index == 2) {
            // 南瓜头
            int slot = findItemSlot(Items.CARVED_PUMPKIN);
            if (slot == -1) {
                warning("Missing carved pumpkin.");
                return false;
            }
            return placeFromSlot(slot, pos);
        } else {
            // 雪块
            int slot = findBlockSlot(Blocks.SNOW_BLOCK);
            if (slot == -1) {
                warning("Missing snow block.");
                return false;
            }
            return placeFromSlot(slot, pos);
        }
    }

    private int findBlockSlot(Blocks block) {
        for (int i = 0; i < 9; i++) {
            if (mc.player.getInventory().getStack(i).getItem() instanceof BlockItem bi) {
                if (bi.getBlock() == block) return i;
            }
        }
        return -1;
    }

    private int findItemSlot(Items item) {
        for (int i = 0; i < 9; i++) {
            if (mc.player.getInventory().getStack(i).getItem() == item) return i;
        }
        return -1;
    }

    private boolean placeFromSlot(int slot, BlockPos pos) {
        if (mc.interactionManager == null) return false;

        int prevSlot = mc.player.getInventory().selectedSlot;
        mc.player.getInventory().selectedSlot = slot;
        mc.interactionManager.interactBlock(mc.player, mc.world, mc.player.getStackInHand(mc.player.getActiveHand()), pos, Direction.UP);
        mc.player.getInventory().selectedSlot = prevSlot;
        return true;
    }
}
