package com.milky.hunt.modules;

import com.milky.hunt.Addon;
import meteordevelopment.meteorclient.events.render.Render3DEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.renderer.ShapeMode;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.entity.EntityUtils;
import meteordevelopment.meteorclient.utils.player.ChatUtils;
import meteordevelopment.meteorclient.utils.player.PlayerUtils;
import meteordevelopment.meteorclient.utils.render.RenderUtils;
import meteordevelopment.meteorclient.utils.render.color.Color;
import meteordevelopment.meteorclient.utils.render.color.SettingColor;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.enchantment.Enchantment;
import net.minecraft.enchantment.Enchantments;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.entity.ItemEntity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.item.*;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.tag.ItemTags;
import net.minecraft.text.Text;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.MathHelper;

import java.util.*;

public class ValuableESP extends Module {
    // Only two tabs/groups: Items and Mobs (general + colors combined into each).
    private final SettingGroup sgItems = settings.createGroup("Items");
    private final SettingGroup sgMobs  = settings.createGroup("Mobs");

    // Shared defaults
    private final List<Item> defaultPlayerItems = new ArrayList<>(List.of(
        Items.DIAMOND_HELMET,
        Items.DIAMOND_CHESTPLATE,
        Items.DIAMOND_LEGGINGS,
        Items.DIAMOND_BOOTS,
        Items.NETHERITE_HELMET,
        Items.NETHERITE_CHESTPLATE,
        Items.NETHERITE_LEGGINGS,
        Items.NETHERITE_BOOTS,
        Items.ELYTRA,
        Items.MACE,
        Items.TRIDENT,
        Items.DIAMOND_SWORD,
        Items.DIAMOND_AXE,
        Items.DIAMOND_PICKAXE,
        Items.DIAMOND_SHOVEL,
        Items.DIAMOND_HOE,
        Items.NETHERITE_SWORD,
        Items.NETHERITE_AXE,
        Items.NETHERITE_PICKAXE,
        Items.NETHERITE_SHOVEL,
        Items.NETHERITE_HOE,
        Items.ENCHANTED_GOLDEN_APPLE,
        Items.END_CRYSTAL,
        Items.ENDER_CHEST,
        Items.TOTEM_OF_UNDYING,
        Items.EXPERIENCE_BOTTLE,
        Items.SHULKER_BOX,
        Items.RED_SHULKER_BOX,
        Items.ORANGE_SHULKER_BOX,
        Items.YELLOW_SHULKER_BOX,
        Items.LIME_SHULKER_BOX,
        Items.GREEN_SHULKER_BOX,
        Items.CYAN_SHULKER_BOX,
        Items.LIGHT_BLUE_SHULKER_BOX,
        Items.BLUE_SHULKER_BOX,
        Items.PURPLE_SHULKER_BOX,
        Items.MAGENTA_SHULKER_BOX,
        Items.PINK_SHULKER_BOX,
        Items.WHITE_SHULKER_BOX,
        Items.LIGHT_GRAY_SHULKER_BOX,
        Items.GRAY_SHULKER_BOX,
        Items.BROWN_SHULKER_BOX,
        Items.BLACK_SHULKER_BOX
    ));

    public ValuableESP() {
        super(Addon.MilkyModCategory, "ValuableESP", "Highlights high-value dropped items and mobs likely wearing player gear.");
    }

    // ---------------- Items tab (also contains shared render settings) ----------------
    public final Setting<ShapeMode> shapeMode = sgItems.add(new EnumSetting.Builder<ShapeMode>()
        .name("shape-mode")
        .description("How the shapes are rendered.")
        .defaultValue(ShapeMode.Both)
        .build()
    );

    public final Setting<Double> fillOpacity = sgItems.add(new DoubleSetting.Builder()
        .name("fill-opacity")
        .description("The opacity of the shape fill.")
        .defaultValue(0.3)
        .range(0, 1)
        .sliderMax(1)
        .build()
    );

    private final Setting<Double> fadeDistance = sgItems.add(new DoubleSetting.Builder()
        .name("fade-distance")
        .description("The distance from an entity where the color begins to fade.")
        .defaultValue(3)
        .min(0)
        .sliderMax(12)
        .build()
    );

    private final Setting<List<Item>> itemChecker = sgItems.add(new ItemListSetting.Builder()
        .name("item-checker")
        .description("Dropped items to detect.")
        .defaultValue(defaultPlayerItems)
        .build()
    );

