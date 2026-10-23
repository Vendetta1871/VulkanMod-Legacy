package net.vulkanmod.vulkan.shader;

import net.vulkanmod.vulkan.VRenderSystem;

import java.nio.ByteBuffer;
import java.nio.FloatBuffer;

public class Vec4f extends Field {

    protected Vec4f(String name, AlignedStruct struct) {
        super(name, 4, 4, struct.getCurrentOffset());

        struct.setCurrentOffset(offset + size);
        setFunction();
    }

    void setFunction() {
        switch (this.name) {
            case "ColorModulator" -> this.set = VRenderSystem::getShaderColor;
            case "FogColor" -> this.set = VRenderSystem::getShaderFogColor;
            case "GameTime" -> this.set = VRenderSystem::getShaderGameTime;
        }
    }

    void update(FloatBuffer fb) {
        float[] arr = (float[]) this.set.get();
        fb.position(offset);
        fb.put(arr);
    }

    void update(ByteBuffer buffer) {
        float[] arr = (float[]) this.set.get();
        buffer.position(offset * 4);
        for (float f : arr) {
            buffer.putFloat(f);
        }
    }
}
