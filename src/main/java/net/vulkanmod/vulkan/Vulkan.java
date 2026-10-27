package net.vulkanmod.vulkan;

import net.minecraft.client.Minecraft;
import net.vulkanmod.Initializer;
import net.vulkanmod.vulkan.memory.MemoryManager;
import net.vulkanmod.vulkan.memory.MemoryTypes;
import net.vulkanmod.vulkan.memory.StagingBuffer;
import net.vulkanmod.vulkan.util.VUtil;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.util.vma.*;
import org.lwjgl.vulkan.*;

import java.nio.IntBuffer;
import java.nio.LongBuffer;
import java.util.*;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import static java.util.stream.Collectors.toSet;
import static net.vulkanmod.vulkan.texture.VulkanImage.transitionImageLayout;
import static org.lwjgl.sdl.SDLVideo.SDL_GetWindowSizeInPixels;
import static org.lwjgl.sdl.SDLVulkan.SDL_Vulkan_CreateSurface;
import static org.lwjgl.sdl.SDLVulkan.SDL_Vulkan_GetInstanceExtensions;
import static org.lwjgl.sdl.SDLVulkan.SDL_Vulkan_LoadLibrary;
import static org.lwjgl.system.MemoryStack.stackGet;
import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.system.MemoryUtil.NULL;
import static org.lwjgl.util.vma.Vma.vmaCreateAllocator;
import static org.lwjgl.util.vma.Vma.vmaDestroyAllocator;
import static org.lwjgl.vulkan.EXTDebugUtils.VK_DEBUG_UTILS_MESSAGE_SEVERITY_ERROR_BIT_EXT;
import static org.lwjgl.vulkan.EXTDebugUtils.VK_DEBUG_UTILS_MESSAGE_SEVERITY_WARNING_BIT_EXT;
import static org.lwjgl.vulkan.EXTDebugUtils.VK_DEBUG_UTILS_MESSAGE_TYPE_GENERAL_BIT_EXT;
import static org.lwjgl.vulkan.EXTDebugUtils.VK_DEBUG_UTILS_MESSAGE_TYPE_PERFORMANCE_BIT_EXT;
import static org.lwjgl.vulkan.EXTDebugUtils.VK_DEBUG_UTILS_MESSAGE_TYPE_VALIDATION_BIT_EXT;
import static org.lwjgl.vulkan.EXTDebugUtils.VK_EXT_DEBUG_UTILS_EXTENSION_NAME;
import static org.lwjgl.vulkan.EXTDebugUtils.VK_STRUCTURE_TYPE_DEBUG_UTILS_MESSENGER_CREATE_INFO_EXT;
import static org.lwjgl.vulkan.EXTDebugUtils.vkCreateDebugUtilsMessengerEXT;
import static org.lwjgl.vulkan.EXTDebugUtils.vkDestroyDebugUtilsMessengerEXT;
import static org.lwjgl.vulkan.KHRSurface.*;
import static org.lwjgl.vulkan.KHRSwapchain.VK_IMAGE_LAYOUT_PRESENT_SRC_KHR;
import static org.lwjgl.vulkan.KHRSwapchain.VK_KHR_SWAPCHAIN_EXTENSION_NAME;
import static org.lwjgl.vulkan.KHRSwapchain.VK_STRUCTURE_TYPE_SWAPCHAIN_CREATE_INFO_KHR;
import static org.lwjgl.vulkan.KHRSwapchain.vkCreateSwapchainKHR;
import static org.lwjgl.vulkan.KHRSwapchain.vkDestroySwapchainKHR;
import static org.lwjgl.vulkan.KHRSwapchain.vkGetSwapchainImagesKHR;
import static org.lwjgl.vulkan.VK10.*;
import static org.lwjgl.vulkan.VK11.VK_API_VERSION_1_1;
import static org.lwjgl.vulkan.VK13.VK_API_VERSION_1_3;
import static org.lwjgl.util.vma.Vma.VMA_ALLOCATOR_CREATE_BUFFER_DEVICE_ADDRESS_BIT;

public class Vulkan {

    private static final int UINT32_MAX = 0xFFFFFFFF;

    public static final int INDEX_SIZE = Short.BYTES;

    // VULKANMOD_VALIDATION=1 runs the client with the Khronos validation layer (the
    // audit's recommended check after renderer changes); off by default.
    private static final boolean ENABLE_VALIDATION_LAYERS = System.getenv("VULKANMOD_VALIDATION") != null;

    // Set at device creation from the physical device's feature bits (see M3).
    public static boolean multiDrawIndirectSupported;
    public static boolean logicOpSupported;

    public static final Set<String> VALIDATION_LAYERS;
    static {
        if(ENABLE_VALIDATION_LAYERS) {
            VALIDATION_LAYERS = new HashSet<>();
            VALIDATION_LAYERS.add("VK_LAYER_KHRONOS_validation");

        } else {
            // We are not going to use it, so we don't create it
            VALIDATION_LAYERS = null;
        }
    }

    private static final Set<String> DEVICE_EXTENSIONS = Stream.of(VK_KHR_SWAPCHAIN_EXTENSION_NAME)
            .collect(toSet());



    private static int debugCallback(int messageSeverity, int messageType, long pCallbackData, long pUserData) {

        VkDebugUtilsMessengerCallbackDataEXT callbackData = VkDebugUtilsMessengerCallbackDataEXT.create(pCallbackData);

//        System.err.println("Stack dump:");
//        Thread.dumpStack();
        System.err.println("Validation layer: " + callbackData.pMessageString());

        return VK_FALSE;
    }

    private static int createDebugUtilsMessengerEXT(VkInstance instance, VkDebugUtilsMessengerCreateInfoEXT createInfo,
                                                    VkAllocationCallbacks allocationCallbacks, LongBuffer pDebugMessenger) {

        if(vkGetInstanceProcAddr(instance, "vkCreateDebugUtilsMessengerEXT") != NULL) {
            return vkCreateDebugUtilsMessengerEXT(instance, createInfo, allocationCallbacks, pDebugMessenger);
        }

        return VK_ERROR_EXTENSION_NOT_PRESENT;
    }

    private static void destroyDebugUtilsMessengerEXT(VkInstance instance, long debugMessenger, VkAllocationCallbacks allocationCallbacks) {

        if(vkGetInstanceProcAddr(instance, "vkDestroyDebugUtilsMessengerEXT") != NULL) {
            vkDestroyDebugUtilsMessengerEXT(instance, debugMessenger, allocationCallbacks);
        }

    }

    public static VkDevice getDevice() {
        return device;
    }

    public static long getAllocator() {
        return allocator;
    }

    static class QueueFamilyIndices {

