package hope.magic.example;

public class TargetObject {
	private int secretCode = 12345;
	private String message = "Hello, Private Field!";

	public TargetObject() {
	}

	private TargetObject(int secretCode, String message) {
		this.secretCode = secretCode;
		this.message = message;
	}

	public int multiply(int a, int b) {
		return a * b;
	}

	private static String staticPrivateGreet(String name) {
		return "Greetings, " + name + " (from static private method)";
	}

	public int getSecretCode() {
		return secretCode;
	}

	public String getMessage() {
		return message;
	}
}
