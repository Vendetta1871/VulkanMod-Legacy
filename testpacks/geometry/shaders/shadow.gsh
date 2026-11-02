#version 330 compatibility
layout(triangles) in;
layout(triangle_strip, max_vertices = 3) out;
in vec2 uvV[];
out vec2 uv;
void main() { for (int i = 0; i < 3; i++) { gl_Position = gl_in[i].gl_Position; uv = uvV[i]; EmitVertex(); } EndPrimitive(); }