        // We use Integer to use null as the empty value
        Integer graphicsFamily;
        Integer presentFamily;

        public boolean isComplete() {
            return graphicsFamily != null && presentFamily != null;
        }

        public int[] unique() {
            return IntStream.of(graphicsFamily, presentFamily).distinct().toArray();
        }

        public int[] array() {
            return new int[] {graphicsFamily, presentFamily};
        }
    }

    public static class SwapChainSupportDetails {

        VkSurfaceCapabilitiesKHR capabilities;
        VkSurfaceFormatKHR.Buffer formats;
        IntBuffer presentModes;

        public VkSurfaceCapabilitiesKHR getCapabilities() {
            return capabilities;
        }

    }

    public static long window;

    private static VkInstance instance;
    private static long debugMessenger;
    private static long surface;

    private static VkPhysicalDevice physicalDevice;
    private static VkDevice device;

    private static DeviceInfo deviceInfo;

    public static VkPhysicalDeviceProperties deviceProperties;
    public static VkPhysicalDeviceMemoryProperties memoryProperties;

    private static VkQueue graphicsQueue;
    private static VkQueue presentQueue;
    private static QueueSetup queueSetup;
    private static int instanceApiVersion;

    // Config value validated: at least double buffering, sane upper bound.
    private static final int frameQueueSize = Math.min(Math.max(2, Initializer.CONFIG.frameQueueSize), 8);

    private static long swapChain;
    // R10: set when a failed vkCreateSwapchainKHR retired swapChain (the spec retires
    // oldSwapchain even on failure). It must neither be acquired from nor passed again.
    private static boolean swapchainRetired;
    private static List<Long> swapChainImages;
    private static int swapChainImageFormat;
    private static VkExtent2D swapChainExtent;
    private static List<Long> swapChainImageViews;

    private static long commandPool;
    private static VkCommandBuffer immediateCmdBuffer;
    private static long immediateFence;

    private static long allocator;

    // M8: vanilla's enableVsync (options.txt) is the single source of truth;
    // initVulkan applies it before the swapchain picks its present mode.
    private static boolean vsync;

    private static StagingBuffer[] stagingBuffers;

    public static void initVulkan(long window) {
        vsync = Minecraft.getMinecraft().gameSettings.enableVsync;
        createInstance();
        setupDebugMessenger();
        createSurface(window);
        pickPhysicalDevice();
        createLogicalDevice();
        createVma();
        MemoryTypes.createMemoryTypes();
        createCommandPool();
        allocateImmediateCmdBuffer();

        createSwapChain();
        createImageViews();
        // B2: the frame renders into offscreen targets and is copied to the swapchain.
        SceneTargets.init(findDepthFormat());
        SceneTargets.create(swapChainExtent.width(), swapChainExtent.height());

        createStagingBuffers();
    }

    private static void createStagingBuffers() {
        stagingBuffers = new StagingBuffer[getSwapChainImages().size()];

        for(int i = 0; i < stagingBuffers.length; ++i) {
            stagingBuffers[i] = new StagingBuffer(30 * 1024 * 1024);
        }
    }

    /**
     * Compares the window's current pixel size against the swapchain extent and flags a
     * recreation when they diverge. Minecraft's framebuffer-size event is unreliable for a
     * Wayland maximize (no VK_ERROR_OUT_OF_DATE_KHR reaches the presenter), so the swapchain
     * would otherwise keep the old extent and the compositor stretches the stale image.
     * Edge-triggered on the window size so a window clamped by the surface's min/max image
     * extent (extent != size) cannot spin the recreation every frame.
     */
    private static int lastPolledW = -1, lastPolledH = -1;

    public static void pollWindowResize() {
        if (swapChainExtent == null) {
            return;
        }
        try(MemoryStack stack = stackPush()) {
            IntBuffer width = stack.ints(0);
            IntBuffer height = stack.ints(0);
            SDL_GetWindowSizeInPixels(window, width, height);
            int w = width.get(0), h = height.get(0);
            if (w <= 0 || h <= 0) {
                return;
            }
            if (w != lastPolledW || h != lastPolledH) {
                lastPolledW = w;
                lastPolledH = h;
                if (w != swapChainExtent.width() || h != swapChainExtent.height()) {
                    Drawer.shouldRecreate = true;
                }
            }
        }
    }

    /** True when the optional R8G8B8_SNORM can be sampled as a vertex attribute (L1). */
    public static boolean vertexBufferFormatSupported(int format) {
        try(MemoryStack stack = stackPush()) {
            VkFormatProperties formatProperties = VkFormatProperties.create();
            vkGetPhysicalDeviceFormatProperties(physicalDevice, format, formatProperties);
            return (formatProperties.bufferFeatures() & VK_FORMAT_FEATURE_VERTEX_BUFFER_BIT) != 0;
        }
    }

    /**
     * Recreates the swapchain and its attachments. Returns false (nothing recreated)
     * when the surface currently has no extent - a minimized window would make
     * vkCreateSwapchainKHR fail on every retry.
     */
    public static boolean recreateSwapChain() {

        TransferQueue.waitPendingFences();

        try(MemoryStack stack = stackPush()) {
            VkSurfaceCapabilitiesKHR capabilities = querySwapChainSupport(physicalDevice, stack).capabilities;
            if(capabilities.currentExtent().width() == 0 || capabilities.currentExtent().height() == 0) {
                return false;
            }
        }

        int oldW = swapChainExtent.width(), oldH = swapChainExtent.height();

        try {
            createSwapChain();
        } catch (RuntimeException e) {
            // A failed vkCreateSwapchainKHR leaves the old swapchain, its views and the
            // scene targets alive; swapChainExtent/format still describe them, because a
            // failed create publishes nothing (R10). Schedule a retry for the next frame.
            Drawer.shouldRecreate = true;
            throw e;
        }

        createImageViews();
        // The caller (Drawer.recreateSwapChain) waited for the device to idle.
        SceneTargets.create(swapChainExtent.width(), swapChainExtent.height());

        Initializer.LOGGER.info("[swapchain] recreated {}x{} -> {}x{}", oldW, oldH,
                swapChainExtent.width(), swapChainExtent.height());
        return true;
    }

    private static boolean cleanedUp;

