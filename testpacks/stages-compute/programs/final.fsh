layout(location = 0) out vec4 outColor;
void main() {
    vec4 c = texture(sceneColor, vkm_texCoord);
    outColor = vkm_texCoord.x < 0.25 ? vec4(texture(pulse, vec2(0.5)).rgb, 1.0) : c;
}
