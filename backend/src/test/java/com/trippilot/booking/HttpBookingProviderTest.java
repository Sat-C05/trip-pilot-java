package com.trippilot.booking;

import static org.junit.jupiter.api.Assertions.*;
import static com.trippilot.booking.BookingContracts.*;
import com.sun.net.httpserver.HttpServer;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;

class HttpBookingProviderTest {
    @Test void redirectsOversizeMalformedContentAndInvalidEndpointsFailClosed() throws Exception {
        var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/v1/bookings/redirect",exchange->{exchange.getResponseHeaders().set("Location","http://127.0.0.1:1/secret");exchange.sendResponseHeaders(302,-1);exchange.close();});
        server.createContext("/v1/bookings/large",exchange->{exchange.getResponseHeaders().set("Content-Type","application/json");byte[] body=new byte[65537];exchange.sendResponseHeaders(200,body.length);exchange.getResponseBody().write(body);exchange.close();});
        server.createContext("/v1/bookings/html",exchange->{exchange.getResponseHeaders().set("Content-Type","text/html");byte[] body="<html>not status</html>".getBytes(StandardCharsets.UTF_8);exchange.sendResponseHeaders(200,body.length);exchange.getResponseBody().write(body);exchange.close();});
        server.start();var provider=new HttpBookingProvider();var uri=URI.create("http://127.0.0.1:"+server.getAddress().getPort());
        try{for(String key:new String[]{"redirect","large","html"}){var failure=assertThrows(BookingProvider.Failure.class,()->provider.status(uri,key));assertFalse(failure.definitive);}assertThrows(BookingProvider.Failure.class,()->provider.status(URI.create("file:/tmp/provider"),"key"));assertThrows(BookingProvider.Failure.class,()->provider.status(uri,"../escape"));}finally{server.stop(0);}
    }
    @Test void knownProviderRejectionIsDefinitiveAndServerErrorsAreUncertain() throws Exception {
        var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/v1/holds",exchange->{exchange.getRequestBody().readAllBytes();byte[] body="{\"code\":\"SOLD_OUT\"}".getBytes(StandardCharsets.UTF_8);exchange.getResponseHeaders().set("Content-Type","application/json");exchange.sendResponseHeaders(409,body.length);exchange.getResponseBody().write(body);exchange.close();});
        server.createContext("/v1/bookings",exchange->{exchange.getRequestBody().readAllBytes();byte[] body="{\"code\":\"PROVIDER_UNAVAILABLE\"}".getBytes(StandardCharsets.UTF_8);exchange.sendResponseHeaders(503,body.length);exchange.getResponseBody().write(body);exchange.close();});server.start();
        try{var p=new HttpBookingProvider();var uri=URI.create("http://127.0.0.1:"+server.getAddress().getPort());var sold=assertThrows(BookingProvider.Failure.class,()->p.hold(uri,new HoldRequest("q","k")));assertTrue(sold.definitive);assertEquals("SOLD_OUT",sold.code);assertFalse(assertThrows(BookingProvider.Failure.class,()->p.submit(uri,new Submit("q","h","k"))).definitive);}finally{server.stop(0);}
    }
    @Test void stalledProviderTimesOutWithinBound() throws Exception {
        var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);var release=new CountDownLatch(1);var threads=Executors.newVirtualThreadPerTaskExecutor();server.setExecutor(threads);
        server.createContext("/v1/bookings/slow",exchange->{try{release.await(12,TimeUnit.SECONDS);}catch(InterruptedException e){Thread.currentThread().interrupt();}finally{exchange.close();}});server.start();
        var start=System.nanoTime();try{assertThrows(BookingProvider.Failure.class,()->new HttpBookingProvider().status(URI.create("http://127.0.0.1:"+server.getAddress().getPort()),"slow"));assertTrue(Duration.ofNanos(System.nanoTime()-start).toSeconds()<9);}finally{release.countDown();server.stop(0);threads.close();}
    }
}
