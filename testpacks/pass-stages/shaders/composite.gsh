#version 150 compatibility
layout(triangles) in;
layout(triangle_strip, max_vertices = 3) out;
in vec2 uvV[];
out vec2 uv;
out vec3 mark;
void main() {
    for (int i = 0; i < 3; i++) {
        gl_Position = gl_in[i].gl_Position;
        uv = uvV[i];
        mark = vec3(0.0, 1.0, 0.0);
        EmitVertex();
    }
    EndPrimitive();
}
