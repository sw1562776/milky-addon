package com.milky.hunt.modules;

import com.milky.hunt.Addon;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.DoubleSetting;
import meteordevelopment.meteorclient.settings.EnumSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.orbit.EventHandler;
import meteordevelopment.orbit.EventPriority;

public class RotationLock extends Module {
    private final SettingGroup sgYaw = settings.createGroup("Yaw");
    private final SettingGroup sgPitch = settings.createGroup("Pitch");

    // Yaw

    private final Setting<LockMode> yawLockMode = sgYaw.add(new EnumSetting.Builder<LockMode>()
        .name("yaw-lock-mode")
        .description("The way in which your yaw is locked.")
        .defaultValue(LockMode.Simple)
        .build()
    );

    private final Setting<Double> yawAngle = sgYaw.add(new DoubleSetting.Builder()
        .name("yaw-angle")
        .description("Yaw angle in degrees.")
        .defaultValue(0)
        .sliderMax(360)
        .max(360)
        .visible(() -> yawLockMode.get() == LockMode.Simple)
        .build()
    );

    // Pitch

    private final Setting<LockMode> pitchLockMode = sgPitch.add(new EnumSetting.Builder<LockMode>()
        .name("pitch-lock-mode")
        .description("The way in which your pitch is locked.")
        .defaultValue(LockMode.Simple)
        .build()
    );

    private final Setting<Double> pitchAngle = sgPitch.add(new DoubleSetting.Builder()
        .name("pitch-angle")
        .description("Pitch angle in degrees.")
        .defaultValue(0)
        .range(-90, 90)
        .sliderRange(-90, 90)
        .visible(() -> pitchLockMode.get() == LockMode.Simple)
        .build()
    );

    public RotationLock() {
        super(Addon.MilkyModCategory, "RotationLock", "Changes/locks your yaw and pitch (corrected in both Pre and Post tick).");
    }

    @Override
    public void onActivate() {
        applyRotation();
    }

    // Apply as early as possible (so movement uses the corrected rotation).
    @EventHandler(priority = EventPriority.HIGHEST)
    private void onTickPre(TickEvent.Pre event) {
        applyRotation();
    }

    // Apply again at the end (so rendering / other modules don't leave it slightly off).
    @EventHandler(priority = EventPriority.LOWEST)
    private void onTickPost(TickEvent.Post event) {
        applyRotation();
    }

    private void applyRotation() {
        if (mc.player == null) return;

        switch (yawLockMode.get()) {
            case Simple -> setYawAngle(yawAngle.get().floatValue());
            case Smart  -> setYawAngle(getSmartYawDirection());
            case None   -> { /* no-op */ }
        }

        switch (pitchLockMode.get()) {
            case Simple -> setPitchAngle(pitchAngle.get().floatValue());
            case Smart  -> setPitchAngle(getSmartPitchDirection());
            case None   -> { /* no-op */ }
        }
    }

    private float getSmartYawDirection() {
        return Math.round((mc.player.getYaw() + 1f) / 45f) * 45f;
    }

    private float getSmartPitchDirection() {
        return Math.round((mc.player.getPitch() + 1f) / 30f) * 30f;
    }

    private void setYawAngle(float yawAngle) {
        mc.player.setYaw(yawAngle);
        mc.player.headYaw = yawAngle;
        mc.player.bodyYaw = yawAngle;
    }

    private void setPitchAngle(float pitchAngle) {
        mc.player.setPitch(pitchAngle);
    }

    public enum LockMode {
        Smart,
        Simple,
        None
    }
}