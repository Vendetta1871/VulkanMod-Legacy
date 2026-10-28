package net.vulkanmod.vulkan.shader;

import java.nio.ByteBuffer;
import java.nio.FloatBuffer;

/** A fixed-size vec4 array UBO field ("vec4array" with count = number of vec4s). */
public class Vec4Array extends Field {

    protected Vec4Array(String name, int count, AlignedStruct struct) {
        // getCurrentOffset(): UBO tracks its offset itself (like Vec4f).
        super(name, count * 4, 4, struct.getCurrentOffset());
        struct.setCurrentOffset(offset + size);
        setFunction();
    }

    void setFunction() {
        if (this.name.equals("DynLights")) {
            this.set = net.vulkanmod.render.DynamicLights::viewSpaceArray;
        } else if (this.name.equals("DynLightsAll")) {
            this.set = net.vulkanmod.render.DynamicLights::viewSpaceArrayAll;
        }
    }

    void update(FloatBuffer fb) {
        float[] arr = (float[]) this.set.get();
        fb.position(offset);
        fb.put(arr, 0, Math.min(arr.length, size));
    }

    void update(ByteBuffer buffer) {
        float[] arr = (float[]) this.set.get();
        buffer.position(offset * 4);
        for (int i = 0; i < Math.min(arr.length, size); i++) buffer.putFloat(arr[i]);
    }
}
