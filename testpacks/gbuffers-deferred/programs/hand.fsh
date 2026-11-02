layout(location = 0) in vec4 color;
layout(location = 1) in vec2 uv;
layout(location = 2) in vec2 light;
void main() {
    vec4 c = color * texture(gtexture, uv);
    vkm_alphaTest(c.a);
    lit = vec4(c.rgb * vec3(1.0, 0.6, 0.6), c.a);
}
