#version 150 compatibility
uniform sampler2D colortex0;
in vec2 uv;
in vec3 mark;
/* RENDERTARGETS: 0 */
out vec4 color;
void main() {
    color = texture(colortex0, uv);
    if (uv.x < 0.1) color.rgb = mark;
}
