package team.terrafirmagreg.appgate.ui;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

public final class Log {
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss");
    private static final Object LOCK = new Object();

    private Log() {
    }

    public static void info(String message) {
        write("INFO", Ansi.BRIGHT_CYAN, message);
    }

    public static void success(String message) {
        write("OK  ", Ansi.BRIGHT_GREEN, message);
    }

    public static void warn(String message) {
        write("WARN", Ansi.BRIGHT_YELLOW, message);
    }

    public static void error(String message) {
        write("ERROR", Ansi.BRIGHT_RED, message);
    }

    public static void error(String message, Throwable error) {
        write("ERROR", Ansi.BRIGHT_RED, message + ": " + error.getMessage());
        synchronized (LOCK) {
            error.printStackTrace(System.out);
        }
    }

    public static void appLine(String appName, boolean stderr, String line) {
        String tagColor = stderr ? Ansi.BRIGHT_RED : Ansi.appColor(appName);
        String tag = stderr ? appName + "/ERR" : appName;
        synchronized (LOCK) {
            System.out.println(
                    Ansi.dim(LocalDateTime.now().format(TIME))
                            + " "
                            + Ansi.dim("|")
                            + " "
                            + Ansi.color(tagColor, "[" + tag + "]")
                            + " "
                            + (stderr ? Ansi.color(Ansi.BRIGHT_RED, line) : line)
            );
        }
    }

    private static void write(String level, String levelColor, String message) {
        String time = Ansi.dim(LocalDateTime.now().format(TIME));
        String wrapper = Ansi.color(Ansi.BRIGHT_MAGENTA, "appgate");
        String lvl = Ansi.color(levelColor, level);
        String body = message == null ? "" : message;
        synchronized (LOCK) {
            System.out.println(time + " " + Ansi.dim("|") + " " + wrapper + " " + lvl + " " + body);
        }
    }
}
