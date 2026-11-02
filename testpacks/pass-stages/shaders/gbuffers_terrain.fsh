#version 150 compatibility
#extension GL_ARB_shader_image_load_store : enable
uniform sampler2D texture;
layout(rgba8) uniform writeonly image2D colorimg4;
layout(rgba8) uniform writeonly image2D colorimg5;
in Passed { vec3 color; vec2 uv; } gs;
/* RENDERTARGETS: 0,5 */
layout(location = 0) out vec4 color;
layout(location = 1) out vec4 other;
void main() {
    color = texture2D(texture, gs.uv) * vec4(gs.color, 1.0);
    other = vec4(0.0, 0.0, 0.0, 1.0);
    imageStore(colorimg4, ivec2(gl_FragCoord.xy), vec4(1.0, 1.0, 0.0, 1.0));
    imageStore(colorimg5, ivec2(gl_FragCoord.xy), vec4(0.0, 1.0, 1.0, 1.0));
}
