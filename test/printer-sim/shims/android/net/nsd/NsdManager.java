package android.net.nsd;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A fake mDNS network. Behaves like Android's NsdManager where it matters:
 * callbacks arrive on a binder-like background thread, and resolveService
 * fails with FAILURE_ALREADY_ACTIVE (3) if a resolve is already running
 * (Android 13 and older), so the kiosk's one-at-a-time queue is exercised.
 */
public class NsdManager {
    public static final int PROTOCOL_DNS_SD = 1;
    public static final int FAILURE_INTERNAL_ERROR = 0, FAILURE_ALREADY_ACTIVE = 3;

    public interface DiscoveryListener {
        void onStartDiscoveryFailed(String serviceType, int errorCode);
        void onStopDiscoveryFailed(String serviceType, int errorCode);
        void onDiscoveryStarted(String serviceType);
        void onDiscoveryStopped(String serviceType);
        void onServiceFound(NsdServiceInfo serviceInfo);
        void onServiceLost(NsdServiceInfo serviceInfo);
    }

    public interface ResolveListener {
        void onResolveFailed(NsdServiceInfo serviceInfo, int errorCode);
        void onServiceResolved(NsdServiceInfo serviceInfo);
    }

    /** A service on the fake network: what discovery reports, and what resolving it returns (null = resolve fails). */
    public static final class Advert {
        final NsdServiceInfo found, resolved;

        public Advert(NsdServiceInfo found, NsdServiceInfo resolved) {
            this.found = found;
            this.resolved = resolved;
        }
    }

    public final List<Advert> network = new ArrayList<>();
    public final AtomicInteger resolveCalls = new AtomicInteger(), alreadyActiveFailures = new AtomicInteger(),
            discoveriesStarted = new AtomicInteger(), discoveriesStopped = new AtomicInteger();
    private final AtomicBoolean resolving = new AtomicBoolean();

    public void discoverServices(String serviceType, int protocol, DiscoveryListener l) {
        discoveriesStarted.incrementAndGet();
        new Thread(() -> {
            l.onDiscoveryStarted(serviceType);
            for (Advert a : network) {
                String t = a.found.getServiceType();
                if (t != null && t.replace(".", "").equals(serviceType.replace(".", ""))) {
                    sleep(150);
                    l.onServiceFound(a.found);
                }
            }
        }, "binder-discovery").start();
    }

    public void stopServiceDiscovery(DiscoveryListener l) {
        discoveriesStopped.incrementAndGet();
        new Thread(() -> l.onDiscoveryStopped(""), "binder-stop").start();
    }

    public void resolveService(NsdServiceInfo info, ResolveListener l) {
        resolveCalls.incrementAndGet();
        if (!resolving.compareAndSet(false, true)) {
            alreadyActiveFailures.incrementAndGet();
            new Thread(() -> l.onResolveFailed(info, FAILURE_ALREADY_ACTIVE), "binder-resolve").start();
            return;
        }
        new Thread(() -> {
            sleep(200);
            NsdServiceInfo out = null;
            for (Advert a : network) if (a.found == info) out = a.resolved;
            resolving.set(false);
            if (out == null) l.onResolveFailed(info, FAILURE_INTERNAL_ERROR);
            else l.onServiceResolved(out);
        }, "binder-resolve").start();
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException ignored) {
        }
    }
}
