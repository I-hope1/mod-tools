package modtools.ui.effect;

import arc.Core;
import arc.graphics.*;
import arc.graphics.g2d.*;
import arc.graphics.gl.*;
import modtools.events.*;
import modtools.ui.effect.MyDraw.DrawEffect;

import static modtools.IntVars.NL;

/**
 * from EB-wilson
 * @author EB-wilson
 */
public class EBBlur implements DrawEffect {
	public enum DEF {
		A(
		 0.0086973240159f, 0.0359949776755f, 0.1093610049784f,
		 0.2129658870149f, 0.2659615230194f, 0.2129658870149f,
		 0.1093610049784f, 0.0359949776755f, 0.0086973240159f
		), B(
		 0.0444086447005f, 0.0779944219933f, 0.1159966211046f,
		 0.1673080561213f, 0.1885769121606f, 0.1673080561213f,
		 0.1159966211046f, 0.0779944219933f, 0.0444086447005f
		), C(
		 0.0045418484119f, 0.0539998665132f, 0.2419867245191f,
		 0.3989431211116f,
		 0.2419867245191f, 0.0539998665132f, 0.0045418484119f
		), D(
		 0.0245418484119f, 0.0639998665132f, 0.2519867245191f,
		 0.3189431211116f,
		 0.2519867245191f, 0.0639998665132f, 0.0245418484119f
		), E(
		 0.019615710072f, 0.2054255182127f,
		 0.5599175434306f,
		 0.2054255182127f, 0.019615710072f
		), F(
		 0.0702702703f, 0.3162162162f,
		 0.2270270270f,
		 0.3162162162f, 0.0702702703f
		), G(
		 0.2079819330264f,
		 0.6840361339472f,
		 0.2079819330264f
		), H(
		 0.2561736558128f,
		 0.4876526883744f,
		 0.2561736558128f
		);
		DEF(float... floats) {
			this.floats = floats;
		}
		public final float[] floats;
	}

	Shader      blurShader;
	FrameBuffer buffer, pingpong, screen;

	boolean capturing;

	public int   blurScl   = 4;
	public float blurSpace = 1.26f;
	public float alpha     = 1.0f;

	public EBBlur() {
		this(R_Blur.convolution_scheme.floats);
	}

	public EBBlur(float... convolutions) {
		blurShader = genShader(convolutions);

		buffer = new FrameBuffer();
		pingpong = new FrameBuffer();
		screen = new FrameBuffer();

		blurShader.bind();
		blurShader.setUniformi("u_texture0", 0);
		blurShader.setUniformi("u_texture1", 1);
	}

	public static Shader genShader(float... convolutions) {
		if (convolutions.length % 2 != 1) {
			throw new IllegalArgumentException("convolution numbers length must be odd number!");
		}

		int convLen = convolutions.length;

		StringBuilder varyings    = new StringBuilder();
		StringBuilder assignVar   = new StringBuilder();
		StringBuilder convolution = new StringBuilder();

		int c    = 0;
		int half = convLen / 2;
		for (float v : convolutions) {
			varyings.append("varying vec2 v_texCoords")
			 .append(c)
			 .append(";")
			 .append(NL);

			assignVar.append("v_texCoords")
			 .append(c)
			 .append(" = ")
			 .append("a_texCoord0");
			if (c - half != 0) {
				assignVar.append(c - half > 0 ? "+" : "-")
				 .append(Math.abs((float) c - half))
				 .append("*len");
			}
			assignVar.append(";")
			 .append(NL).append("  ");

			if (c > 0) convolution.append("        + ");
			convolution.append(v)
			 .append("*texture2D(u_texture1, v_texCoords")
			 .append(c)
			 .append(")")
			 .append(".rgb")
			 .append(NL);

			c++;
		}
		convolution.append(";");

		String vertexShader =
		 """
			attribute vec4 a_position;
			attribute vec2 a_texCoord0;
			
			uniform vec2 dir;
			uniform vec2 size;
			
			varying vec2 v_texCoords;
			%s
			void main(){
			  vec2 len = dir/size;
			
			  v_texCoords = a_texCoord0;
				%s
			  gl_Position = a_position;
			}
			""".formatted(varyings, assignVar);
		String fragmentShader =
		 """
			uniform lowp sampler2D u_texture0;
			uniform lowp sampler2D u_texture1;
			
			uniform lowp float def_alpha;
			uniform lowp float u_alpha;
			
			varying vec2 v_texCoords;
			%s
			void main(){
			  vec3 blurColor = %s
			
			  if(def_alpha > 0.5){
			    gl_FragColor = vec4(blurColor, 1.0);
			  } else {
			    float maskAlpha = texture2D(u_texture0, v_texCoords).a;
			    gl_FragColor = vec4(blurColor, maskAlpha * u_alpha);
			  }
			}
			""".formatted(varyings, convolution);

		// Log.info("vert: @\n\nfrag: @", 	(vertexShader, fragmentShader);

		return new Shader(vertexShader, fragmentShader);
		// return null;
	}
	public void resize(float width, float height) {
		blurScl = E_Blur.scale_level.getInt();
		if (blurScl <= 0) blurScl = 1;

		// 遮罩 buffer 必须是 1:1 全屏原生分辨率，彻底消除移动时的 4 像素量化卡顿与坐标错位
		buffer.resize((int) width, (int) height);

		// pingpong 用于低分辨率模糊加速，保持 1/blurScl
		int blurW = Math.max(1, (int) (width / blurScl));
		int blurH = Math.max(1, (int) (height / blurScl));
		pingpong.resize(blurW, blurH);

		blurShader.bind();
		blurShader.setUniformf("size", (float) blurW, (float) blurH);
	}
	public void capture(float x, float y, float w, float h) {
		alpha = Draw.getColor().a;

		capture();
		Draw.color(1f, 1f, 1f, 1f);
		Fill.crect(x, y, w, h);
	}

	public void capture() {
		if (!capturing) {
			buffer.begin();
			// 必须使用硬件清屏，确保彻底抹除上一帧残留的白雾和孤立小方块
			Gl.clearColor(0f, 0f, 0f, 0f);
			Gl.clear(Gl.colorBufferBit);

			capturing = true;
		}
	}

	public void render() {
		if (!capturing) return;
		Draw.reset();
		capturing = false;
		buffer.end();

		Gl.disable(Gl.blend);
		Gl.disable(Gl.depthTest);
		Gl.depthMask(false);

		screen.resize(Core.graphics.getWidth(), Core.graphics.getHeight());
		ScreenSampler.instance.getToBuffer(screen, true);
		screen.getTexture().bind(1);

		// Pass 1: 水平降采样模糊 (screen -> pingpong)
		pingpong.begin();
		blurShader.bind();
		blurShader.setUniformf("dir", blurSpace, 0f);
		blurShader.setUniformf("def_alpha", 1);
		screen.getTexture().bind(1);
		Draw.shader();
		pingpong.blit(blurShader);
		pingpong.end();

		// Pass 2: 垂直模糊并采样全分辨率 Mask 贴回屏幕 (pingpong -> screen)
		blurShader.bind();
		blurShader.setUniformf("dir", 0f, blurSpace);
		blurShader.setUniformf("def_alpha", 0);
		blurShader.setUniformf("u_alpha", alpha);
		pingpong.getTexture().bind(1);

		Gl.enable(Gl.blend);
		Gl.blendFunc(Gl.srcAlpha, Gl.oneMinusSrcAlpha);
		buffer.blit(blurShader);
	}
}