    /**
     * Destroys every Vulkan object owned by the renderer. Must run while the window
     * (and therefore the surface) still exists - see WindowCloseM. Safe to call twice
     * or when initialization never reached device creation.
     */
    public static void cleanUp() {
        if(cleanedUp) {
            return;
        }
        cleanedUp = true;

        if(device == null) {
            // M20: initialization can fail before the device exists (instance or
            // surface creation, vkCreateDevice itself). Destroy whatever was created
            // so the GL fallback does not leak the instance, messenger and surface.
            if(instance != null) {
                if(surface != 0L) {
                    vkDestroySurfaceKHR(instance, surface, null);
                    surface = 0L;
                }
                if(debugMessenger != 0L) {
                    destroyDebugUtilsMessengerEXT(instance, debugMessenger, null);
                    debugMessenger = 0L;
                }
                vkDestroyInstance(instance, null);
            }
            return;
        }

        vkDeviceWaitIdle(device);

        // M20: init can stop anywhere between createLogicalDevice and createFramebuffers.
        // Pipeline, Drawer and MemoryManager read getSwapChainImages() in their static
        // initializers, so they are only touched once the swapchain exists, and every
        // swapchain-level handle is checked on its own.
        final boolean swapchainCreated = swapChainImages != null;

        if(swapchainCreated) {
            Pipeline.savePipelineCache();

            Drawer drawer = Drawer.getInstance();
            if(drawer != null) {
                drawer.cleanUpSyncObjects();
            }
        }

        if(swapChainImageViews != null) {
            swapChainImageViews.forEach(imageView -> vkDestroyImageView(device, imageView, null));
        }
        if(swapChain != 0L) {
            vkDestroySwapchainKHR(device, swapChain, null);
            swapChain = 0L;
        }

        SceneTargets.destroy();

        // Pipelines reference the shared samplers, so they are destroyed first.
        if(swapchainCreated) {
            Pipeline.cleanUpAll();
        }
        net.vulkanmod.vulkan.texture.VulkanImage.destroySharedSamplers();

        vkDestroyFence(device, immediateFence, null);

        // Destroying the pools also frees the command buffers allocated from them.
        vkDestroyCommandPool(device, commandPool, null);
        vkDestroyCommandPool(device, TransferQueue.getCommandPool(), null);
        TransferQueue.cleanUp();

        // vmaDestroyAllocator does NOT destroy allocations that are still alive, so
        // every texture view, image and buffer still registered must go first, while
        // the device is valid - otherwise vkDestroyDevice reports leaked objects.
        if(swapchainCreated) {
            net.vulkanmod.vulkan.texture.VulkanImage.destroyAllViews();
            MemoryManager.destroyAllResources();
        }

        // The allocator must go while the device is still valid - destroying the
        // device first leaks everything it still owns and aborts.
        if(allocator != 0L) {
            vmaDestroyAllocator(allocator);
        }

        vkDestroyDevice(device, null);

        if(surface != 0L) {
            vkDestroySurfaceKHR(instance, surface, null);
            surface = 0L;
        }
        if(debugMessenger != 0L) {
            destroyDebugUtilsMessengerEXT(instance, debugMessenger, null);
            debugMessenger = 0L;
        }
        vkDestroyInstance(instance, null);
    }

    private static void createInstance() {

        if(ENABLE_VALIDATION_LAYERS && !checkValidationLayerSupport()) {
            throw new RuntimeException("Validation requested but not supported");
        }

        try(MemoryStack stack = stackPush()) {

            // Use calloc to initialize the structs with 0s. Otherwise, the program can crash due to random values

            VkApplicationInfo appInfo = VkApplicationInfo.create();

            appInfo.sType(VK_STRUCTURE_TYPE_APPLICATION_INFO);
            appInfo.pApplicationName(stack.UTF8Safe("VulkanMod"));
            appInfo.applicationVersion(VK_MAKE_VERSION(1, 0, 0));
            appInfo.pEngineName(stack.UTF8Safe("No Engine"));
            appInfo.engineVersion(VK_MAKE_VERSION(1, 0, 0));
            // B1: the highest version up to 1.3 the loader offers; the device may still be
            // older, DeviceFeatures takes min(instance, device).
            instanceApiVersion = Math.min(VK.getInstanceVersionSupported() & ~0xFFF, VK_API_VERSION_1_3);
            appInfo.apiVersion(instanceApiVersion);

            VkInstanceCreateInfo createInfo = VkInstanceCreateInfo.create();

            createInfo.sType(VK_STRUCTURE_TYPE_INSTANCE_CREATE_INFO);
            createInfo.pApplicationInfo(appInfo);
            // enabledExtensionCount is implicitly set when you call ppEnabledExtensionNames
            createInfo.ppEnabledExtensionNames(getRequiredExtensions());

            if(ENABLE_VALIDATION_LAYERS) {

                createInfo.ppEnabledLayerNames(asPointerBuffer(VALIDATION_LAYERS));

                VkDebugUtilsMessengerCreateInfoEXT debugCreateInfo = VkDebugUtilsMessengerCreateInfoEXT.create();
                populateDebugMessengerCreateInfo(debugCreateInfo);
                createInfo.pNext(debugCreateInfo.address());
            }

            // We need to retrieve the pointer of the created instance
            PointerBuffer instancePtr = stack.mallocPointer(1);

            if(vkCreateInstance(createInfo, null, instancePtr) != VK_SUCCESS) {
                throw new RuntimeException("Failed to create instance");
            }

            instance = new VkInstance(instancePtr.get(0), createInfo);
        }
    }

    private static void populateDebugMessengerCreateInfo(VkDebugUtilsMessengerCreateInfoEXT debugCreateInfo) {
        debugCreateInfo.sType(VK_STRUCTURE_TYPE_DEBUG_UTILS_MESSENGER_CREATE_INFO_EXT);
        debugCreateInfo.messageSeverity(VK_DEBUG_UTILS_MESSAGE_SEVERITY_WARNING_BIT_EXT | VK_DEBUG_UTILS_MESSAGE_SEVERITY_ERROR_BIT_EXT);
        debugCreateInfo.messageType(VK_DEBUG_UTILS_MESSAGE_TYPE_GENERAL_BIT_EXT | VK_DEBUG_UTILS_MESSAGE_TYPE_VALIDATION_BIT_EXT | VK_DEBUG_UTILS_MESSAGE_TYPE_PERFORMANCE_BIT_EXT);
        debugCreateInfo.pfnUserCallback(Vulkan::debugCallback);
    }

    private static void setupDebugMessenger() {

        if(!ENABLE_VALIDATION_LAYERS) {
            return;
        }

        try(MemoryStack stack = stackPush()) {

            VkDebugUtilsMessengerCreateInfoEXT createInfo = VkDebugUtilsMessengerCreateInfoEXT.create();

            populateDebugMessengerCreateInfo(createInfo);

            LongBuffer pDebugMessenger = stack.longs(VK_NULL_HANDLE);

            if(createDebugUtilsMessengerEXT(instance, createInfo, null, pDebugMessenger) != VK_SUCCESS) {
                throw new RuntimeException("Failed to set up debug messenger");
            }

            debugMessenger = pDebugMessenger.get(0);
        }
    }

