package com.stash.hunt.modules;

import com.stash.hunt.Addon;
import meteordevelopment.meteorclient.MeteorClient;
import meteordevelopment.meteorclient.events.packets.InventoryEvent;
import meteordevelopment.meteorclient.events.render.Render3DEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.gui.GuiTheme;
import meteordevelopment.meteorclient.gui.widgets.WWidget;
import meteordevelopment.meteorclient.gui.widgets.containers.WVerticalList;
import meteordevelopment.meteorclient.gui.widgets.pressable.WButton;
import meteordevelopment.meteorclient.renderer.ShapeMode;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.render.RenderUtils;
import meteordevelopment.meteorclient.utils.render.color.SettingColor;
import meteordevelopment.meteorclient.utils.world.BlockIterator;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.block.*;
import net.minecraft.block.enums.ChestType;
import net.minecraft.client.gui.screen.ingame.GenericContainerScreen;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.screen.slot.Slot;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;
import net.minecraft.util.collection.DefaultedList;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;

import java.io.File;
import java.io.FileWriter;
import java.io.Writer;
import java.text.SimpleDateFormat;
import java.util.*;
import com.google.gson.JsonObject;
import java.io.IOException;

import com.google.gson.*;
import net.minecraft.registry.Registries;
import net.minecraft.util.Identifier;


import static meteordevelopment.meteorclient.utils.Utils.getItemsInContainerItem;
import static meteordevelopment.meteorclient.utils.Utils.hasItems;

public class ChestIndex extends Module
{
    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    private final Setting<Integer> searchRange = sgGeneral.add(new IntSetting.Builder()
        .name("Range")
        .description("Search chests within this range of the player.")
        .defaultValue(4)
        .min(1)
        .max(10)
        .sliderRange(1, 10)
        .build()
    );

    private final Setting<Integer> delay = sgGeneral.add(new IntSetting.Builder()
        .name("Delay")
        .description("Delay in ticks between chest interactions.")
        .defaultValue(5)
        .min(0)
        .max(40)
        .build()
    );

    private final Setting<DisplayType> displayType = sgGeneral.add(new EnumSetting.Builder<DisplayType>()
        .name("Display Type (Only for Chat Output)")
        .description("Unit to use when displaying results.")
        .defaultValue(DisplayType.ItemCount)
        .build()
    );

    private final Setting<Boolean> highlightSearched = sgGeneral.add(new BoolSetting.Builder()
        .name("Highlight Searched Blocks")
        .defaultValue(true)
        .build()
    );

    private final Setting<ShapeMode> shapeMode = sgGeneral.add(new EnumSetting.Builder<ShapeMode>()
        .name("Box Mode")
        .description("How the shape for the bounding box is rendered.")
        .defaultValue(ShapeMode.Both)
        .build()
    );

    private final Setting<SettingColor> sideColor = sgGeneral.add(new ColorSetting.Builder()
        .name("Side Color")
        .description("The side color of the bounding box.")
        .defaultValue(new SettingColor(16,106,144, 100))
        .build()
    );

    private final Setting<SettingColor> lineColor = sgGeneral.add(new ColorSetting.Builder()
        .name("Line Color")
        .description("The line color of the bounding box.")
        .defaultValue(new SettingColor(16,106,144, 255))
        .build()
    );


    private HashSet<BlockPos> searched;

    private HashMap<String, Integer> blocks;

    private boolean awaiting;

    // up to 2 if it's a double chest
    private BlockPos[] currPos;

    private int tickCounter;

    public ChestIndex()
    {
        super(Addon.CATEGORY, "ChestIndex", "Displays a total count of blocks in your chests (buggy and will probably break for lots of chests)");
        searched = new HashSet<BlockPos>();
        blocks = new HashMap<String, Integer>();
    }

    private void saveToJson(Gson gson, String fileName, JsonObject json) throws IOException
    {
        String timeStamp = new SimpleDateFormat("yyyy.MM.dd.HH.mm.ss").format(new java.util.Date());
        File file = new File(new File(MeteorClient.FOLDER, "ChestIndex"), fileName + "-" + timeStamp + ".json");
        file.getParentFile().mkdirs();
        Writer writer = new FileWriter(file);
        gson.toJson(json, writer);
        writer.close();
    }

    private List<Map.Entry<String, Integer>> sortBlocks()
    {
        // Sort blocks by value (count) in descending order
        return blocks.entrySet().stream()
            .sorted((a, b) -> b.getValue().compareTo(a.getValue()))
            .toList();
    }

