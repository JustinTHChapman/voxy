#version 430

layout(location=0) uniform sampler2D tex;
in vec2 texCoord;
in flat uint metadata;
layout(location=0) out vec4 colour;
layout(location=1) out uvec4 metaOut;

void main() {
    colour = texture(tex, texCoord, ((~metadata>>1)&1u)*-16.0f);
    if (colour.a < 0.001f && ((metadata&1u)!=0)) {
        discard;
    }
    metaOut = uvec4((metadata>>2)&1u);//Write if it is or isnt tinted
}
