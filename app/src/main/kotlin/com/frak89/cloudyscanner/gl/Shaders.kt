package com.frak89.cloudyscanner.gl

object Shaders {
    const val BG_VS = """#version 300 es
layout(location=0) in vec2 aPos;
layout(location=1) in vec2 aUv;
out vec2 vUv;
void main() { vUv = aUv; gl_Position = vec4(aPos, 0.0, 1.0); }
"""

    /** Camera passthrough. uMode 1 draws a Sobel edge map instead of the image. */
    const val BG_FS = """#version 300 es
#extension GL_OES_EGL_image_external_essl3 : require
precision mediump float;
uniform samplerExternalOES uTex;
uniform int uMode;
uniform vec2 uTexel;
uniform float uDim;
in vec2 vUv;
out vec4 oColor;
float luma(vec2 uv) { return dot(texture(uTex, uv).rgb, vec3(0.299, 0.587, 0.114)); }
void main() {
  vec3 c = texture(uTex, vUv).rgb;
  if (uMode == 1) {
    float tl = luma(vUv + uTexel * vec2(-1.0, -1.0));
    float tc = luma(vUv + uTexel * vec2(0.0, -1.0));
    float tr = luma(vUv + uTexel * vec2(1.0, -1.0));
    float ml = luma(vUv + uTexel * vec2(-1.0, 0.0));
    float mr = luma(vUv + uTexel * vec2(1.0, 0.0));
    float bl = luma(vUv + uTexel * vec2(-1.0, 1.0));
    float bc = luma(vUv + uTexel * vec2(0.0, 1.0));
    float br = luma(vUv + uTexel * vec2(1.0, 1.0));
    float gx = (tr + 2.0 * mr + br) - (tl + 2.0 * ml + bl);
    float gy = (bl + 2.0 * bc + br) - (tl + 2.0 * tc + tr);
    float e = smoothstep(0.08, 0.45, sqrt(gx * gx + gy * gy));
    c = mix(vec3(0.07, 0.075, 0.09), vec3(0.75, 0.95, 1.0), e);
  }
  oColor = vec4(c * uDim, 1.0);
}
"""

    const val POINT_VS = """#version 300 es
layout(location=0) in vec3 aPos;
layout(location=1) in vec4 aCol;
layout(location=2) in vec4 aNrmConf;
layout(location=3) in vec4 aFlags;
uniform mat4 uVP;
uniform mat4 uModel;
uniform vec3 uCamPos;
uniform float uPointSize;
uniform float uPersp;
uniform int uColorMode;
uniform int uCull;
uniform int uShowUncertain;
uniform int uShowSubject;
uniform float uTime;
uniform float uWave;
uniform vec2 uHeight;
out vec4 vCol;
vec3 ramp(float t) {
  t = clamp(t, 0.0, 1.0);
  return clamp(vec3(1.5 - abs(4.0 * t - 3.0), 1.5 - abs(4.0 * t - 2.0), 1.5 - abs(4.0 * t - 1.0)), 0.0, 1.0);
}
void main() {
  vec3 n = normalize(aNrmConf.xyz + vec3(0.0, 1e-5, 0.0));
  vec3 p = aPos;
  if (uWave > 0.0) p += n * sin(uTime * 2.5 + aPos.y * 9.0 + aPos.x * 4.0) * 0.012 * uWave;
  vec4 world = uModel * vec4(p, 1.0);
  vec3 wn = mat3(uModel) * n;
  vec3 toCam = uCamPos - world.xyz;
  float dist = length(toCam);
  gl_Position = uVP * world;
  if (uCull == 1 && dot(wn, toCam) < 0.0) gl_Position = vec4(2.0, 2.0, 2.0, 1.0);
  float size = uPointSize;
  if (uPersp > 0.5) size *= clamp(1.6 / max(dist, 0.05), 0.35, 3.0);
  float conf = clamp(aNrmConf.w, 0.0, 1.0);
  vec3 c;
  if (uColorMode == 0) c = vec3(0.92);
  else if (uColorMode == 1) c = aCol.rgb;
  else if (uColorMode == 2) c = mix(vec3(1.0, 0.25, 0.2), vec3(0.25, 1.0, 0.45), conf);
  else if (uColorMode == 3) c = ramp(dist / 4.0);
  else if (uColorMode == 4) c = ramp((aPos.y - uHeight.x) / max(uHeight.y - uHeight.x, 0.01));
  else c = n * 0.5 + 0.5;
  float f = aFlags.x;
  bool sel = mod(f, 2.0) >= 1.0;
  bool subj = mod(floor(f / 2.0), 2.0) >= 1.0;
  bool unc = mod(floor(f / 4.0), 2.0) >= 1.0;
  bool pal = mod(floor(f / 8.0), 2.0) >= 1.0;
  if (pal) c = aCol.rgb;
  if (subj && uShowSubject == 1 && !pal) c = mix(c, vec3(0.35, 0.9, 1.0), 0.45);
  if (unc && uShowUncertain == 1) {
    c = mix(c, vec3(1.0, 0.62, 0.1), 0.6 + 0.4 * sin(uTime * 6.0));
    size *= 1.25;
  }
  if (sel) { c = vec3(0.72, 0.25, 1.0); size *= 1.5; }
  vCol = vec4(c, 1.0);
  gl_PointSize = max(size, 1.0);
}
"""

    const val POINT_FS = """#version 300 es
precision mediump float;
in vec4 vCol;
out vec4 oColor;
void main() {
  vec2 d = gl_PointCoord - vec2(0.5);
  if (dot(d, d) > 0.25) discard;
  oColor = vCol;
}
"""

    const val MESH_VS = """#version 300 es
layout(location=0) in vec3 aPos;
layout(location=1) in vec3 aNrm;
layout(location=2) in vec4 aCol;
uniform mat4 uVP;
uniform mat4 uModel;
out vec3 vWorld;
out vec3 vNrm;
out vec4 vCol;
void main() {
  vec4 w = uModel * vec4(aPos, 1.0);
  vWorld = w.xyz;
  vNrm = mat3(uModel) * aNrm;
  vCol = aCol;
  gl_Position = uVP * w;
}
"""

    /** Shared by solid faces and wireframe lines. */
    const val MESH_FS = """#version 300 es
precision highp float;
uniform vec3 uCamPos;
uniform vec4 uColor;
uniform int uUseVertexColor;
uniform int uLit;
uniform int uFlat;
uniform int uDiscardBack;
in vec3 vWorld;
in vec3 vNrm;
in vec4 vCol;
out vec4 oColor;
void main() {
  vec3 v = normalize(uCamPos - vWorld);
  vec3 n = normalize(vNrm);
  if (uDiscardBack == 1 && dot(n, v) < -0.05) discard;
  if (uFlat == 1) {
    n = normalize(cross(dFdx(vWorld), dFdy(vWorld)));
    if (dot(n, v) < 0.0) n = -n;
  }
  vec3 base = uUseVertexColor == 1 ? vCol.rgb : uColor.rgb;
  if (uLit == 1) {
    float d = abs(dot(n, v));
    base *= 0.28 + 0.72 * d;
    base += vec3(0.12) * pow(d, 24.0);
  }
  oColor = vec4(base, uColor.a);
}
"""

    const val LINE_VS = """#version 300 es
layout(location=0) in vec3 aPos;
uniform mat4 uMVP;
void main() { gl_Position = uMVP * vec4(aPos, 1.0); }
"""

    const val LINE_FS = """#version 300 es
precision mediump float;
uniform vec4 uColor;
out vec4 oColor;
void main() { oColor = uColor; }
"""
}