    @Override
    public WWidget getWidget(GuiTheme theme)
    {
        WVerticalList list = theme.verticalList();



        WButton showBlocks = list.add(theme.button("Display the blocks logged")).widget();
        showBlocks.action = () -> {
            info("showing blocks");
            ArrayList<Map.Entry<String, Integer>> blockList = new ArrayList<>(blocks.entrySet());
            blockList.sort(Map.Entry.comparingByValue());
            Collections.reverse(blockList);

            double factor = switch (displayType.get()) {
                case DubCount -> 64.0 * 27.0 * 27.0 * 2.0;
                case ShulkerCount -> 64.0 * 27.0;
                default -> 1.0;
            };
            for (Map.Entry<String, Integer> block : blockList)
            {
                info(block.getKey() + ": " + block.getValue() / factor);
            }
        };

        WButton clearBlocks = list.add(theme.button("Clear Blocks")).widget();
        clearBlocks.action = () -> {
            searched = new HashSet<>();
            blocks = new HashMap<>();
        };

        // Create a Gson instance for pretty printing without escaping characters
        final Gson gson = new GsonBuilder()
            .setPrettyPrinting()
            .disableHtmlEscaping() // This prevents escaping characters like `'`
            .create();

        // Button: Export (ID)
        WButton exportByID = list.add(theme.button("Export (ID)")).widget();
        exportByID.action = () -> {
            try {
                List<Map.Entry<String, Integer>> sortedBlocks = sortBlocks();

                JsonObject json = new JsonObject();
                for (Map.Entry<String, Integer> entry : sortedBlocks) {
                    json.add(entry.getKey(), new JsonPrimitive(entry.getValue()));
                }
                saveToJson(gson, "blocks_id", json);
                info("Exported blocks to ChestIndex/blocks_id.json using IDs (sorted, pretty-printed).");
            } catch (IOException e) {
                error("Failed to export blocks (ID): " + e.getMessage());
            }
        };

        // Button: Export (Name)
        WButton exportByName = list.add(theme.button("Export (Name)")).widget();
        exportByName.action = () -> {
            try {
                List<Map.Entry<String, Integer>> sortedBlocks = sortBlocks();

                JsonObject json = new JsonObject();
                for (Map.Entry<String, Integer> entry : sortedBlocks) {
                    // Convert key to Identifier
                    Identifier identifier = Identifier.tryParse(entry.getKey());

                    // Get the item from the registry
                    Item item = Registries.ITEM.get(identifier);

                    // Get the display name (human-readable)
                    String displayName = item.getName().getString();
                    json.add(displayName, new JsonPrimitive(entry.getValue()));
                }
                saveToJson(gson, "blocks_name", json);
                info("Exported blocks to ChestIndex/blocks_name.json using human-readable names (sorted, pretty-printed).");
            } catch (IOException e) {
                error("Failed to export blocks (Name): " + e.getMessage());
            }
        };

        // Button: Export (Dubs)
        WButton exportDubs = list.add(theme.button("Export (Dubs)")).widget();
        exportDubs.action = () -> {
            try {
                List<Map.Entry<String, Integer>> sortedBlocks = sortBlocks();

                JsonObject json = new JsonObject();
                for (Map.Entry<String, Integer> entry : sortedBlocks) {
                    // Convert key to Identifier
                    Identifier identifier = Identifier.tryParse(entry.getKey());

                    // Get the item from the registry
                    Item item = Registries.ITEM.get(identifier);

                    // Get the display name (human-readable)
                    String displayName = item.getName().getString();

                    // Calculate stack size
                    int stackSize = item.getMaxCount();

                    // Correct calculation for Dubs (shulkers full of items in dubs)
                    double dubs = entry.getValue() / (stackSize * 27.0 * 54.0); // 1458 slots per double chest
                    json.add(displayName, new JsonPrimitive(String.format("%.2f", dubs)));
                }

                // Write the pretty-printed JSON
                saveToJson(gson, "blocks_dubs", json);
                info("Exported blocks to ChestIndex/blocks_dubs.json (calculated as dubs with shulkers).");
            } catch (IOException e) {
                error("Failed to export blocks (Dubs): " + e.getMessage());
            }
        };

        WButton exportShulkers = list.add(theme.button("Export (Shulkers)")).widget();
        exportShulkers.action = () -> {
            try {
                List<Map.Entry<String, Integer>> sortedBlocks = sortBlocks();

                JsonObject json = new JsonObject();
                for (Map.Entry<String, Integer> entry : sortedBlocks) {
                    try {
                        // Convert key to Identifier
                        Identifier identifier = Identifier.tryParse(entry.getKey());

                        // Get the item from the registry
                        Item item = Registries.ITEM.get(identifier);

                        // Get the display name (human-readable)
                        String displayName =item.getName().getString();

                        // Calculate stack size
                        int stackSize = item.getMaxCount();

                        // Correct calculation for Shulkers
                        double shulkers = entry.getValue() / (stackSize * 27.0); // 27 slots per shulker
                        json.add(displayName, new JsonPrimitive(String.format("%.2f", shulkers)));
                    } catch (Exception e) {
                        json.add(entry.getKey(), new JsonPrimitive("0.00"));
                    }
                }

                // Write the pretty-printed JSON
                saveToJson(gson, "blocks_shulkers", json);
                info("Exported blocks to ChestIndex/blocks_shulkers.json (calculated as shulkers, by name).");
            } catch (IOException e) {
                error("Failed to export blocks (Shulkers): " + e.getMessage());
            }
        };

        return list;
    }



