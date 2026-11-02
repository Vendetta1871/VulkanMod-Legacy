layout(location = 0) out vec4 outColor;
void main() {
    vec2 uv = vkm_texCoord * 4.0;
    if (uv.x < 1.0 && uv.y < 1.0) outColor = vec4(texture(shadowView, uv).rgb, 1.0);
    else outColor = vec4(texture(lit, vkm_texCoord).rgb, 1.0);
}
