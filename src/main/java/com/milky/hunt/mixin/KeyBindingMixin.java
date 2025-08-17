package com.milky.hunt.mixin;

import com.milky.hunt.modules.BoostedBounce;
import meteordevelopment.meteorclient.systems.modules.Modules;
import net.minecraft.client.option.KeyBinding;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import static meteordevelopment.meteorclient.MeteorClient.mc;

@Mixin(KeyBinding.class)
public abstract class KeyBindingMixin {

    @Final
    @Shadow
    private String translationKey;

    @Unique
    BoostedBounce efly = null;

    @Inject(at = @At("RETURN"), method = "isPressed", cancellable = true)
    public void isPressed(CallbackInfoReturnable<Boolean> cir)
    {
        // setting it beforehand caused a crash because meteor wasnt loaded yet
        efly = efly == null ? Modules.get().get(BoostedBounce.class) : efly;
        if (efly != null && efly.isActive() && efly.enabled() && translationKey.equals("key.forward"))
        {
            cir.setReturnValue(true);
        }
    }
}