    @EventHandler
    private void onRender(Render3DEvent event) {
        if (highlightSearched.get()){
            for (BlockPos blockPos : searched)
            {
                RenderUtils.renderTickingBlock(blockPos.toImmutable(), sideColor.get(), lineColor.get(), shapeMode.get(), 0, 8, true, false);
            }
        }
    }

    @Override
    public void onActivate()
    {
//        searched = new HashSet<BlockPos>();
//        blocks = new HashMap<String, Integer>();
        awaiting = false;
        currPos = new BlockPos[2];
        tickCounter = 0;
    }

    // only open new chest if awaiting = false;
    // after opening set awaiting to true
    // when inventory opened set awaiting to false;

    @EventHandler
    private void onTick(TickEvent.Pre event)
    {
        if (mc.currentScreen instanceof GenericContainerScreen) return;

        if (tickCounter < delay.get())
        {
            tickCounter++;
            return;
        }
        else
        {
            tickCounter = 0;
        }


        BlockIterator.register(searchRange.get(), searchRange.get(), (blockPos, blockState) ->
        {
            // might be too many packets from not checking if menu already open
            if (!awaiting &&
                !searched.contains(blockPos.toImmutable()) &&
                (blockState.getBlock() == Blocks.CHEST ||
                    blockState.getBlock() == Blocks.TRAPPED_CHEST ||
                    blockState.getBlock() == Blocks.BARREL ||
                    blockState.getBlock() instanceof ShulkerBoxBlock))
            {




                Vec3d vec = new Vec3d(blockPos.getX(), blockPos.getY(), blockPos.getZ());
                BlockHitResult hitResult = new BlockHitResult(vec, Direction.UP, blockPos, false);
                if (mc.interactionManager.interactBlock(mc.player, Hand.MAIN_HAND, hitResult) == ActionResult.SUCCESS)
                {
                    awaiting = true;
                    mc.player.swingHand(Hand.MAIN_HAND);
                    // find way to see when the interaction fails
                    info("interacted");
                    currPos[0] = blockPos.toImmutable();
                    if (blockState.getBlock() == Blocks.CHEST || blockState.getBlock() == Blocks.TRAPPED_CHEST)
                    {
                        ChestType chestType = blockState.get(ChestBlock.CHEST_TYPE);
                        if (chestType == ChestType.LEFT || chestType == ChestType.RIGHT)
                        {
                            Direction facing = blockState.get(ChestBlock.FACING);
                            BlockPos otherPartPos = blockPos.offset(chestType == ChestType.LEFT ? facing.rotateYClockwise() : facing.rotateYCounterclockwise());

                            currPos[1] = otherPartPos;
                        }
                    }
                }
            }
        });
    }
    @EventHandler
    private void onInventory(InventoryEvent event) {
        ScreenHandler handler = mc.player.currentScreenHandler;
        awaiting = false;
        for (BlockPos blockPos : currPos)
        {
            if (blockPos != null) searched.add(blockPos);
        }
        DefaultedList<Slot> slots = handler.slots;
        for (int i = 0; i < slots.size() - 36; i++)
        {
            ItemStack stack = slots.get(i).getStack();
            if (!stack.isEmpty())
            {

                if (hasItems(stack))
                {
                    ItemStack[] items = new ItemStack[27];
                    getItemsInContainerItem(stack, items);
                    for (ItemStack item : items)
                    {
                        if (!item.isEmpty())
                        {
                            String nameOfblock = item.getItem().toString();
                            blocks.compute(nameOfblock, (k, currentCount) -> (currentCount == null) ? item.getCount() : currentCount + item.getCount());
                        }
                    }
                }
                else
                {
                    String nameOfblock = stack.getItem().toString();
                    blocks.compute(nameOfblock, (k, currentCount) -> (currentCount == null) ? stack.getCount() : currentCount + stack.getCount());
                }

            }
        }
        mc.player.closeHandledScreen();
    }

    private enum DisplayType
    {
        ItemCount,
        DubCount,
        ShulkerCount
    }
}
