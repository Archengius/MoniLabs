#version 330 core

in vec3 Position;
in vec3 BaseColor;
in vec4 ParticleSpeeds;
in uvec4 Colors;
in vec4 Anchor;

uniform mat4 ModelViewMat;
uniform mat4 ProjMat;
uniform mat3 IViewRotMat;

out vec3 vRelPos;
flat out vec4 vAnchor;
flat out uvec4 vColors;
flat out vec3 vBaseColor;
flat out vec4 vParticleSpeeds;

void main() {
    gl_Position = ProjMat * ModelViewMat * vec4(Position, 1.0);

    vRelPos = IViewRotMat * Position;
    vAnchor = vec4(IViewRotMat * Anchor.xyz, Anchor.w);
    vBaseColor = BaseColor;
    vParticleSpeeds = ParticleSpeeds;
    vColors = Colors;
}
