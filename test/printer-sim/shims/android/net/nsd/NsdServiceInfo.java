package android.net.nsd;

import java.net.InetAddress;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Same accessors as Android's NsdServiceInfo. */
public class NsdServiceInfo {
    private String serviceName, serviceType;
    private final List<InetAddress> hosts = new ArrayList<>();
    private int port;
    private final Map<String, byte[]> attributes = new LinkedHashMap<>();

    public NsdServiceInfo() {
    }

    public String getServiceName() { return serviceName; }
    public void setServiceName(String s) { serviceName = s; }
    public String getServiceType() { return serviceType; }
    public void setServiceType(String s) { serviceType = s; }

    /** Like Android: the first resolved address. */
    public InetAddress getHost() { return hosts.isEmpty() ? null : hosts.get(0); }
    public void setHost(InetAddress h) { hosts.clear(); if (h != null) hosts.add(h); }
    public List<InetAddress> getHostAddresses() { return new ArrayList<>(hosts); }
    public void setHostAddresses(List<InetAddress> h) { hosts.clear(); hosts.addAll(h); }

    public int getPort() { return port; }
    public void setPort(int p) { port = p; }
    public Map<String, byte[]> getAttributes() { return attributes; }
    public void setAttribute(String k, String v) { attributes.put(k, v == null ? null : v.getBytes()); }

    @Override
    public String toString() {
        return serviceName + " " + serviceType + " " + hosts + ":" + port;
    }
}
