#version 450

#ifdef USE_REVERSE_Z
#define REDUCTION min
#define NEAR 1.0f
#else
#define REDUCTION max
#define NEAR 0.0f
#endif

layout(location = 0) in vec2 uv;
layout(binding = 0) uniform sampler2D depthTex;
#ifdef OUTPUT_COLOUR
layout(location=0) out vec4 colour;
#endif
void main() {
    vec4 depths = textureGather(depthTex, uv, 0); // Get depth values from all surrounding texels.

    bvec4 cv = equal(vec4(1.0f-NEAR), depths);
    if (any(cv)) {//Patch holes (its very dodgy but should work :tm:, should clamp it to the first 3 levels)
        depths = mix(vec4(NEAR), depths, cv);
    }

    float res = REDUCTION(REDUCTION(depths.x, depths.y), REDUCTION(depths.z, depths.w));

    #ifdef OUTPUT_COLOUR
    colour = vec4(res);
    #else
    gl_FragDepth = res; // Write conservative depth.
    #endif
}
