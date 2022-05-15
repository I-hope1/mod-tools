package modtools.ui;

public class IntFunc {

	public static int parseInt(String string) {
		try {
			return Integer.parseInt(string);
		} catch (Exception e) {
			return 0;
		}
	}

	public static float parseFloat(String string) {
		try {
			return Float.parseFloat(string);
		} catch (Exception e) {
			return 0f;
		}
	}


}
