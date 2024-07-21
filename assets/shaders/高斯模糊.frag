#define HIGHP

uniform sampler2D u_texture;

uniform float u_kernel[9];
uniform vec2 u_invsize;
varying vec2 v_texCoords;

// 高斯核权重
// const float kernel[9] = float[](
//     0.02, 0.125, 0.0625,
//     0.125, 0.25, 0.125,
//     0.0625, 0.125, 0.105
// );

void main(){
    vec2 T = v_texCoords.xy;
    vec4 color = vec4(0.0);

    // 对每个像素点进行高斯卷积
    for (int i = -1; i <= 1; i++) {
        for (int j = -1; j <= 1; j++) {
            color += texture2D(u_texture, T + vec2(i, j) * u_invsize) * u_kernel[(i + 1) * 3 + (j + 1)];
        }
    }

    gl_FragColor = color;
}