    private static void createSurface(long handle) {
        window = handle;

        try(MemoryStack stack = stackPush()) {

            // SDL loads its Vulkan backend lazily; make sure it is ready before querying.
            SDL_Vulkan_LoadLibrary((String) null);

            LongBuffer pSurface = stack.longs(VK_NULL_HANDLE);

            if(!SDL_Vulkan_CreateSurface(window, instance, null, pSurface)) {
                throw new RuntimeException("Failed to create window surface");
            }

            surface = pSurface.get(0);
        }
    }

    private static void pickPhysicalDevice() {

        try(MemoryStack stack = stackPush()) {

            IntBuffer deviceCount = stack.ints(0);

            vkEnumeratePhysicalDevices(instance, deviceCount, null);

            if(deviceCount.get(0) == 0) {
                throw new RuntimeException("Failed to find GPUs with Vulkan support");
            }

            PointerBuffer ppPhysicalDevices = stack.mallocPointer(deviceCount.get(0));

            vkEnumeratePhysicalDevices(instance, deviceCount, ppPhysicalDevices);

            ArrayList<VkPhysicalDevice> integratedGPUs = new ArrayList<>();
            ArrayList<VkPhysicalDevice> otherDevices = new ArrayList<>();

            VkPhysicalDevice currentDevice = null;
            boolean flag = false;

            for(int i = 0; i < ppPhysicalDevices.capacity();i++) {

                currentDevice = new VkPhysicalDevice(ppPhysicalDevices.get(i), instance);

                VkPhysicalDeviceProperties deviceProperties = VkPhysicalDeviceProperties.create();
                vkGetPhysicalDeviceProperties(currentDevice, deviceProperties);

                if(isDeviceSuitable(currentDevice)) {
                    if(deviceProperties.deviceType() == VK_PHYSICAL_DEVICE_TYPE_DISCRETE_GPU){
                        flag = true;
                        break;
                    }
                    else if(deviceProperties.deviceType() == VK_PHYSICAL_DEVICE_TYPE_INTEGRATED_GPU) integratedGPUs.add(currentDevice);
                    else otherDevices.add(currentDevice);

                }
            }

            if(!flag) {
                if(!integratedGPUs.isEmpty()) currentDevice = integratedGPUs.get(0);
                else if(!otherDevices.isEmpty()) currentDevice = otherDevices.get(0);
                else {
                    Initializer.LOGGER.error(DeviceInfo.debugString(ppPhysicalDevices, DEVICE_EXTENSIONS, instance));
                    throw new RuntimeException("Failed to find a suitable GPU");
                }
            }

            physicalDevice = currentDevice;

            //Get device properties

            deviceProperties = VkPhysicalDeviceProperties.malloc();
            vkGetPhysicalDeviceProperties(physicalDevice, deviceProperties);

            memoryProperties = VkPhysicalDeviceMemoryProperties.malloc();
            vkGetPhysicalDeviceMemoryProperties(physicalDevice, memoryProperties);

            deviceInfo = new DeviceInfo(physicalDevice, deviceProperties);
        }
    }

    private static void createLogicalDevice() {

        try(MemoryStack stack = stackPush()) {

            QueueFamilyIndices indices = findQueueFamilies(physicalDevice);
            queueSetup = QueueSetup.plan(physicalDevice, indices);

            int[] families = queueSetup.families();
            VkDeviceQueueCreateInfo.Buffer queueCreateInfos = VkDeviceQueueCreateInfo.calloc(families.length, stack);

            for(int i = 0;i < families.length;i++) {
                int count = queueSetup.queueCount(families[i]);
                java.nio.FloatBuffer priorities = stack.mallocFloat(count);
                for(int q = 0; q < count; q++) priorities.put(q, 1.0f);

                VkDeviceQueueCreateInfo queueCreateInfo = queueCreateInfos.get(i);
                queueCreateInfo.sType(VK_STRUCTURE_TYPE_DEVICE_QUEUE_CREATE_INFO);
                queueCreateInfo.queueFamilyIndex(families[i]);
                queueCreateInfo.pQueuePriorities(priorities);
            }

            // B1: optional features are queried through the pNext chain and enabled only
            // when present; requesting an unsupported one fails vkCreateDevice.
            DeviceFeatures features = new DeviceFeatures();
            long featuresChain = features.configure(physicalDevice, instanceApiVersion,
                    availableDeviceExtensions(physicalDevice), stack);
            deviceInfo.features = features;

            // ChunkBatcher submits one indirect call with drawCount > 1 per chunk layer.
            // Without the feature Drawer.drawIndexedIndirect replays the commands one by one.
            multiDrawIndirectSupported = features.multiDrawIndirect;
            // GlStateManager.colorLogicOp/logicOp mirror (N22).
            logicOpSupported = features.logicOp;
            // M3: vkCmdSetLineWidth must stay inside limits.lineWidthRange
            // (VUID-vkCmdSetLineWidth-lineWidth-00788). Without wideLines the range is
            // [1,1], so a mirrored glLineWidth has to clamp to exactly 1 - both bounds.
            boolean wideLines = features.wideLines;
            VRenderSystem.setLineWidthRange(
                    wideLines ? deviceProperties.limits().lineWidthRange(0) : 1.0f,
                    wideLines ? deviceProperties.limits().lineWidthRange(1) : 1.0f);

            Set<String> extensions = new LinkedHashSet<>(DEVICE_EXTENSIONS);
            extensions.addAll(features.extensions);

            VkDeviceCreateInfo createInfo = VkDeviceCreateInfo.calloc(stack);

            createInfo.sType(VK_STRUCTURE_TYPE_DEVICE_CREATE_INFO);
            createInfo.pQueueCreateInfos(queueCreateInfos);
            // queueCreateInfoCount is automatically set

            if(features.apiVersion >= VK_API_VERSION_1_1) {
                // VkPhysicalDeviceFeatures2 in pNext replaces pEnabledFeatures.
                createInfo.pNext(featuresChain);
            } else {
                createInfo.pEnabledFeatures(VkPhysicalDeviceFeatures2.create(featuresChain).features());
            }

            createInfo.ppEnabledExtensionNames(asPointerBuffer(extensions));
            // Device layers are deprecated; validation is enabled on the instance.

            PointerBuffer pDevice = stack.pointers(VK_NULL_HANDLE);

            if(vkCreateDevice(physicalDevice, createInfo, null, pDevice) != VK_SUCCESS) {
                throw new RuntimeException("Failed to create logical device");
            }

            device = new VkDevice(pDevice.get(0), physicalDevice, createInfo, features.apiVersion);

            queueSetup.fetch(device);
            graphicsQueue = queueSetup.graphics;
            presentQueue = queueSetup.present;

            Initializer.LOGGER.info("Device features: {}", features);
            Initializer.LOGGER.info("Queues: {}", queueSetup);
        }
    }

