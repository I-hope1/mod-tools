package test0;

import java.util.*;

class GlyphRun {
    String text;
    String color;
    int line;

    GlyphRun(String text, String color, int line) {
        this.text = text;
        this.color = color;
        this.line = line;
    }

    @Override
    public String toString() {
        return "(" + text + ", " + color + ", " + line + ")";
    }
}

public class ColorSplit {
    public static List<GlyphRun> splitAndColorize(List<GlyphRun> runs, Map<Integer, String> colorMap) {
        List<GlyphRun> result = new ArrayList<>();
        int globalIndex = 0;

        for (GlyphRun run : runs) {
            int localIndex = 0;
            while (localIndex < run.text.length()) {
                int nextChangeIndex = getNextChangeIndex(globalIndex + localIndex, colorMap);
                int length = nextChangeIndex - (globalIndex + localIndex);
                length = Math.min(length, run.text.length() - localIndex);

                String substring = run.text.substring(localIndex, localIndex + length);
                String color = getColorForIndex(globalIndex + localIndex, colorMap, run.color);

                result.add(new GlyphRun(substring, color, run.line));
                localIndex += length;
            }
            globalIndex += run.text.length();
        }

        return result;
    }

    private static int getNextChangeIndex(int currentIndex, Map<Integer, String> colorMap) {
        for (Integer key : colorMap.keySet()) {
            if (key > currentIndex) {
                return key;
            }
        }
        return Integer.MAX_VALUE;
    }

    private static String getColorForIndex(int index, Map<Integer, String> colorMap, String defaultColor) {
        String color = defaultColor;
        for (Map.Entry<Integer, String> entry : colorMap.entrySet()) {
            if (index >= entry.getKey()) {
                color = entry.getValue();
            } else {
                break;
            }
        }
        return color;
    }

    public static void main(String[] args) {
        List<GlyphRun> runs = Arrays.asList(
            new GlyphRun("text", "white", 1),
            new GlyphRun("text", "white", 2)
        );

        Map<Integer, String> colorMap = new TreeMap<>();
        colorMap.put(1, "pink");
        colorMap.put(2, "white");
        colorMap.put(5, "sky");

        List<GlyphRun> result = splitAndColorize(runs, colorMap);
        for (GlyphRun run : result) {
            System.out.println(run);
        }
    }
}
