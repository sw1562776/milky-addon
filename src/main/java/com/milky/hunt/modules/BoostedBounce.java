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

import com.milky.hunt.Addon;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.util.math.Vec3d;

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

    // NEW: only clear Y momentum when actually colliding with a wall
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
        .description("Enable this if you want to use a yaw that isn't a factor of 45.")
        .defaultValue(false)
        .visible(bounce::get)
        .build()
    );

    private final Setting<Double> yaw = sgGeneral.add(new DoubleSetting.Builder()
        .name("Yaw")
        .description("The yaw to set when bounce is enabled. This is auto set to the closest 45 deg angle to you unless Use Custom Yaw is enabled.")
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
        .description("Enable and set this ONLY if you are on a ringroad or don't want to be locked to a highway. Otherwise (0, 0) is the start position and will be automatically used.")
        .defaultValue(false)
        .visible(() -> bounce.get() && highwayObstaclePasser.get())
        .build()
    );

    private final Setting<BlockPos> startPos = sgObstaclePasser.add(new BlockPosSetting.Builder()
        .name("Start Position")
        .description("The start position to use when using a custom start position.")
        .defaultValue(new BlockPos(0,0,0))
        .visible(() -> bounce.get() && highwayObstaclePasser.get() && useCustomStartPos.get())
        .build()
    );

    private final Setting<Boolean> awayFromStartPos = sgObstaclePasser.add(new BoolSetting.Builder()
        .name("Away From Start Position")
        .description("If true, will go away from the start position instead of towards it. The start pos is (0,0) if it is not set to a custom start pos.")
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
        .visible(() -> bounce.get() && highwayObsta
