layout(location = 0) out vec4 outColor;
void main() {
    vec3 c = texture(sceneColor, vkm_hostUV(vkm_texCoord)).rgb;
    float luma = dot(c, vec3(0.2126, 0.7152, 0.0722));
    outColor = vec4(mix(vec3(luma), c, 0.25), 1.0);
}
