#version 430
layout(local_size_x = 16, local_size_y = 16) in;
const ivec3 workGroups = ivec3(128, 128, 1);
layout(rgba8) uniform image2D shadowcolorimg3;
void main() { imageStore(shadowcolorimg3, ivec2(gl_GlobalInvocationID.xy), vec4(0.0, 1.0, 0.0, 1.0)); }
