package com.trippilot.provider;

import static com.trippilot.provider.ProviderContracts.*;
import java.math.BigDecimal;
import java.nio.channels.*;
import java.nio.file.*;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.json.JsonMapper;

/** Single-process durable provider ledger, exclusive file lock and atomic write before acknowledgement. */
@Component
public class ProviderStore implements AutoCloseable {
    public record StoredQuote(Quote quote,BigDecimal rate) {}
    public record StoredHold(Hold hold,String key) {}
    public record Ledger(Map<String,StoredQuote> quotes,Map<String,StoredHold> holds,Map<String,Status> bookings) {}
    private final Path file;
    private final Clock clock;
    private final FileChannel lockChannel;
    private final FileLock lock;
    private final JsonMapper json=JsonMapper.builder().findAndAddModules().build();
    private Ledger ledger;
    private BigDecimal rate=new BigDecimal("1000.00");
    private int capacity=5;
    private static final Map<String,String> CATALOG=Map.of("jaipur-stay","Jaipur","udaipur-stay","Udaipur","agra-stay","Agra","goa-stay","Goa","manali-stay","Manali","rishikesh-stay","Rishikesh");
    @org.springframework.beans.factory.annotation.Autowired
    public ProviderStore(@Value("${provider.state-file:runtime-data/booking-demo/ledger.json}") String file) {this(Path.of(file),Clock.systemUTC());}
    ProviderStore(Path file,Clock clock) {
        this.file=file.toAbsolutePath();this.clock=clock;
        FileChannel opened=null;FileLock acquired=null;
        try {
            Files.createDirectories(this.file.getParent());
            opened=FileChannel.open(this.file.resolveSibling(this.file.getFileName()+".lock"),StandardOpenOption.CREATE,StandardOpenOption.WRITE);
            acquired=opened.tryLock();if(acquired==null) throw new IllegalStateException("Provider ledger already in use");
            ledger=Files.exists(this.file)?json.readValue(Files.readString(this.file),Ledger.class):new Ledger(new LinkedHashMap<>(),new LinkedHashMap<>(),new LinkedHashMap<>());
            lockChannel=opened;lock=acquired;
        } catch(Exception failure){
            try {if(acquired!=null) acquired.release();} catch(Exception cleanup){failure.addSuppressed(cleanup);}
            try {if(opened!=null) opened.close();} catch(Exception cleanup){failure.addSuppressed(cleanup);}
            throw new IllegalStateException("Provider ledger unavailable",failure);
        }
    }
    private static void require(boolean valid,String code) {if(!valid) throw new ResponseStatusException(HttpStatus.CONFLICT,code);}
    private void valid(Scope scope) {
        require(scope!=null && "IN".equals(scope.country()) && "INR".equals(scope.currency()) && scope.destination()!=null && scope.arrival()!=null && scope.departure()!=null,"INVALID_SCOPE");
        require(!scope.arrival().isBefore(LocalDate.now(clock)) && scope.departure().isAfter(scope.arrival()) && ChronoUnit.DAYS.between(scope.arrival(),scope.departure())<=30 && scope.travelers()>=1 && scope.travelers()<=20,"INVALID_SCOPE");
    }
    private void persist(Ledger next) {
        Path temporary=file.resolveSibling(file.getFileName()+".tmp");
        try {
            byte[] bytes=json.writeValueAsString(next).getBytes(java.nio.charset.StandardCharsets.UTF_8);
            try(var channel=FileChannel.open(temporary,StandardOpenOption.CREATE,StandardOpenOption.TRUNCATE_EXISTING,StandardOpenOption.WRITE)) {var buffer=java.nio.ByteBuffer.wrap(bytes);while(buffer.hasRemaining()) channel.write(buffer);channel.force(true);}
            Files.move(temporary,file,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);ledger=next;
        } catch(Exception failure){throw new IllegalStateException("Provider persistence failed; reconcile status before retry",failure);}
    }
    private Ledger copy() {return new Ledger(new LinkedHashMap<>(ledger.quotes()),new LinkedHashMap<>(ledger.holds()),new LinkedHashMap<>(ledger.bookings()));}
    private boolean overlaps(Scope a,Scope b) {return a.arrival().isBefore(b.departure()) && b.arrival().isBefore(a.departure());}
    private int remaining(String item,Scope scope,String excludedHold) {
        int count=0;
        for(var booking:ledger.bookings().values()) {var q=ledger.quotes().get(booking.quoteId()).quote();if(q.itemId().equals(item) && overlaps(q.scope(),scope)) count++;}
        for(var h:ledger.holds().values()) {
            var q=ledger.quotes().get(h.hold().quoteId()).quote();
            boolean consumed=ledger.bookings().containsKey(h.key());
            if(!consumed && !h.hold().id().equals(excludedHold) && h.hold().expiresAt().isAfter(clock.instant()) && q.itemId().equals(item) && overlaps(q.scope(),scope)) count++;
        }
        return Math.max(0,capacity-count);
    }
    public synchronized Inventory inventory(Scope scope) {
        valid(scope);return new Inventory(scope,CATALOG.entrySet().stream().filter(e->e.getValue().equalsIgnoreCase(scope.destination())).sorted(Map.Entry.comparingByKey()).map(e->new Item(e.getKey(),e.getValue()+" college stay",e.getValue(),"INR",rate,remaining(e.getKey(),scope,null))).toList());
    }
    public synchronized Quote quote(QuoteRequest request) {
        require(request!=null,"INVALID_REQUEST");valid(request.scope());
        require(CATALOG.containsKey(request.itemId()) && CATALOG.get(request.itemId()).equalsIgnoreCase(request.scope().destination()),"INVALID_ITEM");
        require(remaining(request.itemId(),request.scope(),null)>0,"SOLD_OUT");
        var scope=request.scope();var total=rate.multiply(BigDecimal.valueOf(ChronoUnit.DAYS.between(scope.arrival(),scope.departure())*scope.travelers()));
        var q=new Quote(UUID.randomUUID().toString(),request.itemId(),CATALOG.get(request.itemId())+" college stay",scope,total,clock.instant().plusSeconds(120),"All-inclusive stay only; no payment collected. Non-refundable after confirmation. No transport included.");
        var next=copy();next.quotes().put(q.id(),new StoredQuote(q,rate));persist(next);return q;
    }
    public synchronized Quote readQuote(String id) {var q=ledger.quotes().get(id);require(q!=null,"QUOTE_NOT_FOUND");return q.quote();}
    private Quote current(String id) {var q=readQuote(id);require(q.expiresAt().isAfter(clock.instant()),"QUOTE_EXPIRED");require(ledger.quotes().get(id).rate().compareTo(rate)==0,"PRICE_CHANGED");return q;}
    private void key(String key) {require(key!=null && key.matches("[A-Za-z0-9._:-]{1,100}"),"INVALID_KEY");}
    public synchronized Hold hold(HoldRequest request) {
        require(request!=null,"INVALID_REQUEST");key(request.key());
        for(var stored:ledger.holds().values()) if(stored.key().equals(request.key())) {require(stored.hold().quoteId().equals(request.quoteId()),"IDEMPOTENCY_CONFLICT");require(stored.hold().expiresAt().isAfter(clock.instant()),"HOLD_EXPIRED");return stored.hold();}
        var q=current(request.quoteId());require(remaining(q.itemId(),q.scope(),null)>0,"SOLD_OUT");
        var h=new Hold(UUID.randomUUID().toString(),q.id(),clock.instant().plusSeconds(60),q.total());var next=copy();next.holds().put(h.id(),new StoredHold(h,request.key()));persist(next);return h;
    }
    public synchronized Status submit(Submit request) {
        require(request!=null,"INVALID_REQUEST");key(request.key());
        var old=ledger.bookings().get(request.key());
        if(old!=null) {require(old.quoteId().equals(request.quoteId()) && ledger.holds().get(request.holdId())!=null && ledger.holds().get(request.holdId()).key().equals(request.key()),"IDEMPOTENCY_CONFLICT");return old;}
        var q=current(request.quoteId());var held=ledger.holds().get(request.holdId());
        require(held!=null && held.key().equals(request.key()) && held.hold().quoteId().equals(q.id()),"INVALID_HOLD");
        require(held.hold().expiresAt().isAfter(clock.instant()),"HOLD_EXPIRED");require(remaining(q.itemId(),q.scope(),request.holdId())>0,"SOLD_OUT");
        var s=new Status(request.key(),"CONFIRMED",UUID.randomUUID().toString(),q.id(),q.total(),q.scope().currency());var next=copy();next.bookings().put(request.key(),s);persist(next);return s;
    }
    public synchronized Status status(String key) {key(key);return ledger.bookings().getOrDefault(key,new Status(key,"NOT_FOUND",null,null,null,null));}
    // Package-only fixture controls; no remote administration or scenario logic in the backend.
    synchronized void price(BigDecimal next){rate=next;}
    synchronized void capacity(int next){capacity=next;}
    public void close() throws Exception {lock.release();lockChannel.close();}
}
