// VulkanMod <-> NRD bridge: a C API over NRD's NRI integration layer, recording into the
// renderer's own VkCommandBuffer. Two RELAX_DIFFUSE integrations: block light and indirect
// light. Separate integrations, not two denoisers in one: NRD sizes its descriptor pools by
// unique pass names, so a second identical denoiser per frame would overflow them.
// Struct layouts are mirrored by net.vulkanmod.vulkan.NrdDenoiser.

#include <cstddef>
#include <cstdint>
#include <cstdio>
#include <cstring>

#include "NRD.h"
#include "NRI.h"
#include "Extensions/NRIHelper.h"
#include "Extensions/NRIRayTracing.h"
#include "Extensions/NRIWrapperVK.h"
#include "NRDIntegration.hpp"

#define VM_EXPORT extern "C" __attribute__((visibility("default")))

struct VmNrdCreate {
    uint64_t vkInstance, vkPhysicalDevice, vkDevice;
    uint64_t instanceExtensions, deviceExtensions; // const char* const*
    uint32_t instanceExtensionNum, deviceExtensionNum;
    uint32_t queueFamily;
    uint32_t width, height;
    uint32_t queuedFrames;
    uint32_t minorVersion;
    uint32_t pad;
};
static_assert(sizeof(VmNrdCreate) == 72, "layout");

struct VmNrdImage {
    uint64_t image;
    uint32_t format;
    uint32_t layout; // 0: GENERAL (storage), 1: SHADER_READ_ONLY_OPTIMAL
};

struct VmNrdFrame {
    uint64_t commandBuffer;
    float viewToClip[16], viewToClipPrev[16], worldToView[16], worldToViewPrev[16];
    float jitter[2], jitterPrev[2];
    float mvScale[2];
    uint32_t frameIndex;
    uint32_t reset;
    float denoisingRange;
    uint32_t pad;
    VmNrdImage mv, normalRoughness, viewZ, blockIn, blockOut, giIn, giOut;
};
static_assert(offsetof(VmNrdFrame, viewToClip) == 8, "layout");
static_assert(offsetof(VmNrdFrame, jitter) == 264, "layout");
static_assert(offsetof(VmNrdFrame, frameIndex) == 288, "layout");
static_assert(offsetof(VmNrdFrame, mv) == 304, "layout");
static_assert(sizeof(VmNrdFrame) == 416, "layout");

static nrd::Integration* g_nrd[2] = {nullptr, nullptr};
static uint16_t g_width, g_height;
static const nrd::Identifier DENOISER = 1;

static nrd::Resource resource(const VmNrdImage& img) {
    nrd::Resource r = {};
    r.vk.image = (VKNonDispatchableHandle)img.image;
    r.vk.format = (VKEnum)img.format;
    if (img.layout == 1) {
        r.state = {nri::AccessBits::SHADER_RESOURCE, nri::Layout::SHADER_RESOURCE, nri::StageBits::ALL};
    } else {
        r.state = {nri::AccessBits::SHADER_RESOURCE_STORAGE, nri::Layout::GENERAL, nri::StageBits::ALL};
    }
    return r;
}

VM_EXPORT void vmnrd_destroy() {
    for (nrd::Integration*& integration : g_nrd) {
        if (integration) {
            integration->Destroy();
            delete integration;
            integration = nullptr;
        }
    }
}

// Returns 0 on success, 1 + nrd::Result otherwise.
VM_EXPORT int vmnrd_create(const VmNrdCreate* d) {
    vmnrd_destroy();

    nri::QueueFamilyVKDesc family = {};
    family.queueNum = 1;
    family.queueType = nri::QueueType::GRAPHICS;
    family.familyIndex = d->queueFamily;

    nri::DeviceCreationVKDesc device = {};
    device.vkExtensions.instanceExtensions = (const char* const*)d->instanceExtensions;
    device.vkExtensions.instanceExtensionNum = d->instanceExtensionNum;
    device.vkExtensions.deviceExtensions = (const char* const*)d->deviceExtensions;
    device.vkExtensions.deviceExtensionNum = d->deviceExtensionNum;
    device.vkInstance = (VKHandle)d->vkInstance;
    device.vkPhysicalDevice = (VKHandle)d->vkPhysicalDevice;
    device.vkDevice = (VKHandle)d->vkDevice;
    device.queueFamilies = &family;
    device.queueFamilyNum = 1;
    device.minorVersion = (uint8_t)d->minorVersion;

    const nrd::DenoiserDesc denoisers[] = {{DENOISER, nrd::Denoiser::RELAX_DIFFUSE}};
    nrd::InstanceCreationDesc instance = {};
    instance.denoisers = denoisers;
    instance.denoisersNum = 1;

    nrd::IntegrationCreationDesc desc = {};
    std::strncpy(desc.name, "VulkanMod", sizeof(desc.name) - 1);
    desc.resourceWidth = g_width = (uint16_t)d->width;
    desc.resourceHeight = g_height = (uint16_t)d->height;
    desc.queuedFrameNum = (uint8_t)d->queuedFrames;
    desc.autoWaitForIdle = true;

    for (nrd::Integration*& integration : g_nrd) {
        integration = new nrd::Integration();
        nrd::Result r = integration->RecreateVK(desc, instance, device);
        if (r != nrd::Result::SUCCESS) {
            vmnrd_destroy();
            return 1 + (int)r;
        }
    }
    return 0;
}

