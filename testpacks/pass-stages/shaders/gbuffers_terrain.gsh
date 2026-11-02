#version 150 compatibility
layout(triangles) in;
layout(triangle_strip, max_vertices = 3) out;
in Tint { vec3 color; float bright; vec2 uv; } vs[];
out Passed { vec3 color; vec2 uv; } gs;
void main() {
    for (int i = 0; i < 3; i++) {
        gl_Position = gl_in[i].gl_Position;
        gs.color = vs[i].color * vs[i].bright;
        gs.uv = vs[i].uv;
        EmitVertex();
    }
    EndPrimitive();
}
