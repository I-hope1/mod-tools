#define HIGHP
#define ALPHA 1
#define step 2.0

uniform sampler2D u_texture;
uniform vec2 u_texsize;
uniform vec2 u_invsize;
uniform float u_time;
uniform vec2 u_offset;

// uniform float ALPHA;
varying vec2 v_texCoords;
void main(){
    vec2 T = v_texCoords.xy;
    vec2 coords = (T * u_texsize) + u_offset;
    T += vec2(sin(coords.y / 3.0 + u_time / 20.0), sin(coords.x / 3.0 + u_time / 20.0)) / u_texsize;
    vec4 color = texture2D(u_texture, T);
    vec2 v = u_invsize;

    if (color.a == 0.0) {
        gl_FragColor = color;// 设置输出颜色
        return;
    }
    // 添加毛玻璃效果
    color.a = ALPHA;// 设置半透明度
    //    color.rgb = mix(color.rgb, vec3(0.0), ALPHA);// 设置颜色
    color = HIGHP vec4(color.rgb, color.a);// 设置高精度
    gl_FragColor = color;// 设置输出颜色
}