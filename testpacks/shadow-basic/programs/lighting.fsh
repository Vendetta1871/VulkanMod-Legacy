layout(location = 0) out vec4 outColor;
void main() {
    float d = texture(sceneDepth, vkm_texCoord).r;
    if (d >= 1.0 || texture(albedo, vkm_texCoord).a == 0.0) {
        outColor = texture(sceneColor, vkm_texCoord);
        return;
    }
    vec3 view = vkm_screenToView(vkm_texCoord, d);
    vec4 world = gbufferModelViewInverse * vec4(view, 1.0);
    vec4 clip = shadowProjection * shadowModelView * world;
    vec3 s = clip.xyz / clip.w * 0.5 + 0.5;
    if (!all(greaterThan(s.xy, vec2(0.0))) || !all(lessThan(s.xy, vec2(1.0)))) {
        outColor = vec4(1.0, 0.0, 1.0, 1.0);   // outside the map: visible on purpose
        return;
    }
    float lit = s.z - 0.0008 <= texture(shadowtex0, s.xy).r ? 1.0 : 0.25;
    outColor = vec4(texture(albedo, vkm_texCoord).rgb * lit, 1.0);
}