    private static Set<String> availableDeviceExtensions(VkPhysicalDevice device) {
        try(MemoryStack stack = stackPush()) {
            IntBuffer count = stack.ints(0);
            vkEnumerateDeviceExtensionProperties(device, (String)null, count, null);
            VkExtensionProperties.Buffer props = VkExtensionProperties.malloc(count.get(0), stack);
            vkEnumerateDeviceExtensionProperties(device, (String)null, count, props);
            Set<String> names = new HashSet<>();
            for(int i = 0; i < props.capacity(); i++) names.add(props.get(i).extensionNameString());
            return names;
        }
    }

    private static void createVma() {
        try(MemoryStack stack = stackPush()) {

            VmaVulkanFunctions vulkanFunctions = VmaVulkanFunctions.create();
            vulkanFunctions.set(instance, device);

            VmaAllocatorCreateInfo allocatorCreateInfo = VmaAllocatorCreateInfo.create();
            allocatorCreateInfo.physicalDevice(physicalDevice);
            allocatorCreateInfo.device(device);
            allocatorCreateInfo.pVulkanFunctions(vulkanFunctions);
            allocatorCreateInfo.instance(instance);
            allocatorCreateInfo.vulkanApiVersion(deviceInfo.features.apiVersion);
            if(deviceInfo.features.bufferDeviceAddress) {
                // Arena and AS buffers need vkGetBufferDeviceAddress (T2).
                allocatorCreateInfo.flags(VMA_ALLOCATOR_CREATE_BUFFER_DEVICE_ADDRESS_BIT);
            }

            PointerBuffer pAllocator = stack.pointers(VK_NULL_HANDLE);

            if (vmaCreateAllocator(allocatorCreateInfo, pAllocator) != VK_SUCCESS) {
                throw new RuntimeException("Failed to create command pool");
            }

            allocator = pAllocator.get(0);
        }
    }

    private static void createCommandPool() {

        try(MemoryStack stack = stackPush()) {

            QueueFamilyIndices queueFamilyIndices = findQueueFamilies(physicalDevice);

            VkCommandPoolCreateInfo poolInfo = VkCommandPoolCreateInfo.create();
            poolInfo.sType(VK_STRUCTURE_TYPE_COMMAND_POOL_CREATE_INFO);
            poolInfo.queueFamilyIndex(queueFamilyIndices.graphicsFamily);
            poolInfo.flags(VK_COMMAND_POOL_CREATE_RESET_COMMAND_BUFFER_BIT);

            LongBuffer pCommandPool = stack.mallocLong(1);

            if (vkCreateCommandPool(device, poolInfo, null, pCommandPool) != VK_SUCCESS) {
                throw new RuntimeException("Failed to create command pool");
            }

            commandPool = pCommandPool.get(0);
        }
    }

    private static void createSwapChain() {

        try(MemoryStack stack = stackPush()) {

            // R10: a retired swapchain must not be passed again as oldSwapchain
            // (VUID-VkSwapchainCreateInfoKHR-oldSwapchain-01933); it is still destroyed
            // below once the new one exists.
            long oldSwapchain = swapChain != 0L && !swapchainRetired ? swapChain : VK_NULL_HANDLE;

            SwapChainSupportDetails swapChainSupport = querySwapChainSupport(physicalDevice, stack);

            if(swapChainSupport.formats == null || swapChainSupport.formats.capacity() == 0) {
                throw new RuntimeException("Surface reports no supported formats");
            }

            VkSurfaceFormatKHR surfaceFormat = chooseSwapSurfaceFormat(swapChainSupport.formats);
            int presentMode = chooseSwapPresentMode(swapChainSupport.presentModes);
            VkExtent2D extent = chooseSwapExtent(swapChainSupport.capabilities);

            // The configured frame count is only a hint: the surface's minimum (and,
            // when set, maximum) image count always wins.
            int requestedImages = Math.max(frameQueueSize, swapChainSupport.capabilities.minImageCount());
            int maxImages = swapChainSupport.capabilities.maxImageCount();
            if(maxImages > 0 && requestedImages > maxImages) {
                requestedImages = maxImages;
            }
            IntBuffer imageCount = stack.ints(requestedImages);

            VkSwapchainCreateInfoKHR createInfo = VkSwapchainCreateInfoKHR.create();

            createInfo.sType(VK_STRUCTURE_TYPE_SWAPCHAIN_CREATE_INFO_KHR);
            createInfo.surface(surface);

            // Image settings. R10: swapChainImageFormat/swapChainExtent describe the
            // swapchain that exists, so they are published only after a successful
            // create; a failed recreation then rebuilds its framebuffers with the size
            // of the views it still has.
            createInfo.minImageCount(imageCount.get(0));
            createInfo.imageFormat(surfaceFormat.format());
            createInfo.imageColorSpace(surfaceFormat.colorSpace());
            createInfo.imageExtent(extent);
            createInfo.imageArrayLayers(1);
            // TRANSFER_DST: the scene target is copied in (B2). TRANSFER_SRC lets the
            // autotest copy presented frames back to the CPU.
            if((swapChainSupport.capabilities.supportedUsageFlags() & VK_IMAGE_USAGE_TRANSFER_DST_BIT) == 0) {
                throw new RuntimeException("Surface does not support TRANSFER_DST swapchain images");
            }
            createInfo.imageUsage(VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT | VK_IMAGE_USAGE_TRANSFER_SRC_BIT
                    | VK_IMAGE_USAGE_TRANSFER_DST_BIT);

            QueueFamilyIndices indices = findQueueFamilies(physicalDevice);

            if(!indices.graphicsFamily.equals(indices.presentFamily)) {
                createInfo.imageSharingMode(VK_SHARING_MODE_CONCURRENT);
                createInfo.pQueueFamilyIndices(stack.ints(indices.graphicsFamily, indices.presentFamily));
            } else {
                createInfo.imageSharingMode(VK_SHARING_MODE_EXCLUSIVE);
            }

            createInfo.preTransform(swapChainSupport.capabilities.currentTransform());
            createInfo.compositeAlpha(VK_COMPOSITE_ALPHA_OPAQUE_BIT_KHR);
            createInfo.presentMode(presentMode);
            createInfo.clipped(true);

            //long oldSwapchain = swapChain != NULL ? swapChain : VK_NULL_HANDLE;
            createInfo.oldSwapchain(oldSwapchain);

            LongBuffer pSwapChain = stack.longs(VK_NULL_HANDLE);

            int result = vkCreateSwapchainKHR(device, createInfo, null, pSwapChain);
            if(result != VK_SUCCESS) {
                // Passing oldSwapchain retired it even though the create failed (R10).
                if(oldSwapchain != VK_NULL_HANDLE) {
                    swapchainRetired = true;
                }
                throw new RuntimeException("Failed to create swap chain: " + result);
            }

            swapChainImageFormat = surfaceFormat.format();
            swapChainExtent = VkExtent2D.create().set(extent);

            // Destroy the previous swapchain whenever one exists - also a retired one,
            // for which oldSwapchain stayed VK_NULL_HANDLE.
            if(swapChain != 0L) {
                swapChainImageViews.forEach(imageView -> vkDestroyImageView(device, imageView, null));
                vkDestroySwapchainKHR(device, swapChain, null);
            }
            swapchainRetired = false;

            swapChain = pSwapChain.get(0);

            vkGetSwapchainImagesKHR(device, swapChain, imageCount, null);

            LongBuffer pSwapchainImages = stack.mallocLong(imageCount.get(0));

            vkGetSwapchainImagesKHR(device, swapChain, imageCount, pSwapchainImages);

            swapChainImages = new ArrayList<>(imageCount.get(0));

            for(int i = 0;i < pSwapchainImages.capacity();i++) {
                swapChainImages.add(pSwapchainImages.get(i));
            }
        }
    }

