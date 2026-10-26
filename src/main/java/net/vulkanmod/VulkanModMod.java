package net.vulkanmod;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.event.FMLPreInitializationEvent;
import net.vulkanmod.debug.AutoTest;

/**
 * VulkanMod for Minecraft 1.12.2 (Cleanroom Loader).
 *
 * <p>A port of <a href="https://github.com/xCollateral/VulkanMod">VulkanMod</a> by xCollateral,
 * which replaces Minecraft's OpenGL renderer with a Vulkan-based voxel rendering engine.
 */
@Mod(modid = VulkanModMod.MODID, name = VulkanModMod.NAME, version = VulkanModMod.VERSION, clientSideOnly = true, acceptedMinecraftVersions = "[1.12.2]")
public class VulkanModMod {

    public static final String MODID = "vulkanmod";
    public static final String NAME = "VulkanMod";
    // Generated from gradle.properties at build time (L15); still a compile-time constant,
    // so the @Mod annotation above keeps working.
    public static final String VERSION = BuildConfig.VERSION;

    public static final Logger LOGGER = LogManager.getLogger("VulkanMod");

    @Mod.EventHandler
    public void preInit(FMLPreInitializationEvent event) {
        Initializer.init();
        LOGGER.info("== VulkanMod {} on Cleanroom ==", VERSION);

        if (AutoTest.ENABLED) {
            MinecraftForge.EVENT_BUS.register(AutoTest.class);
            LOGGER.info("== VulkanMod autotest enabled ==");
        }
    }
}
