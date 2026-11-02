layout(std430, set = 2, binding = 256) readonly buffer Values { vec4 value; };
layout(location = 0) out vec4 outColor;
void main() {
    vec4 c = texture(sceneColor, vkm_texCoord);
    if (vkm_texCoord.x < 0.25) c = texture(voxTex, vec3(vkm_texCoord * 4.0, 0.5));
    else if (vkm_texCoord.x > 0.9) c = texture(rawTex, vkm_texCoord);
    else if (vkm_texCoord.x < 0.5 && vkm_texCoord.y < 0.5) c = value;
    else if (vkm_texCoord.x < 0.5) c = vec4(float(texture(lineTex, vkm_texCoord.y).r) / 255.0, 0.0, 0.0, 1.0);
    outColor = c;
}
