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
    ivec3 origin = ivec3(chunkPos[gl_InstanceID], 0).xzy;
    origin -= section.xyz;

    ivec3 cubeCornerI = ivec3(gl_VertexID&1, (gl_VertexID>>2)&1, (gl_VertexID>>1)&1);
    //Expand the y height to be big (will be +- 8192)
    //TODO: make it W.R.T world height and offsets
    cubeCornerI.y = cubeCornerI.y*32-16;
    gl_Position = MVP * vec4(vec3(cubeCornerI+origin)*16, 1);
    gl_Position.z -= 0.0001f;
}