package android.util;

public final class Log {
    public static volatile boolean quiet = false;

    private static int p(String level, String tag, String msg, Throwable t) {
        if (!quiet) System.out.println("   [log " + level + "/" + tag + "] " + msg + (t == null ? "" : " :: " + t));
        return 0;
    }

    public static int d(String tag, String msg) { return p("D", tag, msg, null); }
    public static int i(String tag, String msg) { return p("I", tag, msg, null); }
    public static int w(String tag, String msg) { return p("W", tag, msg, null); }
    public static int w(String tag, String msg, Throwable t) { return p("W", tag, msg, t); }
    public static int e(String tag, String msg) { return p("E", tag, msg, null); }
    public static int e(String tag, String msg, Throwable t) { return p("E", tag, msg, t); }
}
