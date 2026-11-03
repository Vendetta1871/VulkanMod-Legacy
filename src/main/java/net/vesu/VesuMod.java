package net.vesu;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.event.FMLPreInitializationEvent;
import net.vesu.debug.AutoTest;

/**
 * Vesu for Minecraft 1.12.2 (Cleanroom Loader).
 *
 * <p>A port of <a href="https://github.com/xCollateral/VulkanMod">VulkanMod</a> by xCollateral,
 * which replaces Minecraft's OpenGL renderer with a Vulkan-based voxel rendering engine.
 */
@Mod(modid = VesuMod.MODID, name = VesuMod.NAME, version = VesuMod.VERSION, clientSideOnly = true, acceptedMinecraftVersions = "[1.12.2]")
public class VesuMod {

    public static final String MODID = "vesu";
    public static final String NAME = "Vesu";
    // Generated from gradle.properties at build time (L15); still a compile-time constant,
    // so the @Mod annotation above keeps working.
    public static final String VERSION = BuildConfig.VERSION;

    public static final Logger LOGGER = LogManager.getLogger("Vesu");

    @Mod.EventHandler
    public void preInit(FMLPreInitializationEvent event) {
        Initializer.init();
        LOGGER.info("== Vesu {} on Cleanroom ==", VERSION);
        // RT material atlas follows the block atlas across resource reloads.
        MinecraftForge.EVENT_BUS.register(net.vesu.vulkan.rt.RtMaterials.class);
        net.vesu.lod.mc.LodSystem.init();

        if (AutoTest.ENABLED) {
            MinecraftForge.EVENT_BUS.register(AutoTest.class);
            LOGGER.info("== Vesu autotest enabled ==");
        }
    }
}
