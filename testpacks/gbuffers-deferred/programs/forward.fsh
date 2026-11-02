layout(location = 0) in vec4 color;
layout(location = 1) in vec2 uv;
layout(location = 2) in vec2 light;
void main() {
    vec4 c = color * texture(gtexture, uv);
    float opaqueDepth = texture(depthtex1, gl_FragCoord.xy / vec2(viewWidth, viewHeight)).r;
    // Thickness of the water in front of the opaque scene: deeper is greener.
    float thick = clamp(vkm_linearDepth(opaqueDepth) - vkm_linearDepth(gl_FragCoord.z), 0.0, 8.0) / 8.0;
    lit = vec4(mix(c.rgb, vec3(0.0, 0.5, 0.3), thick), mix(c.a, 1.0, thick));
}
