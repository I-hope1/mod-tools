uniform lowp sampler2D u_texture0;
uniform lowp sampler2D u_texture1;

varying vec2 v_texCoords;

void main(){
	vec4 be_replaced = texture2D(u_texture0, v_texCoords);
	vec4 to = texture2D(u_texture1, v_texCoords);

	if (be_replaced.a > 0.0){
		gl_FragColor = to;
	} else {
		gl_FragColor = be_replaced;
	}
}