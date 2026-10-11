package com.trippilot.retrieval;

import java.net.*;
import java.util.Locale;
import static com.trippilot.retrieval.RetrievalFailure.Code.*;

/** Resolve at the connection boundary; the validated addresses are the ones used by the socket. */
public class PublicUrlPolicy {
    @FunctionalInterface public interface Resolver { InetAddress[] resolve(String host) throws UnknownHostException; }
    private final Resolver resolver;
    public PublicUrlPolicy() { this(InetAddress::getAllByName); }
    public PublicUrlPolicy(Resolver resolver) { this.resolver = resolver; }

    public URI validate(URI uri) {
        if (uri == null || uri.toString().length() > 2048 || uri.getHost() == null || uri.getUserInfo() != null
                || uri.getFragment() != null || !("http".equals(uri.getScheme()) || "https".equals(uri.getScheme()))
                || (uri.getPort() != -1 && uri.getPort() != ("https".equals(uri.getScheme()) ? 443 : 80))) throw new RetrievalFailure(UNSAFE_URL);
        String host = uri.getHost().toLowerCase(Locale.ROOT);
        if (host.equals("localhost") || host.endsWith(".localhost") || host.endsWith(".local")
                || host.endsWith(".internal") || host.endsWith(".") || host.contains("%")) throw new RetrievalFailure(UNSAFE_URL);
        return uri.normalize();
    }

    public InetAddress[] resolvePublic(String host) throws UnknownHostException {
        InetAddress[] addresses = resolver.resolve(host);
        if (addresses.length == 0) throw new UnknownHostException("No addresses");
        for (var address : addresses) if (!isPublic(address)) throw new RetrievalFailure(UNSAFE_URL);
        return addresses;
    }

    public static boolean isPublic(InetAddress address) {
        if (address.isAnyLocalAddress() || address.isLoopbackAddress() || address.isLinkLocalAddress()
                || address.isSiteLocalAddress() || address.isMulticastAddress()) return false;
        byte[] b = address.getAddress();
        int a = b[0] & 255, c = b[1] & 255;
        if (b.length == 4) {
            return !(a == 0 || a == 10 || a == 127 || a >= 224 || (a == 100 && c >= 64 && c <= 127)
                    || (a == 169 && c == 254) || (a == 172 && c >= 16 && c <= 31)
                    || (a == 192 && (c == 168 || c == 0 || (c == 88 && (b[2] & 255) == 99)))
                    || (a == 198 && (c == 18 || c == 19 || (c == 51 && (b[2] & 255) == 100)))
                    || (a == 203 && c == 0 && (b[2] & 255) == 113));
        }
        // Only native global unicast; block mapped/tunnel/documentation ranges as well.
        return (a & 0xe0) == 0x20 && !(a == 0x20 && c == 2)
                && !(a == 0x20 && c == 1 && ((b[2] == 0 && b[3] == 0) || ((b[2] & 255) == 13 && (b[3] & 255) == 184)));
    }
}
