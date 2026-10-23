package net.vulkanmod.vulkan.shader;

import net.vulkanmod.vulkan.VRenderSystem;

import java.nio.ByteBuffer;
import java.nio.FloatBuffer;

public class Vec1f extends Field {

    protected Vec1f(String name, AlignedStruct struct) {
        super(name, 1, 1, struct.getCurrentOffset());

        struct.setCurrentOffset(offset + size);
        setFunction();
    }

    void setFunction() {
        switch (this.name) {
            case "FogStart" -> this.set = VRenderSystem::getShaderFogStart;
            case "FogEnd" -> this.set = VRenderSystem::getShaderFogEnd;
            case "FogDensity" -> this.set = VRenderSystem::getFogDensity;
            case "LineWidth" -> this.set = VRenderSystem::getShaderLineWidth;
            case "GameTime" -> this.set = VRenderSystem::getShaderGameTime;
        }
    }

    void update(FloatBuffer fb) {
        float f = (float) this.set.get();
        fb.position(offset);
        fb.put(f);
    }

    void update(ByteBuffer buffer) {
        float f = (float) this.set.get();
        buffer.position(offset * 4);
        buffer.putFloat(f);
    }
}
