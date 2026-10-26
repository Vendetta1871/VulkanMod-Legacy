package net.vulkanmod.vulkan.shader;

import net.vulkanmod.vulkan.VRenderSystem;

import java.nio.ByteBuffer;
import java.nio.FloatBuffer;

public class Vec1f extends Field {
    // A primitive supplier avoids the per-draw Float boxing that the shared
    // Supplier<Object> "set" field would force (L10). The JDK has no FloatSupplier,
    // so the value travels through a double - widening/narrowing a float is exact.
    private java.util.function.DoubleSupplier floatSet;

    protected Vec1f(String name, AlignedStruct struct) {
        super(name, 1, 1, struct.getCurrentOffset());

        struct.setCurrentOffset(offset + size);
        setFunction();
    }

    void setFunction() {
        switch (this.name) {
            case "FogStart" -> this.floatSet = VRenderSystem::getShaderFogStart;
            case "FogEnd" -> this.floatSet = VRenderSystem::getShaderFogEnd;
            case "FogDensity" -> this.floatSet = VRenderSystem::getFogDensity;
            case "LineWidth" -> this.floatSet = VRenderSystem::getShaderLineWidth;
            case "GameTime" -> this.floatSet = VRenderSystem::getShaderGameTime;
            case "AlphaFunc" -> this.floatSet = VRenderSystem::getAlphaFunc;
            case "AlphaRef" -> this.floatSet = VRenderSystem::getAlphaRef;
            case "RedOverlay" -> this.floatSet = net.vulkanmod.render.EntityRenderer::getRedOverlay;
        }
    }

    void update(FloatBuffer fb) {
        fb.position(offset);
        fb.put((float) floatSet.getAsDouble());
    }

    void update(ByteBuffer buffer) {
        buffer.position(offset * 4);
        buffer.putFloat((float) floatSet.getAsDouble());
    }
}
