package android.os;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/** JVM stand-in: every Handler posts to a single shared "main" thread, as on Android. */
public class Handler {
    private static volatile Thread mainThread;
    private static final ScheduledExecutorService MAIN = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "main");
        t.setDaemon(true);
        mainThread = t;
        return t;
    });

    public Handler() {
    }

    public Handler(Looper looper) {
    }

    static boolean onMainThread() {
        return Thread.currentThread() == mainThread;
    }

    public final boolean post(Runnable r) {
        MAIN.execute(guard(r));
        return true;
    }

    public final boolean postDelayed(Runnable r, long delayMillis) {
        MAIN.schedule(guard(r), delayMillis, TimeUnit.MILLISECONDS);
        return true;
    }

    public final void removeCallbacks(Runnable r) {
        // Not needed by the code under test.
    }

    public final void removeCallbacksAndMessages(Object token) {
    }

    private static Runnable guard(Runnable r) {
        return () -> {
            try {
                r.run();
            } catch (Throwable t) {
                System.out.println("!! exception on main thread: " + t);
                t.printStackTrace(System.out);
            }
        };
    }
}
