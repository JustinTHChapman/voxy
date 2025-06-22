#version 450 core

layout(binding = 0) uniform sampler2D colourTex;
layout(binding = 1) uniform sampler2D depthTex;
layout(location = 2) uniform mat4 invProjMat;
layout(location = 3) uniform mat4 projMat;
#ifdef USE_ENV_FOG
layout(location = 4) uniform vec3 endParams;
layout(location = 5) uniform vec3 fogColour;
#endif

out vec4 colour;
in vec2 UV;

vec3 rev3d(vec3 clip) {
    vec4 view = invProjMat * vec4(clip*2.0f-1.0f,1.0f);
    return view.xyz/view.w;
}
float projDepth(vec3 pos) {
    vec4 view = projMat * vec4(pos, 1);
    return view.z/view.w;
}

void main() {
    colour = texture(colourTex, UV.xy);
    if (colour.a == 0.0) {
        discard;
    }

    float depth = texture(depthTex, UV.xy).r;
    if (depth == 0.0f) {
        discard;
    }

    vec3 point = rev3d(vec3(UV.xy, depth));

    #ifdef USE_ENV_FOG
    {
        float fogLerp = max(fma(min(length(point.xyz), endParams.x),endParams.y,endParams.z),0);//512 is 32*16 which is the render distance in blocks
        colour.rgb = mix(colour.rgb, fogColour, fogLerp);
    }
    #endif

    depth = projDepth(point);
    depth = min(1.0f-(2.0f/((1<<24)-1)), depth);
    depth = depth * 0.5f + 0.5f;
    depth = gl_DepthRange.diff * depth + gl_DepthRange.near;
    gl_FragDepth = depth;
}