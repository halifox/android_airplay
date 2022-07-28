package com.halifox.airplay;

import java.io.IOException;
import java.lang.reflect.Field;
import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.util.Arrays;
import java.util.Collections;
import java.util.Map;
import java.util.logging.Logger;

import javax.jmdns.JmDNS;
import javax.jmdns.ServiceInfo;
import javax.jmdns.impl.constants.DNSConstants;

/**
 * Created by hjhua on 16-8-19.
 */
public class MdnsServices {
    private static final Logger LOG = Logger.getLogger(MdnsServices.class.getName());

    private static InetAddress ip4addr = null;
    private static InetAddress ip6Addr = null;
    private static String HardwareAddr = null;

    static {
        try {
            Field close_timeout = DNSConstants.class.getDeclaredField("CLOSE_TIMEOUT");
            close_timeout.setAccessible(true);
            close_timeout.set(null, 1000L);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    /**
     * Decides whether or nor a given MAC address is the address of some
     * virtual interface, like e.g. VMware's host-only interface (server-side).
     *
     * @param addr a MAC address
     * @return true if the MAC address is unsuitable as the device's hardware address
     */
    private static boolean isBlockedHardwareAddress(final byte[] addr) {
        if ((addr[0] & 0x02) != 0)
            /* Locally administered */
            return true;
        else if ((addr[0] == 0x00) && (addr[1] == 0x50) && (addr[2] == 0x56))
            /* VMware */
            return true;
        else if ((addr[0] == 0x00) && (addr[1] == 0x1C) && (addr[2] == 0x42))
            /* Parallels */
            return true;
        else if ((addr[0] == 0x00) && (addr[1] == 0x25) && (addr[2] == (byte) 0xAE))
            /* Microsoft */
            return true;
        else
            return false;
    }

    /**
     * Converts an array of bytes to a hexadecimal string
     *
     * @param bytes array of bytes
     * @return hexadecimal representation
     */
    private static String toHexString(final byte[] bytes) {
        final StringBuilder s = new StringBuilder();
        for (final byte b : bytes) {
            final String h = Integer.toHexString(0x100 | b);
            s.append(h.substring(h.length() - 2, h.length()).toUpperCase());
        }
        return s.toString();
    }

    /**
     * Returns a suitable hardware address.
     *
     * @return a MAC address
     */
    private static byte[] getHardwareAddress() {
        try {
            /* Search network interfaces for an interface with a valid, non-blocked hardware address */
            for (final NetworkInterface iface : Collections.list(NetworkInterface.getNetworkInterfaces())) {

//                LOG.info("iface : " + iface.getName());

                if (iface.isLoopback()) {
                    continue;
                }
                if (iface.isPointToPoint()) {
                    continue;
                }

//                LOG.info("iface : " + iface.getName());
                if (!iface.getName().equals("wlan0") && !iface.getName().equals("eth0")) {
                    continue;
                }
//                LOG.info("iface : " + iface.getName());
                try {
                    final byte[] ifaceMacAddress = iface.getHardwareAddress();
                    if ((ifaceMacAddress != null) && (ifaceMacAddress.length == 6) && !isBlockedHardwareAddress(ifaceMacAddress)) {
                        LOG.info("Hardware address is " + toHexString(ifaceMacAddress) + " (" + iface.getDisplayName() + ")");
                        return Arrays.copyOfRange(ifaceMacAddress, 0, 6);
                    }
                } catch (final Throwable e) {
                    /* Ignore */
                }
            }
        } catch (final Throwable e) {
            /* Ignore */
        }

//		/* Fallback to the IP address padded to 6 bytes */
//        try {
//            final byte[] hostAddress = Arrays.copyOfRange(InetAddress.getLocalHost().getAddress(), 0, 6);
//            LOG.info("Hardware address is " + toHexString(hostAddress) + " (IP address)");
//            return hostAddress;
//        }
//        catch (final Throwable e) {
//			/* Ignore */
//        }
//
//		/* Fallback to a constant */
//        LOG.info("Hardware address is 00DEADBEEF00 (last resort)");
        return new byte[]{};
    }

    public static String getHardwareAddressString() {
        byte[] hardwareAddressBytes = getHardwareAddress();
        HardwareAddr = toHexString(hardwareAddressBytes);
        LOG.warning("HardwareAddr:" + HardwareAddr);
        return HardwareAddr;
    }

    private static void getIpaddr() {
        try {
            for (final NetworkInterface iface : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (iface.isLoopback()) {
                    continue;
                }
                if (iface.isPointToPoint()) {
                    continue;
                }
                if (!iface.isUp()) {
                    continue;
                }

                if (!iface.getName().equals("wlan0") && !iface.getName().equals("eth0")) {
                    continue;
                }

                for (final InetAddress addr : Collections.list(iface.getInetAddresses())) {
                    LOG.warning("addr:" + addr);
                    if (addr instanceof Inet4Address) {
                        ip4addr = addr;
                        continue;
                    }
                    if (addr instanceof Inet6Address) {
                        ip6Addr = addr;
                        continue;
                    }

                }
            }

            LOG.warning("ipv4addr:" + ip4addr);
            LOG.warning("ipv6addr:" + ip6Addr);
        } catch (SocketException e) {
            LOG.warning("Failed to get ipv4addr and ipv6addr" + e);
        }


    }

    /**
     * The AirTunes/RAOP service type
     */
    static final String AIR_TUNES_SERVICE_TYPE = "_raop._tcp.local.";

    /**
     * The AirTunes/RAOP M-DNS service properties (TXT record)
     */
    static final Map<String, String> AIRTUNES_SERVICE_PROPERTIES = map(
            "ek", "1",
            "sm", "false",
            "vs", "130.14",
            "md", "0,1,2",
            "tp", "TCP,UDP",
            "vn", "3",
            "pw", "false",
            "ss", "16",
            "sr", "44100",
            "da", "true",
            "sv", "false",
            "et", "0,1",
            "cn", "0,1",
            "ch", "2",
            "txtvers", "1"
    );

    /**
     * Map factory. Creates a Map from a list of keys and values
     *
     * @param keys_values key1, value1, key2, value2, ...
     * @return a map mapping key1 to value1, key2 to value2, ...
     */
    private static Map<String, String> map(final String... keys_values) {
        assert keys_values.length % 2 == 0;
        final Map<String, String> map = new java.util.HashMap<String, String>(keys_values.length / 2);
        for (int i = 0; i < keys_values.length; i += 2)
            map.put(keys_values[i], keys_values[i + 1]);
        return Collections.unmodifiableMap(map);
    }

    private static JmDNS jmDns;

    public static synchronized boolean registerAirplayService(int ServerPort, String MdnsName) {
        getIpaddr();
        getHardwareAddressString();

        if (null == ip4addr && null == ip6Addr) {
            LOG.info("ip4addr or ip6Addr is null");
            return false;
        }

        if (HardwareAddr.isEmpty()) {
            LOG.info("HardwareAddr is empty");
            return false;
        }

        try {
            jmDns = JmDNS.create(ip4addr, MdnsName);
        } catch (IOException e) {
            e.printStackTrace();
            return false;
        }
        LOG.info("create is  sucess");
        if (null == jmDns) {
            LOG.warning("jmdns is  null");
            return false;
        }

        /* Publish RAOP service */
        final ServiceInfo airTunesServiceInfo = ServiceInfo.create(
                AIR_TUNES_SERVICE_TYPE,
                HardwareAddr + "@" + MdnsName,
                ServerPort,
                0 /* weight */, 0 /* priority */,
                AIRTUNES_SERVICE_PROPERTIES
        );

        try {
            jmDns.registerService(airTunesServiceInfo);
        } catch (IOException e) {
            e.printStackTrace();
            return false;
        }
        LOG.info("Registered AirTunes service " + airTunesServiceInfo.getName() + " on " + ip4addr.toString());

        return true;
    }

    public static synchronized void unRegisterAirplayService() {
        if (null != jmDns) {
            jmDns.unregisterAllServices();
            try {
                jmDns.close();
            } catch (IOException e) {
                e.printStackTrace();
            }
            jmDns = null;
        }

        LOG.info("call unRegisterAirplayService");
        return;
    }

}