    private static void createImageViews() {

        swapChainImageViews = new ArrayList<>(swapChainImages.size());

        for(long swapChainImage : swapChainImages) {
            swapChainImageViews.add(createImageView(swapChainImage, swapChainImageFormat, VK_IMAGE_ASPECT_COLOR_BIT, 1));
        }
    }

    private static int findSupportedFormat(IntBuffer formatCandidates, int tiling, int features) {

        try(MemoryStack stack = stackPush()) {

            VkFormatProperties props = VkFormatProperties.create();

            for(int i = 0; i < formatCandidates.capacity(); ++i) {

                int format = formatCandidates.get(i);

                vkGetPhysicalDeviceFormatProperties(physicalDevice, format, props);

                if(tiling == VK_IMAGE_TILING_LINEAR && (props.linearTilingFeatures() & features) == features) {
                    return format;
                } else if(tiling == VK_IMAGE_TILING_OPTIMAL && (props.optimalTilingFeatures() & features) == features) {
                    return format;
                }

            }
        }

        throw new RuntimeException("Failed to find supported format");
    }

    static int findDepthFormat() {
        return findSupportedFormat(
                stackGet().ints(VK_FORMAT_D32_SFLOAT, VK_FORMAT_D32_SFLOAT_S8_UINT, VK_FORMAT_D24_UNORM_S8_UINT),
                VK_IMAGE_TILING_OPTIMAL,
                VK_FORMAT_FEATURE_DEPTH_STENCIL_ATTACHMENT_BIT);
    }



    private static VkSurfaceFormatKHR chooseSwapSurfaceFormat(VkSurfaceFormatKHR.Buffer availableFormats) {
        List<VkSurfaceFormatKHR> list = availableFormats.stream().toList();

        VkSurfaceFormatKHR format = list.get(0);
        boolean flag = true;

        for (VkSurfaceFormatKHR availableFormat : list) {
            if (availableFormat.format() == VK_FORMAT_R8G8B8A8_UNORM && availableFormat.colorSpace() == VK_COLOR_SPACE_SRGB_NONLINEAR_KHR)
                return availableFormat;

            if (availableFormat.format() == VK_FORMAT_B8G8R8A8_UNORM && availableFormat.colorSpace() == VK_COLOR_SPACE_SRGB_NONLINEAR_KHR) {
                format = availableFormat;
                flag = false;
            }
        }

        if(flag) System.out.println("Non-optimal surface format.");
        return format;
    }

    private static int chooseSwapPresentMode(IntBuffer availablePresentModes) {

        // FIFO is the only mode a Vulkan implementation has to support, so the low
        // latency modes are picked only when the surface actually offers them.
        boolean mailbox = false;
        boolean immediate = false;
        for (int i = 0; i < availablePresentModes.capacity(); i++) {
            int mode = availablePresentModes.get(i);
            if (mode == VK_PRESENT_MODE_MAILBOX_KHR) {
                mailbox = true;
            }
            else if (mode == VK_PRESENT_MODE_IMMEDIATE_KHR) {
                immediate = true;
            }
        }

        if (vsync) {
            return VK_PRESENT_MODE_FIFO_KHR;
        }
        // MAILBOX is the tear-free low-latency mode; IMMEDIATE tears, so it is
        // only the fallback for surfaces without mailbox.
        if (mailbox) {
            return VK_PRESENT_MODE_MAILBOX_KHR;
        }
        if (immediate) {
            return VK_PRESENT_MODE_IMMEDIATE_KHR;
        }
        return VK_PRESENT_MODE_FIFO_KHR;
    }

    private static VkExtent2D chooseSwapExtent(VkSurfaceCapabilitiesKHR capabilities) {

        if(capabilities.currentExtent().width() != UINT32_MAX) {
            return capabilities.currentExtent();
        }

        IntBuffer width = stackGet().ints(0);
        IntBuffer height = stackGet().ints(0);

        SDL_GetWindowSizeInPixels(window, width, height);

        VkExtent2D actualExtent = VkExtent2D.create().set(width.get(0), height.get(0));

        VkExtent2D minExtent = capabilities.minImageExtent();
        VkExtent2D maxExtent = capabilities.maxImageExtent();

        actualExtent.width(clamp(minExtent.width(), maxExtent.width(), actualExtent.width()));
        actualExtent.height(clamp(minExtent.height(), maxExtent.height(), actualExtent.height()));

        return actualExtent;
    }

    private static int clamp(int min, int max, int value) {
        return Math.max(min, Math.min(max, value));
    }

    public static long createImageView(long image, int format, int aspectFlags, int mipLevels) {

        try(MemoryStack stack = stackPush()) {

            VkImageViewCreateInfo viewInfo = VkImageViewCreateInfo.create();
            viewInfo.sType(VK_STRUCTURE_TYPE_IMAGE_VIEW_CREATE_INFO);
            viewInfo.image(image);
            viewInfo.viewType(VK_IMAGE_VIEW_TYPE_2D);
            viewInfo.format(format);
            viewInfo.subresourceRange().aspectMask(aspectFlags);
            viewInfo.subresourceRange().baseMipLevel(0);
            viewInfo.subresourceRange().levelCount(mipLevels);
            viewInfo.subresourceRange().baseArrayLayer(0);
            viewInfo.subresourceRange().layerCount(1);

            LongBuffer pImageView = stack.mallocLong(1);

            if(vkCreateImageView(device, viewInfo, null, pImageView) != VK_SUCCESS) {
                throw new RuntimeException("Failed to create texture image view");
            }

            return pImageView.get(0);
        }
    }

