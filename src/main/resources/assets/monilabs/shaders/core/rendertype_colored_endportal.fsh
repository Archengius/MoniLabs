#version 330 core

uniform sampler2D Sampler0;
uniform sampler2D Sampler1;

uniform float GameTime;
uniform int EndPortalLayers;

in vec3 vRelPos;
flat in vec4 vAnchor;
flat in vec3 vBaseColor;
flat in vec4 vParticleSpeeds;
flat in uvec4 vColors;

const int NEAR_LAYERS_START = 11;

mat2 mat2_rotate_z(float radians) {
    return mat2(
    cos(radians), -sin(radians),
    sin(radians), cos(radians)
    );
}

mat3 rotate_axis(vec3 axis, float angle) {
    float s = sin(angle);
    float c = cos(angle);
    float t = 1.0 - c;
    return mat3(
    t * axis.x * axis.x + c,          t * axis.x * axis.y + s * axis.z, t * axis.x * axis.z - s * axis.y,
    t * axis.x * axis.y - s * axis.z, t * axis.y * axis.y + c,          t * axis.y * axis.z + s * axis.x,
    t * axis.x * axis.z + s * axis.y, t * axis.y * axis.z - s * axis.x, t * axis.z * axis.z + c
    );
}

vec3 hash_dir(float n) {
    vec3 v = fract(sin(vec3(n * 12.9898, n * 78.233, n * 37.719)) * 43758.5453) * 2.0 - 1.0;
    return normalize(v + vec3(1e-4));
}

vec2 cube_uv(vec3 dir) {
    vec3 a = abs(dir);
    if (a.x >= a.y && a.x >= a.z) {
        return dir.zy / a.x + vec2(dir.x > 0.0 ? 0.0 : 0.37, 0.0);
    } else if (a.y >= a.z) {
        return dir.xz / a.y + vec2(dir.y > 0.0 ? 0.61 : 0.13, 0.5);
    }
    return dir.xy / a.z + vec2(dir.z > 0.0 ? 0.29 : 0.83, 0.25);
}

vec3 rgb2hsv(vec3 c) {
    vec4 K = vec4(0.0, -1.0 / 3.0, 2.0 / 3.0, -1.0);
    vec4 p = mix(vec4(c.bg, K.wz), vec4(c.gb, K.xy), step(c.b, c.g));
    vec4 q = mix(vec4(p.xyw, c.r), vec4(c.r, p.yzx), step(p.x, c.r));

    float d = q.x - min(q.w, q.y);
    float e = 1.0e-10;
    return vec3(abs(q.z + (q.w - q.y) / (6.0 * d + e)), d / (q.x + e), q.x);
}
vec3 hsv2rgb(vec3 c) {
    vec4 K = vec4(1.0, 2.0 / 3.0, 1.0 / 3.0, 3.0);
    vec3 p = abs(fract(c.xxx + K.xyz) * 6.0 - K.www);
    return c.z * mix(K.xxx, clamp(p - K.xxx, 0.0, 1.0), c.y);
}

vec3 end_portal_layer(vec3 dir, float layer, float speedModifier) {
    mat3 orientation = rotate_axis(hash_dir(layer), layer * layer * 4321.0 + layer * 9.0);
    float drift = GameTime * 1200.0 * (0.005 + layer * 0.002) * speedModifier;
    mat3 rotation = rotate_axis(hash_dir(layer + 31.0), drift) * orientation;

    vec2 uv = cube_uv(rotation * dir) * 0.07;
    uv = mat2_rotate_z(radians(layer * 37.0)) * uv * ((4.5 - layer / 4.0) * 2.0) + vec2(17.0 / layer, 0.0);
    return texture(Sampler1, uv).rgb;
}

vec3 sphere_exit_dir(vec3 origin, vec3 dir, float radius) {
    float b = dot(origin, dir);
    float c = dot(origin, origin) - radius * radius;
    float t = -b + sqrt(max(b * b - c, 0.0));
    return normalize(origin + dir * t);
}

void unpackPalette(uvec4 packedColors, out vec4[4] palette) {
    for (int i = 0; i < 4; i++) {
        uint c = packedColors[i];
        palette[i] = vec4(
            float((c >> 16) & 0xFFu) / 255.0, //Red
            float((c >> 8) & 0xFFu) / 255.0,  //Green
            float((c >> 0) & 0xFFu) / 255.0,  //Blue
            1.0 //Alpha
        );
    }
}

void expandPalette(vec4[4] base4, out vec4[16] palette) {
    for (int i = 0; i < 4; i++) {
        vec3 rgb = base4[i].rgb;
        vec3 hsv = rgb2hsv(rgb);

        int baseIdx = i * 4;

        //Original
        palette[baseIdx] = base4[i];

        //Brighter
        palette[baseIdx + 1] = vec4(hsv2rgb(vec3(
        hsv.x,
        clamp(hsv.y - 0.05, 0.0, 1.0),
        clamp(hsv.z + 0.15, 0.0, 1.0)
        )), 1.0);

        //Darker
        palette[baseIdx + 2] = vec4(hsv2rgb(vec3(
        hsv.x,
        clamp(hsv.y + 0.1, 0.0, 1.0),
        clamp(hsv.z - 0.2, 0.0, 1.0)
        )), 1.0);

        //Hue shifted
        palette[baseIdx + 3] = vec4(hsv2rgb(vec3(
        fract(hsv.x + 0.08),
        hsv.y,
        hsv.z
        )), 1.0);
    }
}

out vec4 fragColor;

void main() {
    float speeds[4] = float[](vParticleSpeeds.x, vParticleSpeeds.y, vParticleSpeeds.z, vParticleSpeeds.w);
    vec4 palette[4];
    vec4 expandedPalette[16];
    unpackPalette(vColors, palette);
    expandPalette(palette, expandedPalette);

    vec3 dir = normalize(vRelPos);
    vec3 anchorOffset = vRelPos - vAnchor.xyz;

    vec3 color = texture(Sampler0, cube_uv(dir) * 0.07 + 0.5).rgb * vBaseColor.rgb;
    for (int i = 0; i < EndPortalLayers; i++) {
        vec3 layerDir = dir;
        if (i >= NEAR_LAYERS_START) {
            float shellScale = 1.5 + float(EndPortalLayers - 1 - i) * 0.5;
            layerDir = sphere_exit_dir(anchorOffset, dir, vAnchor.w * shellScale);
        }
        vec3 textureColor = end_portal_layer(layerDir, float(i + 1), speeds[i / 4]);
        bool isStar = length(textureColor) > 0.1;
        bool isBlackish = length(expandedPalette[i].rgb) < 0.1;
        if (isBlackish && isStar)
            color = expandedPalette[i].rgb;
        else
            color += textureColor * expandedPalette[i].rgb;
    }
    fragColor = vec4(color, 1.0);
}
