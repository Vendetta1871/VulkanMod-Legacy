layout(location = 0) out vec3 pos;
layout(location = 1) out vec3 n;
layout(location = 2) out vec4 t;
layout(location = 3) out vec2 mid;
layout(location = 4) out vec2 uv;
layout(location = 5) flat out vec4 entity;
void main() {
    gl_Position = vkm_position();
    pos = vaPosition; n = vaNormal; t = at_tangent; mid = mc_midTexCoord; uv = vaUV0; entity = mc_Entity;
}
