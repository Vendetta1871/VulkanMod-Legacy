#version 150 compatibility
uniform sampler2D colortex0;
layout(rgba8) uniform writeonly image2D colorimg3;
layout(rgba8) uniform writeonly image2D colorimg6;
layout(rgba8) uniform writeonly image2D shadowcolorimg0;
in vec2 uv;
/* RENDERTARGETS: 0,6 */
layout(location = 0) out vec4 color;
layout(location = 1) out vec4 six;
void main() {
    color = texture(colortex0, uv);
    // Attached and stored at once: the attachment's value wins here, the pass only has to be legal.
    six = vec4(0.0, 0.0, 0.0, 1.0);
    ivec2 p = ivec2(gl_FragCoord.xy);
    imageStore(colorimg3, p, vec4(1.0, 0.0, 0.0, 1.0));
    imageStore(colorimg6, p, vec4(1.0, 1.0, 1.0, 1.0));
    // Past every edge: GL drops these.
    imageStore(colorimg3, p + ivec2(100000), vec4(0.0, 1.0, 0.0, 1.0));
    imageStore(colorimg3, -p - 1, vec4(0.0, 1.0, 0.0, 1.0));
    imageStore(shadowcolorimg0, ivec2(uv * 64.0), vec4(1.0, 0.0, 1.0, 1.0));
}