    public static VkViewport.Buffer viewport(MemoryStack stack) {
        VkViewport.Buffer viewport = VkViewport.calloc(1, stack);
        viewport.x(0.0f);
        viewport.y(swapChainExtent.height());
        viewport.width(swapChainExtent.width());
        viewport.height(-swapChainExtent.height());
        viewport.minDepth(0.0f);
        viewport.maxDepth(1.0f);

        return viewport;
    }

    public static VkRect2D.Buffer scissor(MemoryStack stack) {
        VkRect2D.Buffer scissor = VkRect2D.calloc(1, stack);
        scissor.offset(VkOffset2D.calloc(stack).set(0, 0));
        scissor.extent(swapChainExtent);

        return scissor;
    }

    public static void copyStagingtoLocalBuffer(long srcBuffer, long dstBuffer, long dstOffset, long size) {

        try(MemoryStack stack = stackPush()) {

            VkCommandBuffer commandBuffer = beginImmediateCmd();

            VkBufferCopy.Buffer copyRegion = VkBufferCopy.calloc(1, stack);
            copyRegion.size(size);
            copyRegion.dstOffset(dstOffset);

            // Readback path (MemoryTypes.copyFromBuffer): earlier submits may still have
            // writes to the source pending, and the caller maps the destination right after
            // the fence, so make the copy's writes visible to the host (N2).
            VkMemoryBarrier.Buffer barrier = VkMemoryBarrier.calloc(1, stack);
            barrier.sType(VK_STRUCTURE_TYPE_MEMORY_BARRIER);
            barrier.srcAccessMask(VK_ACCESS_MEMORY_WRITE_BIT);
            barrier.dstAccessMask(VK_ACCESS_TRANSFER_READ_BIT | VK_ACCESS_TRANSFER_WRITE_BIT);
            vkCmdPipelineBarrier(commandBuffer, VK_PIPELINE_STAGE_ALL_COMMANDS_BIT,
                    VK_PIPELINE_STAGE_TRANSFER_BIT, 0, barrier, null, null);

            vkCmdCopyBuffer(commandBuffer, srcBuffer, dstBuffer, copyRegion);

            barrier.srcAccessMask(VK_ACCESS_TRANSFER_WRITE_BIT);
            barrier.dstAccessMask(VK_ACCESS_HOST_READ_BIT);
            vkCmdPipelineBarrier(commandBuffer, VK_PIPELINE_STAGE_TRANSFER_BIT,
                    VK_PIPELINE_STAGE_HOST_BIT, 0, barrier, null, null);

            endImmediateCmd();
        }
    }

    /**
     * Buffer-to-buffer copy. Inside the frame window it is recorded into the frame's
     * transfer command buffer (submitted and fenced with the frame, no CPU wait);
     * outside it goes through the TransferQueue with a fence of its own.
     */
    public static void copyStagingtoLocalBuffer(long srcBuffer, long srcOffset, long dstBuffer, long dstOffset, long size) {

        try(MemoryStack stack = stackPush()) {

            VkCommandBuffer frameCmd = Drawer.getFrameTransferCmd();

            VkBufferCopy.Buffer copyRegion = VkBufferCopy.create(1);
            copyRegion.size(size);
            copyRegion.srcOffset(srcOffset);
            copyRegion.dstOffset(dstOffset);

            if(frameCmd != null) {
                vkCmdCopyBuffer(frameCmd, srcBuffer, dstBuffer, copyRegion);
                return;
            }

            TransferQueue.CommandBuffer commandBuffer = TransferQueue.beginCommands();

            vkCmdCopyBuffer(commandBuffer.getHandle(), srcBuffer, dstBuffer, copyRegion);

            TransferQueue.endCommands(commandBuffer);
        }
    }

    private static void allocateImmediateCmdBuffer() {
        try(MemoryStack stack = stackPush()) {

            VkCommandBufferAllocateInfo allocInfo = VkCommandBufferAllocateInfo.create();
            allocInfo.sType(VK_STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO);
            allocInfo.level(VK_COMMAND_BUFFER_LEVEL_PRIMARY);
            allocInfo.commandPool(commandPool);
            allocInfo.commandBufferCount(1);

            PointerBuffer pCommandBuffer = stack.mallocPointer(1);
            vkAllocateCommandBuffers(device, allocInfo, pCommandBuffer);
            immediateCmdBuffer = new VkCommandBuffer(pCommandBuffer.get(0), device);

            VkFenceCreateInfo fenceInfo = VkFenceCreateInfo.create();
            fenceInfo.sType(VK_STRUCTURE_TYPE_FENCE_CREATE_INFO);
            fenceInfo.flags(VK_FENCE_CREATE_SIGNALED_BIT);

            LongBuffer pFence = stack.mallocLong(1);
            vkCreateFence(device, fenceInfo, null, pFence);
            vkResetFences(device,  pFence.get(0));

            immediateFence = pFence.get(0);
        }
    }

    public static VkCommandBuffer beginImmediateCmd() {
        try (MemoryStack stack = stackPush()) {
            VkCommandBufferBeginInfo beginInfo = VkCommandBufferBeginInfo.calloc(stack);
            beginInfo.sType(VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO);

            vkBeginCommandBuffer(immediateCmdBuffer, beginInfo);
        }
        return immediateCmdBuffer;
    }

    public static void endImmediateCmd() {
        try (MemoryStack stack = stackPush()) {
            vkEndCommandBuffer(immediateCmdBuffer);

            VkSubmitInfo.Buffer submitInfo = VkSubmitInfo.calloc(1, stack);
            submitInfo.sType(VK_STRUCTURE_TYPE_SUBMIT_INFO);
            submitInfo.pCommandBuffers(stack.pointers(immediateCmdBuffer));

            int result = vkQueueSubmit(graphicsQueue, submitInfo, immediateFence);
            if(result != VK_SUCCESS) {
                throw new RuntimeException("Failed to submit immediate command buffer: " + result);
            }

            vkWaitForFences(device, immediateFence, true, VUtil.UINT64_MAX);
            vkResetFences(device, immediateFence);
            vkResetCommandBuffer(immediateCmdBuffer, 0);
        }

    }

    private static PointerBuffer asPointerBuffer(Collection<String> collection) {

        MemoryStack stack = stackGet();

        PointerBuffer buffer = stack.mallocPointer(collection.size());

        collection.stream()
                .map(stack::UTF8)
                .forEach(buffer::put);

        return buffer.rewind();
    }

