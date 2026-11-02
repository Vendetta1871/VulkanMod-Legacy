layout(location = 0) out vec4 fragColor;
void main() { fragColor = vec4(texture(sceneColor, vkm_texCoord).rgb, 1.0); }
