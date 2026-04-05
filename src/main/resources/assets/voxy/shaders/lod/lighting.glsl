#ifndef _VOXY_LIGHTING_DECL
#define _VOXY_LIGHTING_DECL

vec2 getLightmapUv(uint index) {
    return clamp((vec2((index>>4)&0xFu, index&0xFu)/15)+vec2(8.0f/256), vec2(8.0f/256), vec2(248.0f/256));
}

#ifdef LIGHTING_SAMPLER_BINDING

layout(binding = LIGHTING_SAMPLER_BINDING) uniform sampler2D lightSampler;

vec4 getLighting(uint index) {
    return texture(lightSampler, getLightmapUv(index));
}
#endif

#endif