static void denoise(nrd::Integration* integration, const VmNrdFrame* f, const VmNrdImage& in,
                    const VmNrdImage& out, const nri::CommandBufferVKDesc& cmd) {
    nrd::ResourceSnapshot snapshot;
    // The renderer keeps its own layouts: everything goes back to how it came in.
    snapshot.restoreInitialState = true;
    snapshot.SetResource(nrd::ResourceType::IN_MV, resource(f->mv));
    snapshot.SetResource(nrd::ResourceType::IN_NORMAL_ROUGHNESS, resource(f->normalRoughness));
    snapshot.SetResource(nrd::ResourceType::IN_VIEWZ, resource(f->viewZ));
    snapshot.SetResource(nrd::ResourceType::IN_DIFF_RADIANCE_HITDIST, resource(in));
    snapshot.SetResource(nrd::ResourceType::OUT_DIFF_RADIANCE_HITDIST, resource(out));
    integration->DenoiseVK(&DENOISER, 1, cmd, snapshot);
}

// Records both denoisers into the frame's command buffer. Returns 0 on success.
VM_EXPORT int vmnrd_denoise(const VmNrdFrame* f) {
    if (!g_nrd[0] || !g_nrd[1]) return 1;

    nrd::CommonSettings common = {};
    std::memcpy(common.viewToClipMatrix, f->viewToClip, sizeof(common.viewToClipMatrix));
    std::memcpy(common.viewToClipMatrixPrev, f->viewToClipPrev, sizeof(common.viewToClipMatrixPrev));
    std::memcpy(common.worldToViewMatrix, f->worldToView, sizeof(common.worldToViewMatrix));
    std::memcpy(common.worldToViewMatrixPrev, f->worldToViewPrev, sizeof(common.worldToViewMatrixPrev));
    common.motionVectorScale[0] = f->mvScale[0];
    common.motionVectorScale[1] = f->mvScale[1];
    common.motionVectorScale[2] = 0.0f;
    common.cameraJitter[0] = f->jitter[0];
    common.cameraJitter[1] = f->jitter[1];
    common.cameraJitterPrev[0] = f->jitterPrev[0];
    common.cameraJitterPrev[1] = f->jitterPrev[1];
    common.resourceSize[0] = common.resourceSizePrev[0] = common.rectSize[0] = common.rectSizePrev[0] = g_width;
    common.resourceSize[1] = common.resourceSizePrev[1] = common.rectSize[1] = common.rectSizePrev[1] = g_height;
    common.frameIndex = f->frameIndex;
    common.accumulationMode = f->reset ? nrd::AccumulationMode::RESTART : nrd::AccumulationMode::CONTINUE;
    common.denoisingRange = f->denoisingRange;
    common.isMotionVectorInWorldSpace = false;
    nrd::RelaxSettings relax = {};
    for (nrd::Integration* integration : g_nrd) {
        integration->NewFrame();
        if (integration->SetCommonSettings(common) != nrd::Result::SUCCESS) return 2;
        integration->SetDenoiserSettings(DENOISER, &relax);
    }

    nri::CommandBufferVKDesc cmd = {};
    cmd.vkCommandBuffer = (VKHandle)f->commandBuffer;
    cmd.queueType = nri::QueueType::GRAPHICS;

    denoise(g_nrd[0], f, f->blockIn, f->blockOut, cmd);
    denoise(g_nrd[1], f, f->giIn, f->giOut, cmd);
    return 0;
}
