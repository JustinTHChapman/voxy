#version 430

layout(location=0) uniform sampler2D tex;
in flat uint metadata;
in vec2 texCoord;
out vec4 colour;

void main() {
    colour = texture(tex, texCoord);
    if (colour.a < 0.001f && ((metadata&1u)!=0)) {
        discard;
    }
}
