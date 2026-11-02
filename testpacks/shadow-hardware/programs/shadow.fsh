layout(location = 0) in vec2 uv;
void main() {
    vec4 c = texture(gtexture, uv);
    vkm_alphaTest(c.a);
    shadowcolor0 = vec4(c.rgb, 1.0);
}
