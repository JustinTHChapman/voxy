#version 450

layout(location = 0) out vec2 uv;
layout(binding = 0, std140) uniform SceneUniform {
    mat4 MVP;
    ivec4 section;
};

layout(binding = 1, std430) restrict readonly buffer ChunkPosBuffer {
    ivec2[] chunkPos;
};

void main() {
    ivec3 cubeCornerI = ivec3(gl_VertexID&1, (gl_VertexID>>2)&1, (gl_VertexID>>1)&1);
    cubeCornerI.xz += chunkPos[gl_InstanceID];
    //Expand the y height to be big (will be +- 8192)
    //TODO: make it W.R.T world height and offsets
    cubeCornerI.y = cubeCornerI.y*1024-512;
    cubeCornerI -= section.xyz;
    gl_Position = MVP * vec4(vec3(cubeCornerI*16), 1);
}