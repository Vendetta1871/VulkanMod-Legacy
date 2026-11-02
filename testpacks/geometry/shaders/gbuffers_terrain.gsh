#version 330 compatibility
#ifndef MC_GEOMETRY_SHADER
#error no macro
#endif
layout(triangles) in;
layout(triangle_strip, max_vertices = 3) out;
in vec2 uvV[];
in vec4 tintV[];
flat in int idV[];
out vec2 uv;
out vec3 bary;
flat out int id;
uniform sampler2D tex;
uniform mat4 gbufferProjection;
void main() {
    vec4 c = gl_ModelViewProjectionMatrix * vec4(0.0);
    vec4 s = textureLod(tex, uvV[0], 0.0);
    for (int i = 0; i < 3; i++) {
        gl_Position = gl_in[i].gl_Position;
        uv = uvV[i];
        bary = vec3(i == 0, i == 1, i == 2);
        id = idV[i];
        gl_FrontColor = tintV[i] + c * 0.0 + s * 0.0;
        EmitVertex();
    }
    EndPrimitive();
}
