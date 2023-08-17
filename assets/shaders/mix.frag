uniform lowp sampler2D u_texture0;
uniform lowp sampler2D u_texture1;

uniform vec4 color;

varying vec2 v_texCoords;

void main(){
	vec4 be_replaced = texture2D(u_texture1, v_texCoords);
	if (be_replaced.a > 0.0){
		gl_FragColor = color;
	} else {
		gl_FragColor = texture2D(u_texture0, v_texCoords);
	}
}