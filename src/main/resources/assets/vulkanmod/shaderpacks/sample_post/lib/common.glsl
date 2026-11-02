// Shared helpers of the sample pack. Included with #include <lib/common.glsl>.
float luma(vec3 c) {
    return dot(c, vec3(0.2126, 0.7152, 0.0722));
}
