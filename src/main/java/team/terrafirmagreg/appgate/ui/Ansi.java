package team.terrafirmagreg.appgate.ui;

import java.util.Locale;

public final class Ansi {
    public static final String RESET = "\u001B[0m";
    public static final String BOLD = "\u001B[1m";
    public static final String DIM = "\u001B[2m";

    public static final String GREEN = "\u001B[32m";
    public static final String YELLOW = "\u001B[33m";
    public static final String BLUE = "\u001B[34m";
    public static final String MAGENTA = "\u001B[35m";
    public static final String CYAN = "\u001B[36m";

    public static final String BRIGHT_RED = "\u001B[91m";
    public static final String BRIGHT_GREEN = "\u001B[92m";
    public static final String BRIGHT_YELLOW = "\u001B[93m";
    public static final String BRIGHT_BLUE = "\u001B[94m";
    public static final String BRIGHT_MAGENTA = "\u001B[95m";
    public static final String BRIGHT_CYAN = "\u001B[96m";

    private static final String[] APP_COLORS = {
            BRIGHT_CYAN,
            BRIGHT_GREEN,
            BRIGHT_YELLOW,
            BRIGHT_MAGENTA,
            BRIGHT_BLUE,
            CYAN,
            GREEN,
            YELLOW,
            MAGENTA,
            BLUE
    };

    private static final boolean ENABLED = detectEnabled();

    private Ansi() {
    }

    public static String color(String code, String text) {
        if (!ENABLED) {
            return text;
        }
        return code + text + RESET;
    }

    public static String bold(String text) {
        return color(BOLD, text);
    }

    public static String dim(String text) {
        return color(DIM, text);
    }

    public static String appColor(String appName) {
        if (appName.isBlank()) {
            return CYAN;
        }
        return APP_COLORS[Math.floorMod(appName.hashCode(), APP_COLORS.length)];
    }

    private static boolean detectEnabled() {
        String prop = System.getProperty("terminal.ansi");
        if (prop != null) {
            return Boolean.parseBoolean(prop) || "1".equals(prop) || "yes".equalsIgnoreCase(prop);
        }
        String noColor = System.getenv("NO_COLOR");
        if (noColor != null && !noColor.isBlank()) {
            return false;
        }
        String force = System.getenv("FORCE_COLOR");
        if (force != null && !force.isBlank() && !"0".equals(force)) {
            return true;
        }
        // Pterodactyl pipes often have no System.console(); prefer ANSI unless disabled.
        String term = System.getenv("TERM");
        return term == null || !term.toLowerCase(Locale.ROOT).contains("dumb");
    }
}
