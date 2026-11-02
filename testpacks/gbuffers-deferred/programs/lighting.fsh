layout(location = 0) out vec4 outColor;
void main() {
    float d = texture(sceneDepth, vkm_texCoord).r;
    if (d >= 1.0 || texture(albedo, vkm_texCoord).a == 0.0) {
        outColor = texture(sceneColor, vkm_texCoord);
        return;
    }
    vec3 n = texture(normal, vkm_texCoord).xyz;
    vec3 l = normalize(mat3(gbufferModelView) * vec3(0.5, 0.8, 0.3));
    outColor = vec4(texture(albedo, vkm_texCoord).rgb * (0.35 + 0.65 * max(dot(n, l), 0.0)), 1.0);
}
