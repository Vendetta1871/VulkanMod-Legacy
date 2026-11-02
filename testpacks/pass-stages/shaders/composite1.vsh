#version 150 compatibility
out Carry { vec2 uv; vec3 mark; } vs;
out float weight;
void main() {
    gl_Position = ftransform();
    vs.uv = gl_MultiTexCoord0.xy;
    vs.mark = vec3(0.0, 0.0, 1.0);
    weight = 1.0;
}
