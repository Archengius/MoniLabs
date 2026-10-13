package net.neganote.monilabs.mixin.microverses;

import net.neganote.monilabs.MoniLabs;
import net.neganote.monilabs.client.render.ShaderAnalysisResult;

import com.mojang.blaze3d.shaders.Program;
import org.apache.commons.io.IOUtils;
import org.apache.logging.log4j.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Mixin(Program.class)
public class ProgramMixin {

    @Unique
    private static final Pattern MAIN_PATTERN = Pattern.compile("void\\s+main\\s*\\(\\s*\\)\\s*\\{([\\s\\S]*)}");
    @Unique
    private static final Pattern DECL_PATTERN = Pattern
            .compile("\\b((?:[iu]?vec\\d|float|int|bool))\\b\\s+([\\w\\d_]+)");
    @Unique
    private static final Pattern TARGET_PATTERN = Pattern
            .compile("layout\\s*\\(\\s*location\\s*=\\s*0\\s*\\)\\s*out\\s+vec4\\s+(\\w+)");
    @Unique
    private static final Pattern VAR_PATTERN = Pattern.compile("\\b([a-zA-Z_]\\w*)\\b");

    // Works for all popular shaders
    // Basically what this does is find the last write to the color variable before it gets actually written to the
    // framebuffer
    @Unique
    private static ShaderAnalysisResult moniLabs$analyzeShaderForColorWrite(String content) {
        Matcher mainMatcher = MAIN_PATTERN.matcher(content);
        if (!mainMatcher.find()) return new ShaderAnalysisResult("", "", false);

        String mainBody = mainMatcher.group(1);

        Matcher targetMatcher = TARGET_PATTERN.matcher(content);
        String targetOut = targetMatcher.find() ? targetMatcher.group(1) : "gl_FragColor";

        Map<String, String> knownTypes = new HashMap<>();
        String[] lines = mainBody.split("\n");

        for (String line : lines) {
            line = line.trim().replaceAll("//.*", "");
            if (line.isEmpty()) {
                continue;
            }

            Matcher declMatch = DECL_PATTERN.matcher(line);
            while (declMatch.find()) {
                knownTypes.put(declMatch.group(2), declMatch.group(1));
            }

            if (line.contains(targetOut) && line.contains("=")) {
                String rightSide = line.substring(line.indexOf('=') + 1);
                Matcher varMatch = VAR_PATTERN.matcher(rightSide);

                while (varMatch.find()) {
                    String candidate = varMatch.group(1);
                    if (Character.isDigit(candidate.charAt(0))) continue;

                    if (knownTypes.containsKey(candidate)) {
                        return new ShaderAnalysisResult(line, candidate, true);
                    }
                }
            }
        }
        return new ShaderAnalysisResult("", "", false);
    }

