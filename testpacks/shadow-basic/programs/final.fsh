layout(location = 0) out vec4 outColor;
void main() { outColor = vec4(texture(lit, vkm_texCoord).rgb, 1.0); }
