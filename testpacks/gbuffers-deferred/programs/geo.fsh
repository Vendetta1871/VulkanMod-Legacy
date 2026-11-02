layout(location = 0) in vec4 color;
layout(location = 1) in vec2 uv;
layout(location = 2) in vec3 viewPos;
layout(location = 3) in vec2 light;
void main() {
    vec4 c = color * texture(gtexture, uv);
    vkm_alphaTest(c.a);
#ifdef VKM_LIGHTMAP
    c.rgb *= texture(lightmap, light).rgb;
#endif
    albedo = vec4(c.rgb, 1.0);
    normal = vec4(normalize(cross(dFdx(viewPos), dFdy(viewPos))), 1.0);
}
