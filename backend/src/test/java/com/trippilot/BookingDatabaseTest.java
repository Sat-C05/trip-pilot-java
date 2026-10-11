package com.trippilot;

import static org.junit.jupiter.api.Assertions.*;
import static com.trippilot.booking.BookingContracts.*;
import com.trippilot.booking.*;
import com.trippilot.config.TripPilotProperties;
import com.trippilot.planning.TravelRequirements;
import com.trippilot.research.ResearchRepository;
import com.trippilot.presentation.ApiException;
import com.jayway.jsonpath.JsonPath;
import java.math.BigDecimal;
import java.net.URI;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.*;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import tools.jackson.databind.json.JsonMapper;

@EnabledIfEnvironmentVariable(named="PHASE5_PROVIDER_TESTS",matches="true")
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties={"trippilot.dispatch.enabled=false","trippilot.booking.enabled=true","trippilot.booking.provider-base-url=${PHASE5_PROVIDER_URL}"})
class BookingDatabaseTest {
    @Value("${local.server.port}") int port;
    @Autowired JdbcTemplate jdbc;
    @Autowired ResearchRepository research;
    @Autowired BookingService service;
    @Autowired BookingProvider provider;
    @Autowired TripPilotProperties properties;
    @Autowired PlatformTransactionManager manager;
    final JsonMapper json=JsonMapper.builder().findAndAddModules().build();
    ResearchTestBrowser browser() throws Exception {var b=new ResearchTestBrowser(port);b.signIn();return b;}
    static final java.util.concurrent.atomic.AtomicInteger dateOffset=new java.util.concurrent.atomic.AtomicInteger();
    TravelRequirements requirements(){var arrival=LocalDate.now(ZoneOffset.UTC).plusDays(180+dateOffset.getAndIncrement()*3L);return new TravelRequirements("Delhi","Jaipur","IN",arrival,arrival.plusDays(2),new BigDecimal("50000.00"),"INR",2,List.of());}
    UUID trip(ResearchTestBrowser b) throws Exception {
        var fixture=new PlanningExperienceDatabaseTest();fixture.repository=research;fixture.jdbc=jdbc;UUID session=b.session();UUID trip=fixture.select(b,session,fixture.completed(b,session,"Jaipur"));research.revise(b.owner,session,trip,requirements());return trip;
    }
    BookingService.StoredQuote quote(ResearchTestBrowser b,UUID trip){return service.quote(b.owner,trip,"jaipur-stay");}
    BookingService.Attempt authorize(BookingService target,ResearchTestBrowser b,UUID trip,BookingService.StoredQuote q){return target.authorize(b.owner,trip,q.id(),q.quote().total(),q.quote().expiresAt(),UUID.randomUUID().toString());}
    void code(String code,org.junit.jupiter.api.function.Executable action){assertEquals(code,assertThrows(ApiException.class,action).code());}
    BookingService alternate(BookingProvider adapter,boolean enabled){return new BookingService(jdbc,new TripPilotProperties(properties.research(),properties.model(),new TripPilotProperties.Booking(enabled,properties.booking().providerBaseUrl())),adapter,manager);}
    class Delegate implements BookingProvider {
        public Inventory inventory(URI endpoint,Scope scope){return provider.inventory(endpoint,scope);}
        public Quote quote(URI endpoint,QuoteRequest request){return provider.quote(endpoint,request);}
        public Quote readQuote(URI endpoint,String id){return provider.readQuote(endpoint,id);}
        public Hold hold(URI endpoint,HoldRequest request){return provider.hold(endpoint,request);}
        public Status submit(URI endpoint,Submit request){return provider.submit(endpoint,request);}
        public Status status(URI endpoint,String key){return provider.status(endpoint,key);}
    }
    @Test void realProviderAuthorizationBudgetIsolationCsrfAndConcurrentDuplicate() throws Exception {
        try(var b=browser();var other=browser();var threads=Executors.newVirtualThreadPerTaskExecutor()) {
            UUID trip=trip(b);assertFalse(service.inventory(b.owner,trip).items().isEmpty());var q=quote(b,trip);
            code("COST_CEILING",()->service.authorize(b.owner,trip,q.id(),new BigDecimal("3999.99"),q.quote().expiresAt(),"low"));
            code("COST_CEILING",()->service.authorize(b.owner,trip,q.id(),new BigDecimal("50000.01"),q.quote().expiresAt(),"high"));
            String payload=json.writeValueAsString(Map.of("quoteId",q.id(),"ceiling","4000.00","authorizedUntil",q.quote().expiresAt(),"requestKey","missing-consent","accepted",false));
            assertEquals(400,b.post("/api/booking/trips/"+trip+"/authorizations",payload).statusCode());
            assertEquals(403,b.call("POST","/api/booking/trips/"+trip+"/authorizations",payload,false,"application/json",null).statusCode());
            assertEquals(404,other.post("/api/booking/trips/"+trip+"/quotes","{\"itemId\":\"jaipur-stay\"}").statusCode());
            var attempt=authorize(service,b,trip,q);
            assertEquals(404,other.get("/api/booking/attempts/"+attempt.id()).statusCode());assertEquals(404,other.get("/api/booking/quotes/"+q.id()).statusCode());
            assertEquals(404,other.post("/api/booking/attempts/"+attempt.id()+"/execute","{}").statusCode());
            var first=threads.submit(()->service.execute(b.owner,attempt.id()));var second=threads.submit(()->service.execute(b.owner,attempt.id()));first.get();second.get();
            var confirmed=service.read(b.owner,attempt.id());assertEquals("CONFIRMED",confirmed.state());assertNotNull(confirmed.bookingId());assertEquals(confirmed,service.execute(b.owner,attempt.id()));assertEquals(confirmed,service.reconcile(b.owner,attempt.id()));
            code("BOOKING_ALREADY_ACTIVE",()->authorize(service,b,trip,quote(b,trip)));
        }
    }
    @Test void expiredAuthorizationAndChangedRevisionPreventProviderSubmission() throws Exception {
        try(var b=browser()) {
            UUID trip=trip(b);var q=quote(b,trip);var a=authorize(service,b,trip,q);
            jdbc.update("UPDATE booking_attempts SET authorized_until=? WHERE id=?",java.sql.Timestamp.from(Instant.now().minusSeconds(1)),a.id().toString());
            assertEquals("REJECTED",service.execute(b.owner,a.id()).state());assertEquals("NOT_FOUND",provider.status(properties.booking().providerBaseUrl(),jdbc.queryForObject("SELECT provider_key FROM booking_attempts WHERE id=?",String.class,a.id().toString())).state());
            var next=authorize(service,b,trip,quote(b,trip));UUID session=UUID.fromString(jdbc.queryForObject("SELECT session_id FROM trip_contexts WHERE id=?",String.class,trip.toString()));research.revise(b.owner,session,trip,requirements());assertEquals("REJECTED",service.execute(b.owner,next.id()).state());
        }
    }
    @Test void lostSuccessfulResponseReconcilesAcrossServiceRestartWithoutResubmit() throws Exception {
        try(var b=browser()) {
            UUID trip=trip(b);var q=quote(b,trip);var calls=new java.util.concurrent.atomic.AtomicInteger();
            var unreliable=alternate(new Delegate(){public Status submit(URI endpoint,Submit request){calls.incrementAndGet();super.submit(endpoint,request);throw new BookingProvider.Failure("PROVIDER_UNAVAILABLE",false);}},true);
            var a=authorize(unreliable,b,trip,q);assertEquals("UNCERTAIN",unreliable.execute(b.owner,a.id()).state());assertEquals("UNCERTAIN",unreliable.execute(b.owner,a.id()).state());assertEquals(1,calls.get());
            code("BOOKING_ALREADY_ACTIVE",()->authorize(service,b,trip,quote(b,trip)));
            var restarted=alternate(provider,false);var recovered=restarted.reconcile(b.owner,a.id());assertEquals("CONFIRMED",recovered.state());assertEquals(1,calls.get());assertEquals(recovered,service.reconcile(b.owner,a.id()));
        }
    }
    @Test void actualHttpTimeoutAfterCreationReconcilesOneDurableBooking() throws Exception {
        var proxy=com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1",0),0);var threads=Executors.newVirtualThreadPerTaskExecutor();proxy.setExecutor(threads);var submissions=new java.util.concurrent.atomic.AtomicInteger();var release=new CountDownLatch(1);
        proxy.createContext("/v1/",exchange->{
            try {
                var body=exchange.getRequestBody().readAllBytes();var request=java.net.http.HttpRequest.newBuilder(URI.create(properties.booking().providerBaseUrl()+exchange.getRequestURI().toString())).timeout(Duration.ofSeconds(10)).method(exchange.getRequestMethod(),body.length==0?java.net.http.HttpRequest.BodyPublishers.noBody():java.net.http.HttpRequest.BodyPublishers.ofByteArray(body)).header("Content-Type","application/json").build();
                var response=java.net.http.HttpClient.newHttpClient().send(request,java.net.http.HttpResponse.BodyHandlers.ofByteArray());
                if(exchange.getRequestMethod().equals("POST") && exchange.getRequestURI().getPath().equals("/v1/bookings")){submissions.incrementAndGet();release.await(12,TimeUnit.SECONDS);return;}
                exchange.getResponseHeaders().set("Content-Type","application/json");exchange.sendResponseHeaders(response.statusCode(),response.body().length);exchange.getResponseBody().write(response.body());
            }catch(Exception failed){}finally{exchange.close();}
        });proxy.start();
        try(var b=browser()) {
            UUID trip=trip(b);var q=quote(b,trip);jdbc.update("UPDATE booking_quotes SET provider_url=? WHERE id=?","http://127.0.0.1:"+proxy.getAddress().getPort(),q.id().toString());
            var a=authorize(service,b,trip,q);assertEquals("UNCERTAIN",service.execute(b.owner,a.id()).state());assertEquals(1,submissions.get());assertEquals("CONFIRMED",service.reconcile(b.owner,a.id()).state());assertEquals("CONFIRMED",service.execute(b.owner,a.id()).state());assertEquals(1,submissions.get());
        }finally{release.countDown();proxy.stop(0);threads.close();}
    }
    @Test void unknownStatusNeverClearsUncertaintyOrResubmits() throws Exception {
        try(var b=browser()) {
            UUID trip=trip(b);var q=quote(b,trip);var unknown=alternate(new Delegate(){public Status submit(URI endpoint,Submit request){throw new BookingProvider.Failure("PROVIDER_UNAVAILABLE",false);}},true);
            var a=authorize(unknown,b,trip,q);assertEquals("UNCERTAIN",unknown.execute(b.owner,a.id()).state());assertEquals("UNCERTAIN",service.reconcile(b.owner,a.id()).state());code("BOOKING_ALREADY_ACTIVE",()->authorize(service,b,trip,quote(b,trip)));
        }
    }
    @Test void disabledWritesAndMissingAuthorizationMakeNoProviderCalls() throws Exception {
        var disabled=alternate(new Delegate(){public Inventory inventory(URI endpoint,Scope scope){fail("Disabled operation contacted provider");return null;}},false);
        code("BOOKING_DISABLED",()->disabled.inventory("unknown",UUID.randomUUID()));code("BOOKING_DISABLED",()->disabled.execute("unknown",UUID.randomUUID()));code("NOT_FOUND",()->service.execute("unknown",UUID.randomUUID()));
        try(var b=browser()){assertEquals(403,b.get("/api/booking/submit").statusCode());assertEquals(403,b.call("POST","/api/booking/attempts/"+UUID.randomUUID()+"/execute","{}",false,"application/json",null).statusCode());}
    }
    @Test void providerWrongScopePriceChangeAndRejectionFailClosed() throws Exception {
        try(var b=browser()) {
            UUID trip=trip(b);var invalid=alternate(new Delegate(){public Quote quote(URI endpoint,QuoteRequest request){var q=super.quote(endpoint,request);return new Quote(q.id(),q.itemId(),q.name(),new Scope("Goa","IN",q.scope().arrival(),q.scope().departure(),2,"INR"),q.total(),q.expiresAt(),q.policy());}},true);
            code("INVALID_QUOTE",()->invalid.quote(b.owner,trip,"jaipur-stay"));
            var q=quote(b,trip);var a=authorize(service,b,trip,q);var changed=alternate(new Delegate(){public Quote readQuote(URI endpoint,String id){var old=super.readQuote(endpoint,id);return new Quote(old.id(),old.itemId(),old.name(),old.scope(),old.total().add(BigDecimal.ONE),old.expiresAt(),old.policy());}},true);
            assertEquals("REJECTED",changed.execute(b.owner,a.id()).state());assertEquals("PRICE_CHANGED",service.read(b.owner,a.id()).outcome());
            var sold=alternate(new Delegate(){public Hold hold(URI endpoint,HoldRequest request){throw new BookingProvider.Failure("SOLD_OUT",true);}},true);var next=authorize(sold,b,trip,quote(b,trip));assertEquals("REJECTED",sold.execute(b.owner,next.id()).state());assertEquals("SOLD_OUT",service.read(b.owner,next.id()).outcome());
        }
    }
    @Test void idempotentAuthorizationRevocationAndQuoteExpiry() throws Exception {
        try(var b=browser()) {
            UUID trip=trip(b);var q=quote(b,trip);String key=UUID.randomUUID().toString();var a=service.authorize(b.owner,trip,q.id(),q.quote().total(),q.quote().expiresAt(),key);
            assertEquals(a,service.authorize(b.owner,trip,q.id(),q.quote().total(),q.quote().expiresAt(),key));code("IDEMPOTENCY_CONFLICT",()->service.authorize(b.owner,trip,q.id(),new BigDecimal("4500"),q.quote().expiresAt(),key));
            assertEquals("REVOKED",service.revoke(b.owner,a.id()).state());assertEquals("REVOKED",service.execute(b.owner,a.id()).state());
            var next=quote(b,trip);jdbc.update("UPDATE booking_quotes SET quote=JSON_SET(quote,'$.expiresAt',?) WHERE id=?",Instant.now().minusSeconds(1).toString(),next.id().toString());code("INVALID_QUOTE",()->authorize(service,b,trip,next));
        }
    }
    @Test @EnabledIfEnvironmentVariable(named="PHASE5_BROWSER_TESTS",matches="true")
    void productionBrowserAuthorizesSubmitsAndReloadsSavedState() throws Exception {
        try(var b=browser()){
            UUID trip=trip(b);String session=jdbc.queryForObject("SELECT session_id FROM trip_contexts WHERE id=?",String.class,trip.toString());
            var output=java.nio.file.Path.of("../runtime-data/phase5/browser-"+UUID.randomUUID()).toAbsolutePath();java.nio.file.Files.createDirectories(output);
            var process=new ProcessBuilder("node","../scripts/verify-booking-ui.cjs",Integer.toString(port),b.email,output.toString(),trip.toString(),session).redirectErrorStream(true).redirectOutput(output.resolve("browser.log").toFile()).start();
            try{assertTrue(process.waitFor(90,TimeUnit.SECONDS),"Booking browser timeout");assertEquals(0,process.exitValue(),java.nio.file.Files.readString(output.resolve("browser.log")));assertEquals("CONFIRMED",service.attempts(b.owner,trip).getFirst().state());}finally{if(process.isAlive())process.destroyForcibly();}
        }
    }
}
