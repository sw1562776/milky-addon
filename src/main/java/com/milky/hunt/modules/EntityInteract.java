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
        .defaultValue(EntityType.SHEEP)
        .onlyAttackable()
        .build()
    );

    private final Setting<Double> range = sgGeneral.add(new DoubleSetting.Builder()
        .name("range")
        .description("Interact range.")
        .min(0)
        .defaultValue(2)
        .build()
    );

    private final Setting<Hand> hand = sgGeneral.add(new EnumSetting.Builder<Hand>()
        .name("hand")
        .description("The hand to use when interacting.")
        .defaultValue(Hand.MAIN_HAND)
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

    private final List<Entity> used = new ArrayList<>();

    public EntityInteract() {
        super(Addon.CATEGORY, "entity-interact", "Automatically interacts with entities in range (2b2t-friendly).");
    }

    @Override
    public void onActivate() {
        used.clear();
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (mc.world == null || mc.player == null) return;

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
            hand.get()
        );
        mc.getNetworkHandler().sendPacket(interactPacket);

        Vec3d hitPos = entity.getPos().add(0, entity.getStandingEyeHeight() / 2.0, 0);
        Packet<?> interactAtPacket = PlayerInteractEntityC2SPacket.interactAt(
            entity,
            false,
            hand.get(),
            hitPos
        );
        mc.getNetworkHandler().sendPacket(interactAtPacket);

        mc.getNetworkHandler().sendPacket(new HandSwingC2SPacket(hand.get()));
    }
}
