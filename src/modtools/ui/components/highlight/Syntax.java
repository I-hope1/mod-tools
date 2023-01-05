package modtools.ui.components.highlight;

import arc.graphics.Color;
import mindustry.graphics.Pal;
import modtools.ui.components.area.TextAreaTable;
import modtools.ui.components.area.TextAreaTable.MyTextArea;

import java.util.regex.Pattern;

import static java.util.regex.Pattern.COMMENTS;

public class Syntax {
	static final Pattern
			whiteSpaceP = Pattern.compile("(\\s+)"),
			stringP = Pattern.compile("(([\"'`]).*?(?<!\\\\)\\2)", COMMENTS),
			operatCharP = Pattern.compile("([~|,+=*/\\-<>!]+)", COMMENTS),
			bracketsP = Pattern.compile("([\\[{()}\\]]+)", COMMENTS),
			others = Pattern.compile("([\\s\\S])")
					// ,whitespace = Pattern.compile("(\\s+)")
					;

	static final Color
			stringC = Color.valueOf("#ce9178"),
			keywordC = Color.valueOf("#569cd6"),
			numberC = Color.valueOf("#b5cea8"),
			commentC = Color.valueOf("#6a9955"),
			bracketsC = Color.valueOf("#ffd704"),
			operatCharC = Pal.accentBack,
			functionsC = Color.sky,//Color.valueOf("#ae81ff")
			objectsC = Color.valueOf("#66d9ef");
	/*public static class Node {
		public boolean has;
		public Node parent;
		public Node left;
		public Node right;

		public Node(boolean has, Node parent, Node left, Node right) {
			this.has = has;
			this.parent = parent;
			this.left = left;
			this.right = right;
		}

		static Node currentNode = null;

		static Node node(boolean has, Node parent, Node left, Node right) {
			Node node = new Node(has, parent, left, right);
			currentNode = node;
			return node;
		}

		static Node node(boolean has, Node left, Node right) {
			return node(has, currentNode, left, right);
		}

	}*/
	// public static JsonReader reader = new JsonReader();

	public final TextAreaTable areaTable;

	public Syntax(TextAreaTable table) {
		areaTable = table;
		area = areaTable.getArea();
	}


	public boolean isWordBreak(char c) {
		return !((48 <= c && c <= 57) || (65 <= c && c <= 90) || (97 <= c && c <= 122) || (19968 <= c && c <= 40869));
	}

	public void highlightingDraw(String displayText) {
	}


	public MyTextArea area;
	public String displayText;

}
