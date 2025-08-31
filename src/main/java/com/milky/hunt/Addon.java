package com.milky.hunt;

import com.milky.hunt.modules.*;
import com.milky.hunt.hud.*;
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
    public static final Category CATEGORY = new Category("Milky Mod");
    public static final HudGroup HUD_GROUP = new HudGroup("Milky Mod");

    public final Settings settings = new Settings();

    @Override
    public void onInitialize() {
        LOG.info("Initializing Milky Mod");

        Modules.get().add(new AirLanding());
        Modules.get().add(new AutoGolem());
        Modules.get().add(new AutoInvertedY());
        Modules.get().add(new ChestDeposit());
        Modules.get().add(new ChestRestock());
        Modules.get().add(new GotoMultiPoints());
        Modules.get().add(new InHand());
        Modules.get().add(new QuickCommand());
        Modules.get().add(new RightClickEntity());
        Modules.get().add(new Timeline());
        Modules.get().add(new Cruise());
        Modules.get().add(new BoostedBounce());
        Modules.get().add(new PullUp());
        Modules.get().add(new PitStop());
        Modules.get().add(new SpiralFlight());
        Modules.get().add(new WaypointQueue());
        Modules.get().add(new RightClickBlock());
        Modules.get().add(new LeftClickBlock());
        Modules.get().add(new Magazine());

        Hud.get().register(YVelocity.INFO);

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
        return "com.milky.hunt";
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
