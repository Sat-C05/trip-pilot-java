package com.trippilot.provider;

import static org.junit.jupiter.api.Assertions.*;
import static com.trippilot.provider.ProviderContracts.*;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.web.server.ResponseStatusException;

class ProviderStoreTest {
    @TempDir Path directory;
    @org.junit.jupiter.api.AfterEach void cleanOwnedTemporaryDirectory() throws Exception {
        // Windows can briefly retain a deleted atomic-write file while removing its parent.
        // Retry only cleanup of this JUnit-owned directory; never suppress a final cleanup failure.
        for(int attempt=0;attempt<100;attempt++) {
            try {
                if(java.nio.file.Files.exists(directory)) {
                    List<Path> paths;
                    try(var entries=java.nio.file.Files.walk(directory)){paths=entries.sorted(Comparator.reverseOrder()).toList();}
                    for(var path:paths) java.nio.file.Files.deleteIfExists(path);
                }
                return;
            } catch(java.io.IOException transientFailure) {
                if(attempt==99) throw transientFailure;
                Thread.sleep(20);
            }
        }
    }
    static class Time extends Clock {
        Instant now=Instant.parse("2026-10-11T00:00:00Z");
        public ZoneId getZone(){return ZoneOffset.UTC;}
        public Clock withZone(ZoneId zone){return this;}
        public Instant instant(){return now;}
        void advance(long seconds){now=now.plusSeconds(seconds);}
    }
    Scope scope(){return new Scope("Jaipur","IN",LocalDate.parse("2026-12-10"),LocalDate.parse("2026-12-12"),2,"INR");}
    Quote quote(ProviderStore store){return store.quote(new QuoteRequest(scope(),"jaipur-stay"));}
    void rejected(String code,org.junit.jupiter.api.function.Executable action){assertEquals(code,assertThrows(ResponseStatusException.class,action).getReason());}
    @Test void durableConfirmationAndDuplicateSubmitAfterRestart() throws Exception {
        var time=new Time();Path file=directory.resolve("ledger.json");Quote q;Hold h;Status booking;
        try(var store=new ProviderStore(file,time)){q=quote(store);assertEquals(new BigDecimal("4000.00"),q.total());h=store.hold(new HoldRequest(q.id(),"key1"));booking=store.submit(new Submit(q.id(),h.id(),"key1"));assertEquals("CONFIRMED",booking.state());assertEquals(4,store.inventory(scope()).items().getFirst().remaining());}
        time.advance(1000);
        try(var store=new ProviderStore(file,time)){assertEquals(booking,store.status("key1"));assertEquals(booking,store.submit(new Submit(q.id(),h.id(),"key1")));rejected("IDEMPOTENCY_CONFLICT",()->store.submit(new Submit("other",h.id(),"key1")));}
    }
    @Test void quoteAndHoldExpiryFailClosedAndReleaseInventory() throws Exception {
        var time=new Time();try(var store=new ProviderStore(directory.resolve("ledger.json"),time)){
            var q=quote(store);var h=store.hold(new HoldRequest(q.id(),"expiry"));time.advance(61);assertEquals(5,store.inventory(scope()).items().getFirst().remaining());rejected("HOLD_EXPIRED",()->store.submit(new Submit(q.id(),h.id(),"expiry")));assertEquals("NOT_FOUND",store.status("expiry").state());time.advance(60);rejected("QUOTE_EXPIRED",()->store.hold(new HoldRequest(q.id(),"another")));}
    }
    @Test void priceChangeAndSoldOutDoNotCreateBookings() throws Exception {
        try(var store=new ProviderStore(directory.resolve("ledger.json"),new Time())){var q=quote(store);store.price(new BigDecimal("1100.00"));rejected("PRICE_CHANGED",()->store.hold(new HoldRequest(q.id(),"change")));var fresh=quote(store);store.capacity(0);rejected("SOLD_OUT",()->store.hold(new HoldRequest(fresh.id(),"sold")));rejected("SOLD_OUT",()->quote(store));assertEquals("NOT_FOUND",store.status("sold").state());}
    }
    @Test void changedPriceAfterHoldStillRejectsSubmit() throws Exception {
        try(var store=new ProviderStore(directory.resolve("ledger.json"),new Time())){var q=quote(store);var h=store.hold(new HoldRequest(q.id(),"changed"));store.price(new BigDecimal("1100.00"));rejected("PRICE_CHANGED",()->store.submit(new Submit(q.id(),h.id(),"changed")));assertEquals("NOT_FOUND",store.status("changed").state());}
    }
    @Test void concurrentDuplicateAndCompetingHoldsRespectCapacity() throws Exception {
        try(var store=new ProviderStore(directory.resolve("ledger.json"),new Time());var threads=Executors.newVirtualThreadPerTaskExecutor()){
            store.capacity(1);var q=quote(store);var h=store.hold(new HoldRequest(q.id(),"duplicate"));assertEquals(h,store.hold(new HoldRequest(q.id(),"duplicate")));rejected("SOLD_OUT",()->store.hold(new HoldRequest(q.id(),"competing")));
            var futures=new ArrayList<Future<Status>>();for(int i=0;i<8;i++) futures.add(threads.submit(()->store.submit(new Submit(q.id(),h.id(),"duplicate"))));
            var first=futures.getFirst().get();for(var future:futures) assertEquals(first,future.get());assertEquals(0,store.inventory(scope()).items().getFirst().remaining());}
    }
    @Test void wrongScopeItemHoldAndKeyAreRejected() throws Exception {
        try(var store=new ProviderStore(directory.resolve("ledger.json"),new Time())){
            rejected("INVALID_SCOPE",()->store.inventory(new Scope("Jaipur","US",scope().arrival(),scope().departure(),2,"INR")));
            rejected("INVALID_ITEM",()->store.quote(new QuoteRequest(scope(),"goa-stay")));var q=quote(store);var h=store.hold(new HoldRequest(q.id(),"valid"));
            rejected("INVALID_HOLD",()->store.submit(new Submit(q.id(),h.id(),"wrong")));rejected("INVALID_KEY",()->store.status("../escape"));rejected("INVALID_REQUEST",()->store.submit(null));}
    }
    @Test void ledgerHasExclusiveOwnership() throws Exception {
        Path file=directory.resolve("ledger.json");try(var store=new ProviderStore(file,new Time())){assertThrows(IllegalStateException.class,()->new ProviderStore(file,new Time()));}
    }
    @Test void corruptLedgerFailureReleasesOwnershipWithoutRewritingData() throws Exception {
        Path file=directory.resolve("ledger.json");java.nio.file.Files.writeString(file,"invalid-json");
        assertThrows(IllegalStateException.class,()->new ProviderStore(file,new Time()));
        assertEquals("invalid-json",java.nio.file.Files.readString(file));
        java.nio.file.Files.delete(file); // Only this deliberately corrupt test fixture.
        try(var store=new ProviderStore(file,new Time())){assertEquals(5,store.inventory(scope()).items().getFirst().remaining());}
    }
}
