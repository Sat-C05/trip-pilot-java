package com.trippilot.booking;

import static com.trippilot.booking.BookingContracts.*;
import java.net.*;
import java.net.http.*;
import java.time.Duration;
import java.util.Set;
import java.util.concurrent.*;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/** Explicitly configured provider transport, separate from general public-page retrieval. */
@Component
public class HttpBookingProvider implements BookingProvider {
    private final HttpClient http=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).followRedirects(HttpClient.Redirect.NEVER).build();
    private final JsonMapper json=JsonMapper.builder().findAndAddModules().build();
    private static final Set<String> ERRORS=Set.of("QUOTE_EXPIRED","HOLD_EXPIRED","PRICE_CHANGED","SOLD_OUT","INVALID_SCOPE","INVALID_ITEM","INVALID_HOLD","IDEMPOTENCY_CONFLICT","QUOTE_NOT_FOUND","INVALID_KEY","INVALID_REQUEST");
    private <T>T call(URI endpoint,String path,Object body,Class<T> type) {
        if(endpoint==null || !Set.of("http","https").contains(endpoint.getScheme()) || endpoint.getHost()==null || endpoint.getUserInfo()!=null || endpoint.getQuery()!=null || endpoint.getFragment()!=null) throw new Failure("PROVIDER_CONFIGURATION",false);
        var request=HttpRequest.newBuilder(URI.create(endpoint.toString().replaceAll("/$","")+"/v1/"+path)).timeout(Duration.ofSeconds(5)).header("Accept","application/json");
        if(body==null) request.GET();else request.header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)));
        // Bounded subscriber terminates oversized responses before buffering more than 64 KiB.
        var future=http.sendAsync(request.build(),info->new BoundedBody());
        try {
            var response=future.get(6,TimeUnit.SECONDS);
            if(response.statusCode()!=200) {
                String code=json.readTree(response.body()).path("code").asText();
                boolean definitive=response.statusCode()>=400 && response.statusCode()<500 && ERRORS.contains(code);
                throw new Failure(definitive?code:"PROVIDER_UNAVAILABLE",definitive);
            }
            if(!response.headers().firstValue("Content-Type").orElse("").startsWith("application/json")) throw new Failure("PROVIDER_INVALID_RESPONSE",false);
            return json.readValue(response.body(),type);
        } catch(Failure failure){throw failure;}catch(InterruptedException interrupted){Thread.currentThread().interrupt();throw new Failure("PROVIDER_UNAVAILABLE",false);}catch(Exception failure){throw new Failure("PROVIDER_UNAVAILABLE",false);}finally{future.cancel(true);}
    }
    private static String id(String id) {if(id==null || !id.matches("[A-Za-z0-9._:-]{1,100}")) throw new Failure("PROVIDER_INVALID_RESPONSE",false);return id;}
    public Inventory inventory(URI endpoint,Scope scope){return call(endpoint,"inventory",scope,Inventory.class);}
    public Quote quote(URI endpoint,QuoteRequest request){return call(endpoint,"quotes",request,Quote.class);}
    public Quote readQuote(URI endpoint,String id){return call(endpoint,"quotes/"+id(id),null,Quote.class);}
    public Hold hold(URI endpoint,HoldRequest request){return call(endpoint,"holds",request,Hold.class);}
    public Status submit(URI endpoint,Submit request){return call(endpoint,"bookings",request,Status.class);}
    public Status status(URI endpoint,String key){return call(endpoint,"bookings/"+id(key),null,Status.class);}
    private static final class BoundedBody implements HttpResponse.BodySubscriber<String> {
        private final CompletableFuture<String> result=new CompletableFuture<>();
        private final java.io.ByteArrayOutputStream bytes=new java.io.ByteArrayOutputStream();
        private Flow.Subscription subscription;
        public CompletionStage<String> getBody(){return result;}
        public void onSubscribe(Flow.Subscription subscription){this.subscription=subscription;subscription.request(1);}
        public void onNext(java.util.List<java.nio.ByteBuffer> buffers){
            for(var buffer:buffers){if(bytes.size()+buffer.remaining()>65536){subscription.cancel();result.completeExceptionally(new IllegalArgumentException("response too large"));return;}byte[] next=new byte[buffer.remaining()];buffer.get(next);bytes.writeBytes(next);}
            subscription.request(1);
        }
        public void onError(Throwable failure){result.completeExceptionally(failure);}
        public void onComplete(){result.complete(bytes.toString(java.nio.charset.StandardCharsets.UTF_8));}
    }
}
