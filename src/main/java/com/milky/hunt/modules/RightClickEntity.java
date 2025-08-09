package com.milky.hunt.modules;

import com.milky.hunt.Addon;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.player.Rotations;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.LivingEntity;
import net.minecraft.network.packet.Packet;
import net.minecraft.network.packet.c2s.play.HandSwingC2SPacket;
import net.minecraft.network.packet.c2s.play.PlayerInteractEntityC2SPacket;
import net.minecraft.util.Hand;
import net.minecraft.util.math.Vec3d;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

public class EntityInteract extends Module {
    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    private final Setting<Boolean> oneInteractionPerTick = sgGeneral.add(new BoolSetting.Builder()
        .name("one-interaction-per-tick")
        .description("One interaction per tick.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Set<EntityType<?>>> entities = sgGeneral.add(new EntityTypeListSetting.Builder()
        .name("entities")
        .description("Entities to interact with.")
        .defaultValue(EntityType.TURTLE)
        .onlyAttackable()
        .build()
    );

    private final Setting<Double> range = sgGeneral.add(new DoubleSetting.Builder()
        .name("range")
        .description("Interact range.")
        .min(0)
        .defaultValue(3)
        .build()
    );

    private final Setting<Boolean> ignoreBabies = sgGeneral.add(new BoolSetting.Builder()
        .name("ignore-babies")
        .description("Ignore baby entities.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> oneTime = sgGeneral.add(new BoolSetting.Builder()
        .name("one-time")
        .description("Interact with each entity only one time.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Double> clearInterval = sgGeneral.add(new DoubleSetting.Builder()
        .name("clear-interval")
        .description("How often to clear the list of interacted entities (seconds). Set to 0 to never clear.")
        .defaultValue(10.0)
        .min(0)
        .sliderMax(60)
        .visible(oneTime::get)
        .build()
    );

    private final List<Entity> used = new ArrayList<>();
    private long lastClearTime;

    public EntityInteract() {
        super(Addon.CATEGORY, "RightClickEntity", "Automatically interacts with entities in range.");
    }

    @Override
    public void onActivate() {
        used.clear();
        lastClearTime = System.currentTimeMillis();
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (mc.world == null || mc.player == null) return;

        if (oneTime.get()) {
            double intervalMs = clearInterval.get() * 1000;
            if (intervalMs > 0 && System.currentTimeMillis() - lastClearTime >= intervalMs) {
                used.clear();
                lastClearTime = System.currentTimeMillis();
            }
        }

        for (Entity entity : mc.world.getEntities()) {
            if (!(entity instanceof LivingEntity)
                || !(entities.get().contains(entity.getType()))
                || mc.player.getMainHandStack().isEmpty()
                || (oneTime.get() && used.contains(entity))
                || mc.player.distanceTo(entity) > range.get()
                || (ignoreBabies.get() && ((LivingEntity) entity).isBaby())) continue;

            if (oneTime.get()) used.add(entity);

            Rotations.rotate(Rotations.getYaw(entity), Rotations.getPitch(entity), () -> {
                sendInteractPackets(entity);
            });

            if (oneInteractionPerTick.get()) break;
        }
    }

    private void sendInteractPackets(Entity entity) {
        Packet<?> interactPacket = PlayerInteractEntityC2SPacket.interact(
            entity,
            false,
            Hand.MAIN_HAND
        );
        mc.getNetworkHandler().sendPacket(interactPacket);

        Vec3d hitPos = entity.getPos().add(0, entity.getStandingEyeHeight() / 2.0, 0);
        Packet<?> interactAtPacket = PlayerInteractEntityC2SPacket.interactAt(
            entity,
            false,
            Hand.MAIN_HAND,
            hitPos
        );
        mc.getNetworkHandler().sendPacket(interactAtPacket);

        mc.getNetworkHandler().sendPacket(new HandSwingC2SPacket(Hand.MAIN_HAND));
    }
}
