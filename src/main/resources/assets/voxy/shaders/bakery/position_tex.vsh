#version 430

layout(location=0) in vec3 pos;
layout(location=1) in uint _metadata;
layout(location=2) in vec2 uv;

layout(location=1) uniform mat4 transform;
out vec2 texCoord;
out flat uint metadata;

void main() {
    metadata = _metadata;

    gl_Position = transform * vec4(pos, 1.0);
    texCoord = uv;
}
