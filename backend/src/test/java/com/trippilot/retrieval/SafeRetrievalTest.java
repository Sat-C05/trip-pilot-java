package com.trippilot.retrieval;

import static org.junit.jupiter.api.Assertions.*;
import static com.trippilot.retrieval.RetrievalFailure.Code.*;
import com.sun.net.httpserver.HttpServer;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.*;

class SafeRetrievalTest {
    @Test void privateMixedAndSpecialAddressesAreBlocked() throws Exception {
        for(String address:List.of("0.0.0.0","10.0.0.1","127.0.0.1","169.254.169.254","172.16.0.1","192.168.1.1","100.64.0.1","198.18.1.1","192.0.2.1","198.51.100.1","203.0.113.1","224.0.0.1","::1","fc00::1","fe80::1","::ffff:127.0.0.1","2001:db8::1","2002:7f00:1::"))
            assertFalse(PublicUrlPolicy.isPublic(InetAddress.getByName(address)),address);
        for(String address:List.of("93.184.216.34","8.8.8.8","2606:4700:4700::1111")) assertTrue(PublicUrlPolicy.isPublic(InetAddress.getByName(address)),address);
        var mixed=new PublicUrlPolicy(host->new InetAddress[]{InetAddress.getByName("8.8.8.8"),InetAddress.getByName("127.0.0.1")});
        assertEquals(UNSAFE_URL,assertThrows(RetrievalFailure.class,()->mixed.resolvePublic("public.example")).code());
        var policy=new PublicUrlPolicy();
        for(String url:List.of("file:///etc/passwd","http://user:secret@example.com","http://localhost","http://example.local","https://example.com:8080","https://example.com/#fragment"))
            assertEquals(UNSAFE_URL,assertThrows(RetrievalFailure.class,()->policy.validate(URI.create(url))).code());
    }

