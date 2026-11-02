layout(location = 0) in vec4 color;
layout(location = 1) in vec2 uv;
void main() {
    vec4 c = texture(gtexture, uv);
    vkm_alphaTest(c.a);
    albedo = vec4(c.rgb * color.rgb, 1.0);
}
