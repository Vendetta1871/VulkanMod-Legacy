#version 460
#extension GL_EXT_ray_tracing : require

layout(location = 1) rayPayloadInEXT vec4 shadow;

void main() {
    shadow.a = 1.0;
}