    private static PointerBuffer getRequiredExtensions() {

        PointerBuffer sdlExtensions = SDL_Vulkan_GetInstanceExtensions();

        if(ENABLE_VALIDATION_LAYERS) {

            MemoryStack stack = stackGet();

            PointerBuffer extensions = stack.mallocPointer(sdlExtensions.capacity() + 1);

            extensions.put(sdlExtensions);
            extensions.put(stack.UTF8(VK_EXT_DEBUG_UTILS_EXTENSION_NAME));

            // Rewind the buffer before returning it to reset its position back to 0
            return extensions.rewind();
        }

        return sdlExtensions;
    }

    private static boolean isDeviceSuitable(VkPhysicalDevice device) {

        QueueFamilyIndices indices = findQueueFamilies(device);

        boolean extensionsSupported = checkDeviceExtensionSupport(device);
        boolean swapChainAdequate = false;

        if(extensionsSupported) {
            try(MemoryStack stack = stackPush()) {
                SwapChainSupportDetails swapChainSupport = querySwapChainSupport(device, stack);
                swapChainAdequate = swapChainSupport.formats != null && swapChainSupport.formats.hasRemaining()
                        && swapChainSupport.presentModes != null && swapChainSupport.presentModes.hasRemaining();
            }
        }

        // Optional features are queried and gated at device creation (DeviceFeatures);
        // only the baseline the frame structure depends on is checked here.

        return indices.isComplete() && extensionsSupported && swapChainAdequate
                && DeviceFeatures.meetsBaseline(device, instanceApiVersion);
    }

    private static boolean checkDeviceExtensionSupport(VkPhysicalDevice device) {

        try(MemoryStack stack = stackPush()) {

            IntBuffer extensionCount = stack.ints(0);

            vkEnumerateDeviceExtensionProperties(device, (String)null, extensionCount, null);

            VkExtensionProperties.Buffer availableExtensions = VkExtensionProperties.create(extensionCount.get(0));

            vkEnumerateDeviceExtensionProperties(device, (String)null, extensionCount, availableExtensions);

            Set<String> extensions = availableExtensions.stream()
                    .map(VkExtensionProperties::extensionNameString)
                    .collect(toSet());

            extensions.removeAll(DEVICE_EXTENSIONS);

            return availableExtensions.stream()
                    .map(VkExtensionProperties::extensionNameString)
                    .collect(toSet())
                    .containsAll(DEVICE_EXTENSIONS);
        }
    }

    public static SwapChainSupportDetails querySwapChainSupport(VkPhysicalDevice device, MemoryStack stack) {

        SwapChainSupportDetails details = new SwapChainSupportDetails();

        // Everything is allocated on the caller's stack: the details (and the static
        // field pointing at them) are only ever read inside the caller's stack scope.
        details.capabilities = VkSurfaceCapabilitiesKHR.calloc(stack);
        vkGetPhysicalDeviceSurfaceCapabilitiesKHR(device, surface, details.capabilities);

        IntBuffer count = stack.ints(0);

        vkGetPhysicalDeviceSurfaceFormatsKHR(device, surface, count, null);

        if(count.get(0) != 0) {
            details.formats = VkSurfaceFormatKHR.calloc(count.get(0), stack);
            vkGetPhysicalDeviceSurfaceFormatsKHR(device, surface, count, details.formats);
        }

        vkGetPhysicalDeviceSurfacePresentModesKHR(device,surface, count, null);

        if(count.get(0) != 0) {
            details.presentModes = stack.mallocInt(count.get(0));
            vkGetPhysicalDeviceSurfacePresentModesKHR(device, surface, count, details.presentModes);
        }

        return details;
    }

    public static QueueFamilyIndices findQueueFamilies(VkPhysicalDevice device) {

        QueueFamilyIndices indices = new QueueFamilyIndices();

        try(MemoryStack stack = stackPush()) {

            IntBuffer queueFamilyCount = stack.ints(0);

            vkGetPhysicalDeviceQueueFamilyProperties(device, queueFamilyCount, null);

            VkQueueFamilyProperties.Buffer queueFamilies = VkQueueFamilyProperties.create(queueFamilyCount.get(0));

            vkGetPhysicalDeviceQueueFamilyProperties(device, queueFamilyCount, queueFamilies);

            IntBuffer presentSupport = stack.ints(VK_FALSE);

            // Stop at the family count: a GPU without a present-capable queue must return
            // incomplete indices (isDeviceSuitable() rejects it), not run off the array.
            for(int i = 0;i < queueFamilies.capacity() && !indices.isComplete();i++) {

                if((queueFamilies.get(i).queueFlags() & VK_QUEUE_GRAPHICS_BIT) != 0) {
                    indices.graphicsFamily = i;
                }

                vkGetPhysicalDeviceSurfaceSupportKHR(device, i, surface, presentSupport);

                if(presentSupport.get(0) == VK_TRUE) {
                    indices.presentFamily = i;
                }

                if(indices.isComplete()) break;
            }

            return indices;
        }
    }

    private static boolean checkValidationLayerSupport() {

        try(MemoryStack stack = stackPush()) {

            IntBuffer layerCount = stack.ints(0);

            vkEnumerateInstanceLayerProperties(layerCount, null);

            VkLayerProperties.Buffer availableLayers = VkLayerProperties.create(layerCount.get(0));

            vkEnumerateInstanceLayerProperties(layerCount, availableLayers);

            Set<String> availableLayerNames = availableLayers.stream()
                    .map(VkLayerProperties::layerNameString)
                    .collect(toSet());

            return availableLayerNames.containsAll(VALIDATION_LAYERS);
        }
    }

    public static void setVsync(boolean b) {
        if(vsync != b) {
            Drawer.shouldRecreate = true;
            vsync = b;
        }
    }

    public static VkQueue getPresentQueue() { return presentQueue; }

    public static VkQueue getGraphicsQueue() { return graphicsQueue; }

    public static QueueSetup getQueues() { return queueSetup; }

    public static long getSwapChain() { return swapChain; }

    public static boolean isSwapchainRetired() { return swapchainRetired; }

    public static VkExtent2D getSwapchainExtent()
    {
        return swapChainExtent;
    }

    public static List<Long> getSwapChainImages() { return swapChainImages; }

    public static int getSwapChainImageFormat() { return swapChainImageFormat; }

    public static long getCommandPool()
    {
        return commandPool;
    }

    public static StagingBuffer getStagingBuffer(int i) { return stagingBuffers[i]; }

    public static DeviceInfo getDeviceInfo() { return deviceInfo; }
}