    @Test void safeTransportBoundsRedirectBytesTypesDeadlinesAndDnsResolution() throws Exception {
        var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        var threads=Executors.newCachedThreadPool();server.setExecutor(threads);
        int port=server.getAddress().getPort();var resolutions=new AtomicInteger();
        // Explicit in-test routing only; production has no configurable private-network bypass.
        var policy=new PublicUrlPolicy() {
            @Override public URI validate(URI uri) {
                if("public.example".equals(uri.getHost()) && uri.getPort()==port) return uri;
                return super.validate(uri);
            }
            @Override public InetAddress[] resolvePublic(String host) throws UnknownHostException {
                resolutions.incrementAndGet();
                if(!host.equals("public.example")) throw new RetrievalFailure(UNSAFE_URL);
                return new InetAddress[]{InetAddress.getByName("127.0.0.1")};
            }
        };
        server.createContext("/",exchange->{
            String path=exchange.getRequestURI().getPath();String body="<html><title>Travel</title><main>Saved evidence<script>secret injection</script></main></html>";
            if(path.equals("/private")) { exchange.getResponseHeaders().set("Location","http://127.0.0.1:"+port+"/");exchange.sendResponseHeaders(302,-1);exchange.close();return; }
            if(path.equals("/loop")) { exchange.getResponseHeaders().set("Location","/loop");exchange.sendResponseHeaders(302,-1);exchange.close();return; }
            if(path.equals("/slow")) try { Thread.sleep(1500); } catch(InterruptedException ignored) { Thread.currentThread().interrupt(); }
            if(path.equals("/large")) body="x".repeat(10000);
            exchange.getResponseHeaders().set("Content-Type",path.equals("/binary") ? "application/octet-stream" : "text/html; charset=utf-8");
            byte[] bytes=body.getBytes(StandardCharsets.UTF_8);
            try { exchange.sendResponseHeaders(path.equals("/error") ? 403 : 200,0);exchange.getResponseBody().write(bytes); }
            finally { exchange.close(); }
        });server.start();var web=new SafeWebClient(policy);
        try {
            var base="http://public.example:"+port;
            var page=web.get(URI.create(base+"/"),20000,5,()->false);
            assertEquals(1,resolutions.get(),"A request pins one DNS resolution through the connection");
            var extracted=new PageExtractor().extract(page,1000);
            assertEquals("Saved evidence",extracted.text()); assertEquals(64,extracted.hash().length());
            assertEquals(UNSAFE_URL,assertThrows(RetrievalFailure.class,()->web.get(URI.create(base+"/private"),20000,5,()->false)).code());
            assertEquals(REDIRECT_LIMIT,assertThrows(RetrievalFailure.class,()->web.get(URI.create(base+"/loop"),20000,5,()->false)).code());
            assertEquals(TOO_LARGE,assertThrows(RetrievalFailure.class,()->web.get(URI.create(base+"/large"),1024,5,()->false)).code());
            assertEquals(UNSUPPORTED_TYPE,assertThrows(RetrievalFailure.class,()->web.get(URI.create(base+"/binary"),20000,5,()->false)).code());
            assertEquals(HTTP_FAILURE,assertThrows(RetrievalFailure.class,()->web.get(URI.create(base+"/error"),20000,5,()->false)).code());
            assertEquals(DEADLINE,assertThrows(RetrievalFailure.class,()->web.get(URI.create(base+"/slow"),20000,1,()->false)).code());
            assertEquals(CANCELLED,assertThrows(RetrievalFailure.class,()->web.get(URI.create(base+"/"),20000,5,()->true)).code());
        } finally { web.close();server.stop(0);threads.shutdownNow(); }
    }
    @Test void emptyAndJsonAreNotPageEvidence() {
        var extractor=new PageExtractor();
        var uri=URI.create("https://example.org/");
        assertEquals(EMPTY_CONTENT,assertThrows(RetrievalFailure.class,()->extractor.extract(new SafeWebClient.Page(uri,uri,java.time.Instant.now(),200,"text/html","<script>only script</script>".getBytes(StandardCharsets.UTF_8)),1000)).code());
        assertEquals(UNSUPPORTED_TYPE,assertThrows(RetrievalFailure.class,()->extractor.extract(new SafeWebClient.Page(uri,uri,java.time.Instant.now(),200,"application/json","{}".getBytes(StandardCharsets.UTF_8)),1000)).code());
    }
    @Test void stalledDnsIsBoundedAndCancelledQueuedRequestsAreRemoved() throws Exception {
        var entered=new CountDownLatch(3);var release=new CountDownLatch(1);var started=new AtomicInteger();
        var policy=new PublicUrlPolicy() {
            @Override public InetAddress[] resolvePublic(String host) throws UnknownHostException {
                started.incrementAndGet();entered.countDown();
                while(release.getCount()>0) try { release.await(); } catch(InterruptedException ignored) { /* Simulate an uninterruptible platform DNS call. */ }
                throw new UnknownHostException("Synthetic stalled resolver");
            }
        };
        var web=new SafeWebClient(policy);var callers=Executors.newFixedThreadPool(3);
        try {
            var futures=new java.util.ArrayList<Future<?>>();
            for(int i=0;i<3;i++) futures.add(callers.submit(()->assertEquals(DEADLINE,assertThrows(RetrievalFailure.class,()->web.get(URI.create("https://example.org/"),1024,1,()->false)).code())));
            assertTrue(entered.await(2,TimeUnit.SECONDS));for(var future:futures) future.get(3,TimeUnit.SECONDS);
            for(int i=0;i<4;i++) assertEquals(DEADLINE,assertThrows(RetrievalFailure.class,()->web.get(URI.create("https://example.org/"),1024,1,()->false)).code());
            assertEquals(3,started.get(),"Cancelled requests queued behind stuck DNS must never execute");
        } finally { release.countDown();web.close();callers.shutdownNow(); }
    }
}