    public final Setting<Boolean> enchants = sgItems.add(new BoolSetting.Builder()
        .name("enforce-item-enchants")
        .description("If enabled, tools/armor must be enchanted to be detected (items + mobs).")
        .defaultValue(true)
        .build()
    );

    public final Setting<Boolean> certainenchants = sgItems.add(new BoolSetting.Builder()
        .name("find-certain-item-enchants")
        .description("If enabled, tools/armor must have ALL enchants from the relevant list below.")
        .defaultValue(false)
        .visible(enchants::get)
        .build()
    );

    private final Setting<Set<RegistryKey<Enchantment>>> toolenchants = sgItems.add(new EnchantmentListSetting.Builder()
        .name("mining-tool-enchants")
        .description("Required enchantments for mining tools.")
        .visible(() -> enchants.get() && certainenchants.get())
        .defaultValue(Enchantments.EFFICIENCY, Enchantments.UNBREAKING, Enchantments.MENDING)
        .build()
    );

    private final Setting<Set<RegistryKey<Enchantment>>> swordenchants = sgItems.add(new EnchantmentListSetting.Builder()
        .name("sword-enchants")
        .description("Required enchantments for swords.")
        .visible(() -> enchants.get() && certainenchants.get())
        .defaultValue(Enchantments.UNBREAKING, Enchantments.MENDING)
        .build()
    );

    private final Setting<Set<RegistryKey<Enchantment>>> armorenchants = sgItems.add(new EnchantmentListSetting.Builder()
        .name("armor-enchants")
        .description("Required enchantments for armor.")
        .visible(() -> enchants.get() && certainenchants.get())
        .defaultValue(Enchantments.UNBREAKING, Enchantments.MENDING)
        .build()
    );

    private final Setting<Set<RegistryKey<Enchantment>>> maceenchants = sgItems.add(new EnchantmentListSetting.Builder()
        .name("mace-enchants")
        .description("Required enchantments for maces.")
        .visible(() -> enchants.get() && certainenchants.get())
        .defaultValue(Enchantments.UNBREAKING, Enchantments.MENDING)
        .build()
    );

    private final Setting<Set<RegistryKey<Enchantment>>> tridentenchants = sgItems.add(new EnchantmentListSetting.Builder()
        .name("trident-enchants")
        .description("Required enchantments for tridents.")
        .visible(() -> enchants.get() && certainenchants.get())
        .defaultValue(Enchantments.UNBREAKING, Enchantments.MENDING)
        .build()
    );

