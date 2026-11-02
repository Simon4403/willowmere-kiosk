package android.content;

import java.io.File;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/** JVM stand-in for an app Context: system services are whatever the test registers. */
public class Context {
    public static final String NSD_SERVICE = "servicediscovery";
    public static final int MODE_PRIVATE = 0;

    public static final Map<String, Object> SERVICES = new ConcurrentHashMap<>();
    private static final Map<String, Mem> PREFS = new ConcurrentHashMap<>();

    public Context getApplicationContext() {
        return this;
    }

    public Object getSystemService(String name) {
        return SERVICES.get(name);
    }

    public String getPackageName() {
        return "com.heracaresolutions.kiosk";
    }

    public File getCacheDir() {
        return new File(System.getProperty("java.io.tmpdir"));
    }

    public SharedPreferences getSharedPreferences(String name, int mode) {
        return PREFS.computeIfAbsent(name, n -> new Mem());
    }

    /** In-memory SharedPreferences. */
    static final class Mem implements SharedPreferences {
        final Map<String, Object> m = new ConcurrentHashMap<>();

        @SuppressWarnings("unchecked")
        private <T> T get(String k, T d) {
            Object v = m.get(k);
            return v == null ? d : (T) v;
        }

        public Map<String, ?> getAll() { return new HashMap<>(m); }
        public String getString(String k, String d) { return get(k, d); }
        public Set<String> getStringSet(String k, Set<String> d) { return get(k, d); }
        public int getInt(String k, int d) { return get(k, d); }
        public long getLong(String k, long d) { return get(k, d); }
        public float getFloat(String k, float d) { return get(k, d); }
        public boolean getBoolean(String k, boolean d) { return get(k, d); }
        public boolean contains(String k) { return m.containsKey(k); }

        public Editor edit() {
            Map<String, Object> pending = new HashMap<>();
            java.util.List<String> removed = new java.util.ArrayList<>();
            return new Editor() {
                public Editor putString(String k, String v) { if (v == null) removed.add(k); else pending.put(k, v); return this; }
                public Editor putStringSet(String k, Set<String> v) { pending.put(k, v); return this; }
                public Editor putInt(String k, int v) { pending.put(k, v); return this; }
                public Editor putLong(String k, long v) { pending.put(k, v); return this; }
                public Editor putFloat(String k, float v) { pending.put(k, v); return this; }
                public Editor putBoolean(String k, boolean v) { pending.put(k, v); return this; }
                public Editor remove(String k) { removed.add(k); return this; }
                public Editor clear() { m.clear(); return this; }
                public boolean commit() { for (String k : removed) m.remove(k); m.putAll(pending); return true; }
                public void apply() { commit(); }
            };
        }
    }
}
