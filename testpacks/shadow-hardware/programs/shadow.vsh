layout(location = 0) out vec2 uv;
void main() {
    gl_Position = vkm_position();
    uv = vaUV0;
}
