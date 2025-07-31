package com.stash.hunt;

import com.stash.hunt.hud.Weather;
import com.stash.hunt.modules.*;
import com.mojang.logging.LogUtils;
import meteordevelopment.meteorclient.addons.MeteorAddon;
import meteordevelopment.meteorclient.settings.Settings;
import meteordevelopment.meteorclient.systems.hud.Hud;
import meteordevelopment.meteorclient.systems.hud.HudGroup;
import meteordevelopment.meteorclient.systems.modules.Category;
import meteordevelopment.meteorclient.systems.modules.Modules;
import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;

public class Addon extends MeteorAddon {
    public static final Logger LOG = LogUtils.getLogger();
    public static final Category CATEGORY = new Category("Jefff Mod");
    public static final HudGroup HUD_GROUP = new HudGroup("Jefff Mod");

    public final Settings settings = new Settings();

    @Override
    public void onInitialize() {
        LOG.info("Initializing Jefff Mod");

        Modules.get().add(new AutoSnowman());
        
        boolean baritoneLoaded = checkModLoaded("baritone", "baritone-meteor");
        boolean xaeroWorldMapLoaded = checkModLoaded("xaeroworldmap");
        boolean xaeroMinimapLoaded = checkModLoaded("xaerominimap");
        boolean xaeroPlusLoaded = checkModLoaded("xaeroplus");
        
    }

    @Override
    public void onRegisterCategories() {
        Modules.registerCategory(CATEGORY);
    }

    @Override
    public String getPackage() {
        return "com.stash.hunt";
    }

    private boolean checkModLoaded(String... modIds)
    {
        boolean loaded = false;
        for (String id : modIds)
        {
            if (FabricLoader.getInstance().isModLoaded(id))
            {
                loaded = true;
                break;
            }
        }
        if (!loaded)
        {
            LOG.error("{} not found, disabling modules that require it.", modIds[0]);
        }
        return loaded;
    }
}
