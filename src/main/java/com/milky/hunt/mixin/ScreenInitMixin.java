package com.milky.hunt.mixin;

import com.milky.hunt.Addon;
import com.milky.hunt.modules.EventLog;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.Drawable;
import net.minecraft.client.gui.Element;
import net.minecraft.client.gui.Selectable;
import net.minecraft.client.gui.screen.DisconnectedScreen;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.text.Text;
import net.minecraft.util.Util;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.io.File;

@Mixin(Screen.class)
public abstract class ScreenInitMixin {
    @Shadow public int width;
    @Shadow public int height;

    @Shadow protected abstract <T extends Element & Drawable & Selectable> T addDrawableChild(T drawable);

    @Inject(method = "init(Lnet/minecraft/client/MinecraftClient;II)V", at = @At("TAIL"))
    private void eventlog$addOpenFolderButton(MinecraftClient client, int w, int h, CallbackInfo ci) {
        if (!(((Object) this) instanceof DisconnectedScreen)) return;

        int bw = 200;
        int bh = 20;
        int x = (this.width - bw) / 2;
        int y = this.height / 2 + 92;

        this.addDrawableChild(
            ButtonWidget.builder(Text.literal("Open EventLog Folder"), btn -> {
                try {
                    File dir = EventLog.EventLogShots.getEventLogDir();
                    Util.getOperatingSystem().open(dir);
                } catch (Throwable t) {
                    Addon.LOG.error("Failed to open EventLog folder.", t);
                }
            }).dimensions(x, y, bw, bh).build()
        );
    }
}
