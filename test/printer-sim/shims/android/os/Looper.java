package android.os;

/** JVM stand-in: one "main" thread, like Android's UI thread. */
public final class Looper {
    private static final Looper MAIN = new Looper();

    public static Looper getMainLooper() {
        return MAIN;
    }

    public static Looper myLooper() {
        return Handler.onMainThread() ? MAIN : null;
    }
}