    private final Setting<Boolean> itemChatFeedback = sgItems.add(new BoolSetting.Builder()
        .name("item-chat-feedback")
        .description("Display info about detected dropped items in chat.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> itemCoordsInChat = sgItems.add(new BoolSetting.Builder()
        .name("item-chat-coords")
        .description("Include coords in dropped item chat message.")
        .visible(itemChatFeedback::get)
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> itemTracers = sgItems.add(new BoolSetting.Builder()
        .name("item-tracers")
        .description("Draw tracers to detected dropped items.")
        .defaultValue(true)
        .build()
    );

    private final Setting<SettingColor> itemColor = sgItems.add(new ColorSetting.Builder()
        .name("item-color")
        .description("Dropped item's bounding box and tracer color.")
        .defaultValue(new SettingColor(255, 25, 255, 255))
        .build()
    );

    public final Setting<Boolean> itemDistanceColors = sgItems.add(new BoolSetting.Builder()
        .name("item-distance-colors")
        .description("Interpolate item color depending on distance.")
        .defaultValue(true)
        .build()
    );

    private final Setting<SettingColor> itemDistantColor = sgItems.add(new ColorSetting.Builder()
        .name("item-distant-color")
        .description("Dropped item's color when you are far away.")
        .defaultValue(new SettingColor(25, 255, 255, 255))
        .visible(itemDistanceColors::get)
        .build()
    );

    public final Setting<Integer> itemDistanceThreshold = sgItems.add(new IntSetting.Builder()
        .name("item-distance-threshold")
        .description("The distance at which item color becomes the distant color.")
        .defaultValue(128)
        .min(1)
        .sliderRange(1, 1024)
        .visible(itemDistanceColors::get)
        .build()
    );

    // ---------------- Mobs tab ----------------
    private final Setting<Boolean> mobEnabled = sgMobs.add(new BoolSetting.Builder()
        .name("enabled")
        .description("Detect mobs likely wearing/holding player gear.")
        .defaultValue(true)
        .build()
    );

    private final Setting<List<Item>> mobItemChecker = sgMobs.add(new ItemListSetting.Builder()
        .name("mob-item-checker")
        .description("Player-like items to check for on mobs.")
        .defaultValue(defaultPlayerItems)
        .visible(mobEnabled::get)
        .build()
    );

    private final Setting<Boolean> mobChatFeedback = sgMobs.add(new BoolSetting.Builder()
        .name("chat-feedback")
        .description("Display info about detected mobs in chat.")
        .defaultValue(true)
        .visible(mobEnabled::get)
        .build()
    );

    private final Setting<Boolean> mobCoordsInChat = sgMobs.add(new BoolSetting.Builder()
        .name("chat-coords")
        .description("Include coords in mob chat message.")
        .visible(() -> mobEnabled.get() && mobChatFeedback.get())
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> mobItemsInChat = sgMobs.add(new BoolSetting.Builder()
        .name("chat-items")
        .description("Include detected items in mob chat message.")
        .visible(() -> mobEnabled.get() && mobChatFeedback.get())
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> mobTracers = sgMobs.add(new BoolSetting.Builder()
        .name("tracers")
        .description("Draw tracers to detected mobs.")
        .defaultValue(true)
        .visible(mobEnabled::get)
        .build()
    );

    private final Setting<SettingColor> mobColor = sgMobs.add(new ColorSetting.Builder()
        .name("color")
        .description("Mob bounding box and tracer color.")
        .defaultValue(new SettingColor(255, 25, 25, 255))
        .visible(mobEnabled::get)
        .build()
    );

    public final Setting<Boolean> mobDistanceColors = sgMobs.add(new BoolSetting.Builder()
        .name("distance-colors")
        .description("Interpolate mob color depending on distance.")
        .defaultValue(true)
        .visible(mobEnabled::get)
        .build()
    );

    private final Setting<SettingColor> mobDistantColor = sgMobs.add(new ColorSetting.Builder()
        .name("distant-color")
        .description("Mob color when you are far away.")
        .defaultValue(new SettingColor(25, 25, 255, 255))
        .visible(() -> mobEnabled.get() && mobDistanceColors.get())
        .build()
    );

    public final Setting<Integer> mobDistanceThreshold = sgMobs.add(new IntSetting.Builder()
        .name("distance-threshold")
        .description("The distance at which mob color becomes the distant color.")
        .defaultValue(128)
        .min(1)
        .sliderRange(1, 1024)
        .visible(() -> mobEnabled.get() && mobDistanceColors.get())
        .build()
    );

    // ---------------- State ----------------
    private final Color itemLineColorC = new Color();
    private final Color itemSideColorC = new Color();
    private final Color itemBaseColorC = new Color();

    private final Color mobLineColorC = new Color();
    private final Color mobSideColorC = new Color();
    private final Color mobBaseColorC = new Color();

    private int itemCount;
    private int mobCount;

    private final Set<Entity> scannedItems = Collections.synchronizedSet(new HashSet<>());
    private final Set<Entity> scannedMobs = Collections.synchronizedSet(new HashSet<>());

    // ---------------- Lifecycle ----------------
    @Override
    public void onActivate() {
        scannedItems.clear();
        scannedMobs.clear();
    }

    @Override
    public void onDeactivate() {
        scannedItems.clear();
        scannedMobs.clear();
    }

    @Override
    public String getInfoString() {
        return Integer.toString(itemCount + mobCount);
    }

    // ---------------- Render ----------------
    @EventHandler
    private void onRender3D(Render3DEvent event) {
        itemCount = 0;
        mobCount = 0;

        if (mc.world == null) return;

        for (Entity entity : mc.world.getEntities()) {
            if (entity instanceof ItemEntity itemEntity) {
                handleItemEntity(event, itemEntity);
            } else if (mobEnabled.get() && entity instanceof LivingEntity livingEntity) {
                handleMobEntity(event, livingEntity);
            }
        }
    }

    private void handleItemEntity(Render3DEvent event, ItemEntity itemEntity) {
        if (shouldSkipDroppedItem(itemEntity)) return;

        if (!scannedItems.contains(itemEntity) && itemChatFeedback.get()) {
            StringBuilder message = new StringBuilder(itemEntity.getStack().getItem().getName().getString()).append(" found");
            if (itemCoordsInChat.get()) {
                message.append(" at ").append(itemEntity.getBlockX()).append(", ").append(itemEntity.getBlockY()).append(", ").append(itemEntity.getBlockZ());
            }
            ChatUtils.sendMsg(Text.of(message.toString()));
        }

        scannedItems.add(itemEntity);

        drawBoundingBox(
            event,
            itemEntity,
            getRenderColor(itemEntity, itemColor, itemDistanceColors, itemDistantColor, itemDistanceThreshold, itemBaseColorC),
            itemSideColorC,
            itemLineColorC
        );

        if (itemTracers.get()) {
            drawTracer(event, itemEntity, itemColor.get(), itemDistanceColors.get(), itemDistantColor.get(), itemDistanceThreshold.get());
        }

        itemCount++;
    }

    private void handleMobEntity(Render3DEvent event, LivingEntity livingEntity) {
        if (shouldSkipMob(livingEntity)) return;

        if (!scannedMobs.contains(livingEntity) && mobChatFeedback.get()) {
            StringBuilder message = new StringBuilder(livingEntity.getType().getName().getString())
                .append(" found (likely wearing player gear)");

            if (mobCoordsInChat.get()) {
                message.append(" at ").append(livingEntity.getBlockX()).append(", ").append(livingEntity.getBlockY()).append(", ").append(livingEntity.getBlockZ());
            }

            if (mobItemsInChat.get()) {
                ArrayList<Item> playerItems = getPlayerItems(livingEntity);
                if (!playerItems.isEmpty()) {
                    message.append(" holding ");
                    for (Item item : playerItems) {
                        String[] parts = item.getTranslationKey().split("\\.");
                        String shortName = parts.length >= 3 ? parts[2] : item.getTranslationKey();
                        message.append(shortName).append(", ");
                    }
                    message.setLength(message.length() - 2);
                }
            }

            ChatUtils.sendMsg(Text.of(message.toString()));
        }

        scannedMobs.add(livingEntity);

        drawBoundingBox(
            event,
            livingEntity,
            getRenderColor(livingEntity, mobColor, mobDistanceColors, mobDistantColor, mobDistanceThreshold, mobBaseColorC),
            mobSideColorC,
            mobLineColorC
        );

        if (mobTracers.get()) {
            drawTracer(event, livingEntity, mobColor.get(), mobDistanceColors.get(), mobDistantColor.get(), mobDistanceThreshold.get());
        }

        mobCount++;
    }

    private void drawBoundingBox(Render3DEvent event, Entity entity, Color renderColor, Color sideColorOut, Color lineColorOut) {
        if (renderColor == null) return;

        lineColorOut.set(renderColor);
        sideColorOut.set(renderColor).a((int) (sideColorOut.a * fillOpacity.get()));

        double x = MathHelper.lerp(event.tickDelta, entity.lastRenderX, entity.getX()) - entity.getX();
        double y = MathHelper.lerp(event.tickDelta, entity.lastRenderY, entity.getY()) - entity.getY();
        double z = MathHelper.lerp(event.tickDelta, entity.lastRenderZ, entity.getZ()) - entity.getZ();

        Box box = entity.getBoundingBox();
        event.renderer.box(
            x + box.minX, y + box.minY, z + box.minZ,
            x + box.maxX, y + box.maxY, z + box.maxZ,
            sideColorOut, lineColorOut, shapeMode.get(), 0
        );
    }

    private void drawTracer(Render3DEvent event, Entity entity, SettingColor near, boolean interpolate, SettingColor far, int maxDist) {
        if (mc.options.hudHidden) return;

        Color c = new Color(near.r, near.g, near.b, near.a);
        if (interpolate) c = interpolateDistanceColor(c, far, entity, maxDist);

        double x = entity.prevX + (entity.getX() - entity.prevX) * event.tickDelta;
        double y = entity.prevY + (entity.getY() - entity.prevY) * event.tickDelta;
        double z = entity.prevZ + (entity.getZ() - entity.prevZ) * event.tickDelta;

        double height = entity.getBoundingBox().maxY - entity.getBoundingBox().minY;
        y += height / 2;

        event.renderer.line(RenderUtils.center.x, RenderUtils.center.y, RenderUtils.center.z, x, y, z, c);
    }

    private Color getRenderColor(
        Entity entity,
        Setting<SettingColor> nearColorSetting,
        Setting<Boolean> interpolateSetting,
        Setting<SettingColor> farColorSetting,
        Setting<Integer> maxDistSetting,
        Color out
    ) {
        double alpha = getFadeAlpha(entity);
        if (alpha == 0) return null;

        SettingColor near = nearColorSetting.get();
        Color c = new Color(near.r, near.g, near.b, near.a);

        if (interpolateSetting.get()) {
            c = interpolateDistanceColor(c, farColorSetting.get(), entity, maxDistSetting.get());
        }

        return out.set(c.r, c.g, c.b, (int) (c.a * alpha));
    }

    private Color interpolateDistanceColor(Color near, SettingColor far, Entity e, int maxDistance) {
        double dist = Math.sqrt(mc.player.squaredDistanceTo(e));
        double percent = MathHelper.clamp(dist / (double) maxDistance, 0, 1);

        int r = (int) (near.r + (far.r - near.r) * percent);
        int g = (int) (near.g + (far.g - near.g) * percent);
        int b = (int) (near.b + (far.b - near.b) * percent);
        int a = near.a;

        return new Color(r, g, b, a);
    }

    private double getFadeAlpha(Entity entity) {
        double distSq = PlayerUtils.squaredDistanceToCamera(
            entity.getX() + entity.getWidth() / 2,
            entity.getY() + entity.getEyeHeight(entity.getPose()),
            entity.getZ() + entity.getWidth() / 2
        );

        double fade = fadeDistance.get();
        double alpha = 1.0;

        if (fade > 0 && distSq <= fade * fade) {
            alpha = Math.sqrt(distSq) / fade;
        }

        if (alpha <= 0.075) alpha = 0;
        return alpha;
    }

    // ---------------- Item logic ----------------
    private boolean shouldSkipDroppedItem(ItemEntity entity) {
        ItemStack stack = entity.getStack();
        boolean skip = false;

        if (enchants.get()) {
            if (!certainenchants.get()
                && (isTool(stack) || isArmor(stack) || stack.isIn(ItemTags.SWORDS)
                    || stack.getItem() instanceof FishingRodItem
                    || stack.getItem() instanceof FlintAndSteelItem
                    || stack.getItem() instanceof MaceItem
                    || stack.getItem() instanceof ShearsItem
                    || stack.getItem() instanceof ShieldItem
                    || stack.getItem() instanceof TridentItem)
                && stack.isEnchantable()
                && stack.getEnchantments().isEmpty()
            ) {
                skip = true;
            } else if (certainenchants.get()) {
                if (isTool(stack)) {
                    skip = !hasAllRequiredEnchants(stack, toolenchants.get());
                } else if (stack.isIn(ItemTags.SWORDS)) {
                    skip = !hasAllRequiredEnchants(stack, swordenchants.get());
                } else if (isArmor(stack)) {
                    skip = !hasAllRequiredEnchants(stack, armorenchants.get());
                } else if (stack.getItem() instanceof MaceItem) {
                    skip = !hasAllRequiredEnchants(stack, maceenchants.get());
                } else if (stack.getItem() instanceof TridentItem) {
                    skip = !hasAllRequiredEnchants(stack, tridentenchants.get());
                }
            }
        }

        if (!itemChecker.get().contains(stack.getItem())) skip = true;
        return skip;
    }

    // ---------------- Mob logic ----------------
    private boolean shouldSkipMob(LivingEntity entity) {
        if (entity.isPlayer()) return true;
        if (entity == mc.getCameraEntity() && mc.options.getPerspective().isFirstPerson()) return true;
        if (!EntityUtils.isInRenderDistance(entity)) return true;

        ArrayList<Item> playerItems = getPlayerItems(entity);
        return playerItems.isEmpty();
    }

    private ArrayList<Item> getPlayerItems(LivingEntity livingEntity) {
        ArrayList<Item> playerItems = new ArrayList<>();

        for (ItemStack stack : getArmorItems(livingEntity)) {
            if (stack == null || stack.isEmpty()) continue;

            boolean skip = false;
            if (enchants.get()) {
                if (!certainenchants.get() && isArmor(stack) && stack.isEnchantable() && stack.getEnchantments().isEmpty()) skip = true;
                else if (certainenchants.get() && isArmor(stack)) skip = !hasAllRequiredEnchants(stack, armorenchants.get());
            }
            if (skip) continue;

            if (mobItemChecker.get().contains(stack.getItem())) playerItems.add(stack.getItem());
        }

        for (ItemStack stack : getHandItems(livingEntity)) {
            if (stack == null || stack.isEmpty()) continue;

            boolean skip = false;
            if (enchants.get()) {
                if (!certainenchants.get()
                    && (isTool(stack) || isArmor(stack) || stack.isIn(ItemTags.SWORDS)
                        || stack.getItem() instanceof FishingRodItem
                        || stack.getItem() instanceof FlintAndSteelItem
                        || stack.getItem() instanceof MaceItem
                        || stack.getItem() instanceof ShearsItem
                        || stack.getItem() instanceof ShieldItem
                        || stack.getItem() instanceof TridentItem)
                    && stack.isEnchantable()
                    && stack.getEnchantments().isEmpty()
                ) {
                    skip = true;
                } else if (certainenchants.get()) {
                    if (isTool(stack)) {
                        skip = !hasAllRequiredEnchants(stack, toolenchants.get());
                    } else if (stack.isIn(ItemTags.SWORDS)) {
                        skip = !hasAllRequiredEnchants(stack, swordenchants.get());
                    } else if (isArmor(stack)) {
                        skip = !hasAllRequiredEnchants(stack, armorenchants.get());
                    } else if (stack.getItem() instanceof MaceItem) {
                        skip = !hasAllRequiredEnchants(stack, maceenchants.get());
                    } else if (stack.getItem() instanceof TridentItem) {
                        skip = !hasAllRequiredEnchants(stack, tridentenchants.get());
                    }
                }
            }
            if (skip) continue;

            if (mobItemChecker.get().contains(stack.getItem())) playerItems.add(stack.getItem());
        }

        return playerItems;
    }

    private static ArrayList<ItemStack> getArmorItems(LivingEntity livingEntity) {
        ArrayList<ItemStack> armorItems = new ArrayList<>();
        armorItems.add(livingEntity.getEquippedStack(EquipmentSlot.HEAD));
        armorItems.add(livingEntity.getEquippedStack(EquipmentSlot.CHEST));
        armorItems.add(livingEntity.getEquippedStack(EquipmentSlot.LEGS));
        armorItems.add(livingEntity.getEquippedStack(EquipmentSlot.FEET));
        return armorItems;
    }

    private static ArrayList<ItemStack> getHandItems(LivingEntity livingEntity) {
        ArrayList<ItemStack> handItems = new ArrayList<>();
        handItems.add(livingEntity.getEquippedStack(EquipmentSlot.MAINHAND));
        handItems.add(livingEntity.getEquippedStack(EquipmentSlot.OFFHAND));
        return handItems;
    }

    public static boolean isTool(ItemStack itemStack) {
        return itemStack.isIn(ItemTags.AXES)
            || itemStack.isIn(ItemTags.HOES)
            || itemStack.isIn(ItemTags.PICKAXES)
            || itemStack.isIn(ItemTags.SHOVELS)
            || itemStack.getItem() instanceof ShearsItem
            || itemStack.getItem() instanceof FlintAndSteelItem;
    }

    public static boolean isArmor(ItemStack itemStack) {
        return itemStack.isIn(ItemTags.HEAD_ARMOR)
            || itemStack.isIn(ItemTags.CHEST_ARMOR)
            || itemStack.isIn(ItemTags.LEG_ARMOR)
            || itemStack.isIn(ItemTags.FOOT_ARMOR);
    }

    private boolean hasAllRequiredEnchants(ItemStack stack, Set<RegistryKey<Enchantment>> required) {
        Set<RegistryKey<Enchantment>> present = new HashSet<>();
        stack.getEnchantments().getEnchantments().forEach(e -> present.add(e.getKey().get()));
        for (RegistryKey<Enchantment> k : required) if (!present.contains(k)) return false;
        return true;
    }

    // ---------------- Cleanup ----------------
    @EventHandler
    private void onPreTick(TickEvent.Pre event) {
        if (mc.world == null) return;

        Set<Entity> current = new HashSet<>();
        mc.world.getEntities().forEach(current::add);

        scannedItems.removeIf(e -> !current.contains(e));
        scannedMobs.removeIf(e -> !current.contains(e));
    }
}
