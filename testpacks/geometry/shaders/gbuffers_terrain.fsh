#version 150 compatibility
in vec2 uv;
in vec3 bary;
flat in int id;
uniform sampler2D tex;
/* DRAWBUFFERS:0 */
void main() { vec4 c = texture2D(tex, uv) * gl_Color; gl_FragData[0] = vec4(mix(vec3(1.0, 0.0, 1.0), c.rgb, step(0.03, min(bary.x, min(bary.y, bary.z)))), c.a + float(id) * 0.0); }