    @Unique
    private static String moniLabs$modifyIrisShaderpackShader(String code, ShaderAnalysisResult result,
                                                              Program.Type type) {
        if (type == Program.Type.VERTEX) {
            return code.replace("void main() {", """
                    in uvec4 Colors;
                    in vec4 ParticleSpeeds;
                    in vec4 Anchor;
                    flat out uvec4 vColors;
                    flat out vec4 vParticleSpeeds;
                    flat out vec4 vAnchor;

                    void main () {
                    vColors = Colors;
                    vParticleSpeeds = ParticleSpeeds;
                    vAnchor = #ANCHOR_TRANSFORM;""".replace("#ANCHOR_TRANSFORM",
                    code.contains("iris_ModelViewMat") ?
                            "vec4((iris_ModelViewMat * vec4(Anchor.xyz, 1.0)).xyz, Anchor.w)" : "Anchor"));
        }
        if (!result.valid()) {
            MoniLabs.LOGGER.log(Level.ERROR,
                    "Could not successfully analyze current shader for colored ender portal inject");
            return code;
        }
        String newSource = code.replace("void main",
                """

                        const int MONI_END_PORTAL_LAYERS = 15;
                        const int MONI_NEAR_LAYERS_START = 11;

                        mat2 mat2_rotate_z(float radians) {
                            return mat2(
                            cos(radians), -sin(radians),
                            sin(radians), cos(radians)
                            );
                        }

                        mat3 moni_rotate_axis(vec3 axis, float angle) {
                            float s = sin(angle);
                            float c = cos(angle);
                            float t = 1.0 - c;
                            return mat3(
                            t * axis.x * axis.x + c,          t * axis.x * axis.y + s * axis.z, t * axis.x * axis.z - s * axis.y,
                            t * axis.x * axis.y - s * axis.z, t * axis.y * axis.y + c,          t * axis.y * axis.z + s * axis.x,
                            t * axis.x * axis.z + s * axis.y, t * axis.y * axis.z - s * axis.x, t * axis.z * axis.z + c
                            );
                        }

                        vec3 moni_hash_dir(float n) {
                            vec3 v = fract(sin(vec3(n * 12.9898, n * 78.233, n * 37.719)) * 43758.5453) * 2.0 - 1.0;
                            return normalize(v + vec3(1e-4));
                        }

                        vec2 moni_cube_uv(vec3 dir) {
                            vec3 a = abs(dir);
                            if (a.x >= a.y && a.x >= a.z) {
                                return dir.zy / a.x + vec2(dir.x > 0.0 ? 0.0 : 0.37, 0.0);
                            } else if (a.y >= a.z) {
                                return dir.xz / a.y + vec2(dir.y > 0.0 ? 0.61 : 0.13, 0.5);
                            }
                            return dir.xy / a.z + vec2(dir.z > 0.0 ? 0.29 : 0.83, 0.25);
                        }

                        vec3 moni_sphere_exit_dir(vec3 origin, vec3 dir, float radius) {
                            float b = dot(origin, dir);
                            float c = dot(origin, origin) - radius * radius;
                            float t = -b + sqrt(max(b * b - c, 0.0));
                            return normalize(origin + dir * t);
                        }

                        vec3 moni_rgb2hsv(vec3 c) {
                            vec4 K = vec4(0.0, -1.0 / 3.0, 2.0 / 3.0, -1.0);
                            vec4 p = mix(vec4(c.bg, K.wz), vec4(c.gb, K.xy), step(c.b, c.g));
                            vec4 q = mix(vec4(p.xyw, c.r), vec4(c.r, p.yzx), step(p.x, c.r));

                            float d = q.x - min(q.w, q.y);
                            float e = 1.0e-10;
                            return vec3(abs(q.z + (q.w - q.y) / (6.0 * d + e)), d / (q.x + e), q.x);
                        }
                        vec3 moni_hsv2rgb(vec3 c) {
                            vec4 K = vec4(1.0, 2.0 / 3.0, 1.0 / 3.0, 3.0);
                            vec3 p = abs(fract(c.xxx + K.xyz) * 6.0 - K.www);
                            return c.z * mix(K.xxx, clamp(p - K.xxx, 0.0, 1.0), c.y);
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
                                vec3 hsv = moni_rgb2hsv(rgb);

                                int baseIdx = i * 4;

                                //Original
                                palette[baseIdx] = base4[i];

                                //Brighter
                                palette[baseIdx + 1] = vec4(moni_hsv2rgb(vec3(
                                hsv.x,
                                clamp(hsv.y - 0.05, 0.0, 1.0),
                                clamp(hsv.z + 0.15, 0.0, 1.0)
                                )), 1.0);

                                //Darker
                                palette[baseIdx + 2] = vec4(moni_hsv2rgb(vec3(
                                hsv.x,
                                clamp(hsv.y + 0.1, 0.0, 1.0),
                                clamp(hsv.z - 0.2, 0.0, 1.0)
                                )), 1.0);

                                //Hue shifted
                                palette[baseIdx + 3] = vec4(moni_hsv2rgb(vec3(
                                fract(hsv.x + 0.08),
                                hsv.y,
                                hsv.z
                                )), 1.0);
                            }
                        }


                        flat in vec4 vParticleSpeeds;
                        flat in uvec4 vColors;
                        flat in vec4 vAnchor;
                        uniform sampler2D _EndPortalTexture;
                        uniform sampler2D _EndSkyTexture;
                        #PLACEHOLDER_VIEWWIDTH
                        #PLACEHOLDER_VIEWHEIGHT
                        #PLACEHOLDER_FRAMETIMECOUNTER
                        #PLACEHOLDER_PROJECTIONINVERSE
                        #PLACEHOLDER_MODELVIEWINVERSE
                        void main""");
        newSource = newSource.replace("#PLACEHOLDER_VIEWWIDTH",
                newSource.contains("viewWidth") ? "" : "uniform float viewWidth;");
        newSource = newSource.replace("#PLACEHOLDER_VIEWHEIGHT",
                newSource.contains("viewHeight") ? "" : "uniform float viewHeight;");
        newSource = newSource.replace("#PLACEHOLDER_FRAMETIMECOUNTER",
                newSource.contains("frameTimeCounter") ? "" : "uniform float frameTimeCounter;");
        newSource = newSource.replace("#PLACEHOLDER_PROJECTIONINVERSE",
                newSource.contains("gbufferProjectionInverse") ? "" : "uniform mat4 gbufferProjectionInverse;");
        newSource = newSource.replace("#PLACEHOLDER_MODELVIEWINVERSE",
                newSource.contains("gbufferModelViewInverse") ? "" : "uniform mat4 gbufferModelViewInverse;");
        // we do this to make sure that all referenced uniforms are in the source code before they are used
        newSource = newSource.replace("void main",
                """
                        vec3 moni_end_portal_layer(vec3 dir, float layer, float speedModifier) {
                            mat3 orientation = moni_rotate_axis(moni_hash_dir(layer), layer * layer * 4321.0 + layer * 9.0);
                            float drift = frameTimeCounter * (0.005 + layer * 0.002) * speedModifier;
                            mat3 rotation = moni_rotate_axis(moni_hash_dir(layer + 31.0), drift) * orientation;

                            vec2 uv = moni_cube_uv(rotation * dir) * 0.07;
                            uv = mat2_rotate_z(radians(layer * 37.0)) * uv * ((4.5 - layer / 4.0) * 2.0) + vec2(17.0 / layer, 0.0);
                            return texture(_EndPortalTexture, uv).rgb;
                        }
                        void main""");
        newSource = newSource.replace(result.firstWriteLine(),
                """
                        if (iris_entityInfo.x == 6767) {
                            float _speeds[4] = float[](vParticleSpeeds.x, vParticleSpeeds.y, vParticleSpeeds.z, vParticleSpeeds.w);
                            vec4 _palette[4];
                            vec4 _expandedPalette[16];
                            unpackPalette(vColors, _palette);
                            expandPalette(_palette, _expandedPalette);
                            vec3 _ndc = vec3(gl_FragCoord.xy / vec2(viewWidth, viewHeight), gl_FragCoord.z) * 2.0 - 1.0;
                            vec4 _viewPos = gbufferProjectionInverse * vec4(_ndc, 1.0);
                            _viewPos.xyz /= _viewPos.w;
                            mat3 _viewToWorld = mat3(gbufferModelViewInverse);
                            vec3 _dir = normalize(_viewToWorld * _viewPos.xyz);
                            vec3 _anchorOffset = _viewToWorld * (_viewPos.xyz - vAnchor.xyz);

                            vec3 _color = texture(_EndSkyTexture, moni_cube_uv(_dir) * 0.07 + 0.5).rgb * iris_vertexColor.rgb;
                            for (int i = 0; i < MONI_END_PORTAL_LAYERS; i++) {
                                vec3 _layerDir = _dir;
                                if (i >= MONI_NEAR_LAYERS_START) {
                                    float _shellScale = 1.5 + float(MONI_END_PORTAL_LAYERS - 1 - i) * 0.5;
                                    _layerDir = moni_sphere_exit_dir(_anchorOffset, _dir, vAnchor.w * _shellScale);
                                }
                                vec3 textureColor = moni_end_portal_layer(_layerDir, float(i + 1), _speeds[i / 4]);
                                bool isStar = length(textureColor) > 0.1;
                                bool isBlackish = length(_expandedPalette[i].rgb) < 0.1;
                                if (isBlackish && isStar)
                                    _color = _expandedPalette[i].rgb;
                                else
                                    _color += textureColor * _expandedPalette[i].rgb;
                            }

                            %s.rgb = _color;
                        }
                        """
                        .formatted(result.referencedVariable()) + "\n\t" +
                        result.firstWriteLine());
        return newSource;
    }

    @Redirect(method = "compileShaderInternal",
              at = @At(value = "INVOKE",
                       target = "Lorg/apache/commons/io/IOUtils;toString(Ljava/io/InputStream;Ljava/nio/charset/Charset;)Ljava/lang/String;"))
    private static String moniLabs$modifyIrisEntityDiffuseShader(InputStream sw, Charset input, Program.Type type,
                                                                 String name) throws IOException {
        String original = IOUtils.toString(sw, input);
        if (name.contains("block_entity_diffuse")) {
            var analysisResult = moniLabs$analyzeShaderForColorWrite(original);
            return moniLabs$modifyIrisShaderpackShader(original, analysisResult, type);
        }

        return original;
    }
}
