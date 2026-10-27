// Smoke test for libamd_fidelityfx_vk.so.
//
// It exercises the parts of the library that do not need a window/swapchain:
//   1. ffxQuery(GET_VERSIONS)     - ffx-api provider enumeration
//   2. ffxCreateContext(UPSCALE)  - FSR3 upscaler context creation, which
//                                   creates every compute pipeline, i.e. loads
//                                   and reflects all FSR3 shader permutations
//   3. ffxQuery(GPU_MEMORY_USAGE) - per-context query path
//   4. ffxDestroyContext
//
// A headless Vulkan device is created with the first available physical device.
//
// Build: see tests/CMakeLists.txt (or the commands in REPORT.md).

#include <ffx_api/ffx_api.h>
#include <ffx_api/ffx_upscale.h>
#include <ffx_api/ffx_framegeneration.h>
#include <ffx_api/vk/ffx_api_vk.h>

#include <FidelityFX/host/backends/vk/ffx_vk.h>

#include <vulkan/vulkan.h>

#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <vector>

#define CHECK(expr)                                                          \
    do                                                                       \
    {                                                                        \
        if (!(expr))                                                         \
        {                                                                    \
            std::printf("FAILED: %s (%s:%d)\n", #expr, __FILE__, __LINE__);  \
            return 1;                                                        \
        }                                                                    \
    } while (0)

static int createVulkanDevice(VkInstance* instance, VkPhysicalDevice* physicalDevice, VkDevice* device)
{
    VkApplicationInfo appInfo{};
    appInfo.sType              = VK_STRUCTURE_TYPE_APPLICATION_INFO;
    appInfo.pApplicationName   = "ffx-api-linux smoke test";
    appInfo.apiVersion         = VK_API_VERSION_1_2;

    VkInstanceCreateInfo instanceInfo{};
    instanceInfo.sType            = VK_STRUCTURE_TYPE_INSTANCE_CREATE_INFO;
    instanceInfo.pApplicationInfo = &appInfo;

    if (vkCreateInstance(&instanceInfo, nullptr, instance) != VK_SUCCESS)
    {
        std::printf("vkCreateInstance failed\n");
        return 1;
    }

    uint32_t deviceCount = 0;
    vkEnumeratePhysicalDevices(*instance, &deviceCount, nullptr);
    if (deviceCount == 0)
    {
        std::printf("no Vulkan physical device\n");
        return 1;
    }

    std::vector<VkPhysicalDevice> devices(deviceCount);
    vkEnumeratePhysicalDevices(*instance, &deviceCount, devices.data());
    *physicalDevice = devices[0];

    VkPhysicalDeviceProperties props{};
    vkGetPhysicalDeviceProperties(*physicalDevice, &props);
    std::printf("device: %s\n", props.deviceName);

    uint32_t queueFamilyCount = 0;
    vkGetPhysicalDeviceQueueFamilyProperties(*physicalDevice, &queueFamilyCount, nullptr);
    std::vector<VkQueueFamilyProperties> families(queueFamilyCount);
    vkGetPhysicalDeviceQueueFamilyProperties(*physicalDevice, &queueFamilyCount, families.data());

    uint32_t queueFamilyIndex = UINT32_MAX;
    for (uint32_t i = 0; i < queueFamilyCount; ++i)
    {
        if (families[i].queueFlags & VK_QUEUE_COMPUTE_BIT)
        {
            queueFamilyIndex = i;
            break;
        }
    }
    if (queueFamilyIndex == UINT32_MAX)
    {
        std::printf("no compute queue family\n");
        return 1;
    }

    const float             priority = 1.0f;
    VkDeviceQueueCreateInfo queueInfo{};
    queueInfo.sType            = VK_STRUCTURE_TYPE_DEVICE_QUEUE_CREATE_INFO;
    queueInfo.queueFamilyIndex = queueFamilyIndex;
    queueInfo.queueCount       = 1;
    queueInfo.pQueuePriorities = &priority;

    VkPhysicalDeviceFeatures features{};
    features.shaderStorageImageWriteWithoutFormat = VK_TRUE;

    VkDeviceCreateInfo deviceInfo{};
    deviceInfo.sType                   = VK_STRUCTURE_TYPE_DEVICE_CREATE_INFO;
    deviceInfo.queueCreateInfoCount    = 1;
    deviceInfo.pQueueCreateInfos       = &queueInfo;
    deviceInfo.pEnabledFeatures        = &features;

    if (vkCreateDevice(*physicalDevice, &deviceInfo, nullptr, device) != VK_SUCCESS)
    {
        std::printf("vkCreateDevice failed\n");
        return 1;
    }
    return 0;
}

int main()
{
    // ---------------------------------------------------------------- providers
    uint64_t     providerCount = 0;
    ffxQueryDescGetVersions versions{};
    versions.header.type  = FFX_API_QUERY_DESC_TYPE_GET_VERSIONS;
    versions.createDescType = FFX_API_CREATE_CONTEXT_DESC_TYPE_UPSCALE;
    versions.outputCount  = &providerCount;

    ffxReturnCode_t rc = ffxQuery(nullptr, &versions.header);
    std::printf("ffxQuery(GET_VERSIONS) -> rc=%u, upscale providers=%llu\n", rc,
                static_cast<unsigned long long>(providerCount));
    CHECK(rc == FFX_API_RETURN_OK);
    CHECK(providerCount > 0);

    std::vector<uint64_t>    versionIds(providerCount);
    std::vector<const char*> versionNames(providerCount);
    versions.versionIds   = versionIds.data();
    versions.versionNames = versionNames.data();
    rc                    = ffxQuery(nullptr, &versions.header);
    CHECK(rc == FFX_API_RETURN_OK);
    for (uint64_t i = 0; i < providerCount; ++i)
        std::printf("  provider %llu: %s (id 0x%llx)\n", static_cast<unsigned long long>(i),
                    versionNames[i] ? versionNames[i] : "?", static_cast<unsigned long long>(versionIds[i]));

    // ------------------------------------------------------------------ vulkan
    VkInstance       instance       = VK_NULL_HANDLE;
    VkPhysicalDevice physicalDevice = VK_NULL_HANDLE;
    VkDevice         device         = VK_NULL_HANDLE;
    CHECK(createVulkanDevice(&instance, &physicalDevice, &device) == 0);

    // ------------------------------------------------------- ffx-api backend
    size_t scratchSize = ffxGetScratchMemorySizeVK(physicalDevice, 1);
    std::printf("ffxGetScratchMemorySizeVK = %zu bytes\n", scratchSize);

    void* scratchBuffer = std::calloc(1, scratchSize);
    CHECK(scratchBuffer != nullptr);

    VkDeviceContext vkDeviceContext{};
    vkDeviceContext.vkDevice         = device;
    vkDeviceContext.vkPhysicalDevice = physicalDevice;
    vkDeviceContext.vkDeviceProcAddr = vkGetDeviceProcAddr;

    FfxInterface backendInterface{};
    rc = static_cast<ffxReturnCode_t>(
        ffxGetInterfaceVK(&backendInterface, ffxGetDeviceVK(&vkDeviceContext), scratchBuffer, scratchSize, 1));
    CHECK(rc == FFX_API_RETURN_OK);

    // ------------------------------------------------------ upscaler context
    ffxCreateBackendVKDesc backendDesc{};
    backendDesc.header.type    = FFX_API_CREATE_CONTEXT_DESC_TYPE_BACKEND_VK;
    backendDesc.vkDevice       = device;
    backendDesc.vkPhysicalDevice = physicalDevice;
    backendDesc.vkDeviceProcAddr = vkGetDeviceProcAddr;

    ffxCreateContextDescUpscale upscaleDesc{};
    upscaleDesc.header.type = FFX_API_CREATE_CONTEXT_DESC_TYPE_UPSCALE;
    upscaleDesc.header.pNext = &backendDesc.header;
    upscaleDesc.flags        = 0;
    upscaleDesc.maxRenderSize = {1280, 720};
    upscaleDesc.maxUpscaleSize = {1920, 1080};

    ffxContext context = nullptr;
    rc = ffxCreateContext(&context, &upscaleDesc.header, nullptr);
    std::printf("ffxCreateContext(UPSCALE) -> rc=%u, context=%p\n", rc, context);
    CHECK(rc == FFX_API_RETURN_OK);
    CHECK(context != nullptr);

    // ------------------------------------------------------------ memory usage
    FfxApiEffectMemoryUsage      memoryUsageValue{};
    ffxQueryDescUpscaleGetGPUMemoryUsage memoryUsage{};
    memoryUsage.header.type          = FFX_API_QUERY_DESC_TYPE_UPSCALE_GPU_MEMORY_USAGE;
    memoryUsage.gpuMemoryUsageUpscaler = &memoryUsageValue;
    rc                               = ffxQuery(&context, &memoryUsage.header);
    std::printf("ffxQuery(UPSCALE_GPU_MEMORY_USAGE) -> rc=%u, total=%llu bytes\n", rc,
                static_cast<unsigned long long>(memoryUsageValue.totalUsageInBytes));
    CHECK(rc == FFX_API_RETURN_OK);

    rc = ffxDestroyContext(&context, nullptr);
    std::printf("ffxDestroyContext -> rc=%u\n", rc);
    CHECK(rc == FFX_API_RETURN_OK);

    // ------------------------------------------------ frame generation context
    // Creating a frame generation context also creates the frame interpolation
    // and optical flow contexts it drives, so this loads the shader blobs of
    // all three components.
    uint64_t     fgProviderCount = 0;
    ffxQueryDescGetVersions fgVersions{};
    fgVersions.header.type  = FFX_API_QUERY_DESC_TYPE_GET_VERSIONS;
    fgVersions.createDescType = FFX_API_CREATE_CONTEXT_DESC_TYPE_FRAMEGENERATION;
    fgVersions.outputCount  = &fgProviderCount;
    rc = ffxQuery(nullptr, &fgVersions.header);
    std::printf("ffxQuery(GET_VERSIONS, frame generation) -> rc=%u, providers=%llu\n", rc,
                static_cast<unsigned long long>(fgProviderCount));
    CHECK(rc == FFX_API_RETURN_OK);
    CHECK(fgProviderCount > 0);

    ffxCreateContextDescFrameGeneration fgDesc{};
    fgDesc.header.type        = FFX_API_CREATE_CONTEXT_DESC_TYPE_FRAMEGENERATION;
    fgDesc.header.pNext       = &backendDesc.header;
    fgDesc.flags              = 0;
    fgDesc.displaySize        = {1920, 1080};
    fgDesc.maxRenderSize      = {1280, 720};
    fgDesc.backBufferFormat   = FFX_API_SURFACE_FORMAT_R8G8B8A8_UNORM;

    ffxContext fgContext = nullptr;
    rc = ffxCreateContext(&fgContext, &fgDesc.header, nullptr);
    std::printf("ffxCreateContext(FRAMEGENERATION) -> rc=%u, context=%p\n", rc, fgContext);
    CHECK(rc == FFX_API_RETURN_OK);
    CHECK(fgContext != nullptr);

    rc = ffxDestroyContext(&fgContext, nullptr);
    std::printf("ffxDestroyContext(FRAMEGENERATION) -> rc=%u\n", rc);
    CHECK(rc == FFX_API_RETURN_OK);

    vkDestroyDevice(device, nullptr);
    vkDestroyInstance(instance, nullptr);
    std::free(scratchBuffer);

    std::printf("OK\n");
    return 0;
}
