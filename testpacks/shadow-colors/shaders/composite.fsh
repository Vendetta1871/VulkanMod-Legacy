#version 150 compatibility
uniform sampler2D colortex0;
uniform sampler2D shadowcolor0;
uniform sampler2D shadowcolor1;
uniform sampler2D shadowcolor2;
uniform sampler2D shadowcolor3;
uniform sampler2D shadowcolor5;
uniform sampler2D shadowtex0;
in vec2 uv;
/* RENDERTARGETS: 0 */
void main() {
    vec2 q = uv * 8.0;
    vec4 c = texture2D(colortex0, uv);
    if (q.y < 1.0) {
        if (q.x < 1.0) c = texture2D(shadowcolor0, vec2(0.5));
        else if (q.x < 2.0) c = texture2D(shadowcolor1, vec2(0.5));
        else if (q.x < 3.0) c = texture2D(shadowcolor2, vec2(0.5));
        else if (q.x < 4.0) c = texture2D(shadowcolor3, vec2(0.5));
        else if (q.x < 5.0) c = texture2D(shadowcolor5, vec2(0.5));
    }
    gl_FragData[0] = c;
}
