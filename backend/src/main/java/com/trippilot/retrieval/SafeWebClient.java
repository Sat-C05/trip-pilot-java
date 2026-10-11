package com.trippilot.retrieval;

import static com.trippilot.retrieval.RetrievalFailure.Code.*;
import java.io.ByteArrayOutputStream;
import java.net.*;
import java.time.Instant;
import java.util.concurrent.*;
import java.util.function.BooleanSupplier;
import org.apache.hc.client5.http.DnsResolver;
import org.apache.hc.client5.http.classic.methods.HttpGet;
import org.apache.hc.client5.http.config.ConnectionConfig;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManagerBuilder;
import org.apache.hc.core5.http.ContentType;
import org.apache.hc.core5.util.Timeout;
import org.springframework.stereotype.Component;

@Component
public class SafeWebClient {
    public record Page(URI requestedUrl, URI finalUrl, Instant retrievedAt, int status, String contentType, byte[] bytes) {
        public String text() {
            try {
                var charset = ContentType.parse(contentType).getCharset();
                return new String(bytes, charset == null ? java.nio.charset.StandardCharsets.UTF_8 : charset);
            } catch (Exception ignored) { throw new RetrievalFailure(UNSUPPORTED_TYPE); }
        }
    }
    private final PublicUrlPolicy policy;
    private final ThreadPoolExecutor io = new ThreadPoolExecutor(3,3,0,TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<>(3),Thread.ofPlatform().daemon().name("public-web-",0).factory(),new ThreadPoolExecutor.AbortPolicy());
    public SafeWebClient() { this(new PublicUrlPolicy()); }
    public SafeWebClient(PublicUrlPolicy policy) { this.policy = policy; }
    @jakarta.annotation.PreDestroy public void close() { io.shutdownNow(); }

    public Page get(URI url, int byteLimit, int seconds, BooleanSupplier cancelled) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds);
        URI current = policy.validate(url);
        for (int redirects = 0; redirects <= 3; redirects++) {
            check(cancelled, deadline);
            HttpGet request = new HttpGet(current);
            request.setHeader("User-Agent", "TripPilot/0.1 (college travel research; local development)");
            request.setHeader("Accept", "text/html, text/plain, application/json");
            request.setHeader("Accept-Encoding", "identity");
            URI target = current;
            Future<Raw> future;
            try { future = io.submit(() -> exchange(request, byteLimit, seconds)); }
            catch(RejectedExecutionException saturated) { throw new RetrievalFailure(HTTP_FAILURE); }
            Raw response;
            try {
                while (true) {
                    check(cancelled, deadline);
                    try { response = future.get(50, TimeUnit.MILLISECONDS); break; }
                    catch (TimeoutException ignored) { }
                }
            } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); throw new RetrievalFailure(CANCELLED);
            } catch (ExecutionException error) {
                if (error.getCause() instanceof RetrievalFailure failure) throw failure;
                if (error.getCause() instanceof UnknownHostException) throw new RetrievalFailure(DNS_FAILURE);
                if (error.getCause() instanceof java.net.SocketTimeoutException) throw new RetrievalFailure(DEADLINE);
                throw new RetrievalFailure(HTTP_FAILURE);
            } finally { if (!future.isDone()) { request.cancel(); future.cancel(true); io.remove((Runnable)future); } }
            if (response.status >= 300 && response.status < 400) {
                if (response.location == null) throw new RetrievalFailure(HTTP_FAILURE);
                try { current = policy.validate(target.resolve(response.location)); }
                catch (IllegalArgumentException error) { throw new RetrievalFailure(UNSAFE_URL); }
                if ("https".equals(target.getScheme()) && !"https".equals(current.getScheme())) throw new RetrievalFailure(UNSAFE_URL);
                continue;
            }
            if (response.status != 200) throw new RetrievalFailure(HTTP_FAILURE);
            check(cancelled, deadline);
            return new Page(url, target, Instant.now(), response.status, response.contentType, response.bytes);
        }
        throw new RetrievalFailure(REDIRECT_LIMIT);
    }

    private record Raw(int status, String location, String contentType, byte[] bytes) {}
    private Raw exchange(HttpGet request, int limit, int seconds) throws Exception {
        String targetHost = request.getUri().getHost();
        InetAddress[] pinned = policy.resolvePublic(targetHost);
        // No shared connection cache or proxy: resolution validates every socket connection.
        DnsResolver resolver = new DnsResolver() {
            public InetAddress[] resolve(String host) throws UnknownHostException {
                if (!targetHost.equalsIgnoreCase(host)) throw new UnknownHostException("Unexpected host");
                return pinned.clone();
            }
            public String resolveCanonicalHostname(String host) { return host; }
        };
        try (var manager = PoolingHttpClientConnectionManagerBuilder.create().setDnsResolver(resolver)
                .setDefaultConnectionConfig(ConnectionConfig.custom().setConnectTimeout(Timeout.ofSeconds(seconds))
                        .setSocketTimeout(Timeout.ofSeconds(seconds)).build()).build();
             var client = HttpClients.custom().setConnectionManager(manager).disableRedirectHandling().disableContentCompression()
                     .disableAutomaticRetries().disableCookieManagement().setDefaultRequestConfig(RequestConfig.custom()
                             .setResponseTimeout(Timeout.ofSeconds(seconds)).setConnectionRequestTimeout(Timeout.ofSeconds(seconds)).build()).build()) {
            return client.execute(request, response -> {
                int status = response.getCode();
                var location = response.getFirstHeader("Location");
                if (status != 200) return new Raw(status, location == null ? null : location.getValue(), "", new byte[0]);
                var type = response.getFirstHeader("Content-Type");
                String contentType = type == null ? "" : type.getValue();
                String mime = contentType.split(";", 2)[0].strip().toLowerCase(java.util.Locale.ROOT);
                if (!java.util.Set.of("text/html", "text/plain", "application/json", "application/xhtml+xml").contains(mime)
                        || contentType.length() > 120 || response.getFirstHeader("Content-Encoding") != null) throw new RetrievalFailure(UNSUPPORTED_TYPE);
                var entity = response.getEntity();
                if (entity == null) throw new RetrievalFailure(EMPTY_CONTENT);
                if (entity.getContentLength() > limit) throw new RetrievalFailure(TOO_LARGE);
                try (var input = entity.getContent(); var output = new ByteArrayOutputStream()) {
                    byte[] buffer = new byte[8192]; int count;
                    while ((count = input.read(buffer)) != -1) {
                        if (output.size() + count > limit) throw new RetrievalFailure(TOO_LARGE);
                        output.write(buffer, 0, count);
                    }
                    return new Raw(status, null, contentType, output.toByteArray());
                }
            });
        }
    }
    private static void check(BooleanSupplier cancelled, long deadline) {
        if (cancelled.getAsBoolean() || Thread.currentThread().isInterrupted()) throw new RetrievalFailure(CANCELLED);
        if (System.nanoTime() >= deadline) throw new RetrievalFailure(DEADLINE);
    }
}
