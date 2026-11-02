#version 150 compatibility
out Tint { vec3 color; float bright; vec2 uv; } vs;
void main() {
    gl_Position = ftransform();
    vs.color = gl_Color.rgb;
    vs.bright = 1.0;
    vs.uv = gl_MultiTexCoord0.xy;
}
