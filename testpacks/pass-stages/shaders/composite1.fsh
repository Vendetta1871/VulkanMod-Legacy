#version 150 compatibility
uniform sampler2D colortex0;
in Carried { vec2 uv; vec3 mark; } te;
/* RENDERTARGETS: 0 */
out vec4 color;
void main() {
    color = texture(colortex0, te.uv);
    if (te.uv.x >= 0.1 && te.uv.x < 0.2) color.rgb = te.mark;
}
