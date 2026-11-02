layout(location = 0) out vec4 outColor;
void main() { outColor = texture(seed, vkm_texCoord); }
