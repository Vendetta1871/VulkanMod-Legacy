layout(location = 0) out vec4 outColor;
void main() { outColor = vec4(vec3(pow(texture(shadowtex0, vkm_texCoord).r, 8.0)), 1.0); }
