package net.vulkanmod.vulkan.shader;

import net.vulkanmod.vulkan.VRenderSystem;
import org.joml.Vector2f;

import java.nio.ByteBuffer;
import java.nio.FloatBuffer;

public class Vec2f extends Field {

    protected Vec2f(String name, AlignedStruct ubo) {
        // Units are floats: std140 vec2 = base alignment 8 bytes = 2 floats (R14).
        super(name, 2, 2, ubo.getCurrentOffset());

        ubo.setCurrentOffset(offset + size);
        setFunction();
    }

    void setFunction() {
        if (this.name.equals("ScreenSize")) this.set = VRenderSystem::getScreenSize;
        else if (this.name.equals("UvOffset")) this.set = VRenderSystem::getCloudUvOffset;
    }

    void update(FloatBuffer fb) {
        Vector2f vec2 = (Vector2f) this.set.get();
        float[] floats = new float[2];

        floats[0] = vec2.x();
        floats[1] = vec2.y();

        fb.position(offset);

        for(float f : floats) {
            fb.put(f);
        }
    }

    void update(ByteBuffer buffer) {
        //update(buffer.asFloatBuffer());

        Vector2f vec2 = (Vector2f) this.set.get();
        float[] floats = new float[2];

        floats[0] = vec2.x();
        floats[1] = vec2.y();

        buffer.position(offset * 4);

        for(float f : floats) {
            buffer.putFloat(f);
        }
    }
}
