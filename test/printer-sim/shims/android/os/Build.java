package android.os;

/** Pretend to be an Android 14 tablet. */
public class Build {
    public static final String MANUFACTURER = "Test", MODEL = "JVM";

    public static class VERSION {
        public static final int SDK_INT = Integer.getInteger("sdk", 34);
        public static final String RELEASE = "14";
    }

    public static class VERSION_CODES {
        public static final int O = 26, P = 28, R = 30, S = 31, TIRAMISU = 33, UPSIDE_DOWN_CAKE = 34;
    }
}
