layout(location = 0) out vec4 color;
layout(location = 1) out vec2 uv;
void main() {
    gl_Position = vkm_position();
    color = vaColor;
    uv = vaUV0;
}
