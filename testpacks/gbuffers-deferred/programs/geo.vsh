layout(location = 0) out vec4 color;
layout(location = 1) out vec2 uv;
layout(location = 2) out vec3 viewPos;
layout(location = 3) out vec2 light;
void main() {
    gl_Position = vkm_position();
    color = vaColor;
    uv = vaUV0;
    viewPos = (vkm_modelView * vec4(vaPosition, 1.0)).xyz;
    light = vec2(vaUV2) / 256.0 + 0.03125;
}
