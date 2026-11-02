layout(std430, set = 2, binding = 256) buffer Values { vec4 value; };
void main() {
    imageStore(vox, ivec3(gl_GlobalInvocationID), vec4(vec3(gl_GlobalInvocationID) / 3.0, 1.0));
    imageStore(line, int(gl_LocalInvocationIndex % 8u), uvec4(255u));
    value = vec4(0.0, 0.5, 1.0, 1.0);
}
