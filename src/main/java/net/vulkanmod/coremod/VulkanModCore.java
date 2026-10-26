package net.vulkanmod.coremod;

import java.util.Collections;
import java.util.List;
import java.util.Map;

import net.minecraftforge.fml.relauncher.IFMLLoadingPlugin;
import zone.rong.mixinbooter.IEarlyMixinLoader;

/**
 * Coremod entry for VulkanMod. Registered through the {@code FMLCorePlugin} jar manifest.
 *
 * <p>Loads the early Mixin configs that replace Minecraft's OpenGL rendering pipeline
 * with the Vulkan renderer. Must apply to vanilla classes, hence the early loader.
 */
@IFMLLoadingPlugin.Name("VulkanMod Core")
@IFMLLoadingPlugin.MCVersion("1.12.2")
@IFMLLoadingPlugin.SortingIndex(1001)
public class VulkanModCore implements IFMLLoadingPlugin, IEarlyMixinLoader {

    @Override
    public String[] getASMTransformerClass() {
        return new String[0];
    }

    @Override
    public String getModContainerClass() {
        return null;
    }

    @Override
    public String getSetupClass() {
        return null;
    }

    @Override
    public void injectData(Map<String, Object> data) {
    }

    @Override
    public String getAccessTransformerClass() {
        return null;
    }

    @Override
    public List<String> getMixinConfigs() {
        return Collections.singletonList("mixins.vulkanmod.json");
    }

    @Override
    public boolean shouldMixinConfigQueue(String mixinConfig) {
        // Client-only renderer; never queue on a dedicated server.
        return !isDedicatedServer();
    }

    private static boolean isDedicatedServer() {
        // MUST NOT touch any Minecraft class here: loading a class that a queued
        // mixin targets makes Mixin prepare reject the whole config ("loaded too
        // early"). FMLLaunchHandler is FML's own relauncher, not a Minecraft class,
        // and it is already initialised when this runs: both tweakers call
        // configureForClientLaunch/configureForServerLaunch (which sets the side)
        // before appendCoreMods() reaches this coremod (L14 - the previous
        // Launch.blackboard "TweakClasses" string sniff depended on the launcher
        // populating that key before coremod discovery, which is not guaranteed).
        return net.minecraftforge.fml.relauncher.FMLLaunchHandler.side()
                == net.minecraftforge.fml.relauncher.Side.SERVER;
    }
}
