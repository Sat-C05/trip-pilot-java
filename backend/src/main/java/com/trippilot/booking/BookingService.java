package com.trippilot.booking;

import static com.trippilot.booking.BookingContracts.*;
import com.trippilot.config.TripPilotProperties;
import com.trippilot.planning.TravelRequirements;
import com.trippilot.presentation.ApiException;
import java.math.BigDecimal;
import java.net.URI;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

@Service
public class BookingService {
    public record StoredQuote(UUID id,UUID contextId,UUID revisionId,Quote quote) {}
    public record Attempt(UUID id,UUID quoteId,UUID contextId,String state,BigDecimal ceiling,Instant authorizedUntil,String bookingId,String outcome) {}
    private record Context(UUID session,UUID revision,TravelRequirements requirements) {}
    private record Row(Attempt attempt,String key,String hold,URI endpoint,Quote quote,UUID revision) {}
    private final JdbcTemplate jdbc;
    private final TripPilotProperties properties;
    private final BookingProvider provider;
    private final TransactionTemplate tx;
    private final JsonMapper json=JsonMapper.builder().findAndAddModules().build();
    public BookingService(JdbcTemplate jdbc,TripPilotProperties properties,BookingProvider provider,PlatformTransactionManager manager){this.jdbc=jdbc;this.properties=properties;this.provider=provider;tx=new TransactionTemplate(manager);}
    public boolean enabled(){return properties.booking().enabled();}
    private void gate(){require(enabled(),HttpStatus.FORBIDDEN,"BOOKING_DISABLED","Booking capability is disabled.");}
    private static void require(boolean valid,HttpStatus status,String code,String message){if(!valid) throw new ApiException(status,code,message);}
    private Context context(String owner,UUID id,boolean lock){
        var sessions=jdbc.queryForList("SELECT session_id FROM trip_contexts WHERE id=? AND owner_id=? AND kind='SELECTED'",String.class,id.toString(),owner);
        require(!sessions.isEmpty(),HttpStatus.NOT_FOUND,"NOT_FOUND","Selected trip not found.");
        if(lock) jdbc.queryForObject("SELECT id FROM planning_sessions WHERE id=? AND owner_id=? FOR UPDATE",String.class,sessions.getFirst(),owner);
        var revisions=jdbc.queryForList("SELECT id,requirements FROM request_revisions WHERE context_id=? AND owner_id=? ORDER BY revision_no DESC LIMIT 1",id.toString(),owner);
        var r=revisions.getFirst();return new Context(UUID.fromString(sessions.getFirst()),UUID.fromString((String)r.get("id")),json.readValue((String)r.get("requirements"),TravelRequirements.class));
    }
    private Scope scope(TravelRequirements r){
        require(r.arrival()!=null && r.departureDate()!=null && r.travelers()!=null && r.budget()!=null && r.currency()!=null && r.destinationIntent()!=null && r.departure()!=null && !r.departure().isBlank(),HttpStatus.CONFLICT,"BOOKING_DETAILS_REQUIRED","Save departure, dates, travelers and budget in this trip before requesting a quote.");
        require(!r.arrival().isBefore(LocalDate.now(ZoneOffset.UTC)) && r.departureDate().isAfter(r.arrival()) && ChronoUnit.DAYS.between(r.arrival(),r.departureDate())<=30,HttpStatus.CONFLICT,"BOOKING_DATE_SCOPE","Use future dates for a stay of at most 30 nights.");
        return new Scope(r.destinationIntent(),r.country(),r.arrival(),r.departureDate(),r.travelers(),r.currency());
    }
    private static void validQuote(Quote q,Scope scope,String item){
        require(q!=null && q.id()!=null && q.id().matches("[A-Za-z0-9._:-]{1,100}") && Objects.equals(q.itemId(),item) && Objects.equals(q.scope(),scope) && q.name()!=null && q.name().length()<=200 && q.policy()!=null && q.policy().length()<=2000 && q.total()!=null && q.total().signum()>0 && q.total().scale()<=2 && q.total().compareTo(new BigDecimal("9999999999.99"))<=0 && q.expiresAt()!=null && q.expiresAt().isAfter(Instant.now()) && q.expiresAt().isBefore(Instant.now().plusSeconds(900)),HttpStatus.CONFLICT,"INVALID_QUOTE","Provider quote did not pass scope, amount or expiry validation.");
    }
    public Inventory inventory(String owner,UUID trip){gate();var scope=scope(context(owner,trip,false).requirements());var inventory=provider.inventory(properties.booking().providerBaseUrl(),scope);
        require(inventory!=null && scope.equals(inventory.scope()) && inventory.items()!=null && inventory.items().size()<=100 && inventory.items().stream().allMatch(i->i!=null && i.id()!=null && i.id().matches("[A-Za-z0-9._:-]{1,100}") && i.name()!=null && i.name().length()<=200 && scope.destination().equalsIgnoreCase(i.destination()) && scope.currency().equals(i.currency()) && i.nightlyPerTraveler()!=null && i.nightlyPerTraveler().signum()>0 && i.remaining()>=0),HttpStatus.CONFLICT,"INVALID_INVENTORY","Provider inventory did not pass validation.");return inventory;}
    public StoredQuote quote(String owner,UUID trip,String item){gate();var context=context(owner,trip,false);var q=provider.quote(properties.booking().providerBaseUrl(),new QuoteRequest(scope(context.requirements()),item));validQuote(q,scope(context.requirements()),item);
        return tx.execute(status->{var current=context(owner,trip,true);require(current.revision().equals(context.revision()),HttpStatus.CONFLICT,"TRIP_CHANGED","Trip details changed. Request a new quote.");UUID id=UUID.randomUUID();jdbc.update("INSERT INTO booking_quotes(id,context_id,session_id,owner_id,revision_id,provider_url,quote) VALUES(?,?,?,?,?,?,?)",id.toString(),trip.toString(),context.session().toString(),owner,context.revision().toString(),properties.booking().providerBaseUrl().toString(),json.writeValueAsString(q));return new StoredQuote(id,trip,context.revision(),q);});}
    private Row row(String owner,UUID id,boolean lock){
        var rows=jdbc.query("SELECT a.*,q.provider_url,q.quote,q.revision_id FROM booking_attempts a JOIN booking_quotes q ON q.id=a.quote_id WHERE a.id=? AND a.owner_id=?"+(lock?" FOR UPDATE":""),(rs,n)->new Row(new Attempt(UUID.fromString(rs.getString("id")),UUID.fromString(rs.getString("quote_id")),UUID.fromString(rs.getString("context_id")),rs.getString("state"),rs.getBigDecimal("ceiling"),rs.getTimestamp("authorized_until").toInstant(),rs.getString("booking_id"),rs.getString("outcome")),rs.getString("provider_key"),rs.getString("hold_id"),URI.create(rs.getString("provider_url")),json.readValue(rs.getString("quote"),Quote.class),UUID.fromString(rs.getString("revision_id"))),id.toString(),owner);
        require(!rows.isEmpty(),HttpStatus.NOT_FOUND,"NOT_FOUND","Booking attempt not found.");return rows.getFirst();
    }
    public List<Attempt> attempts(String owner,UUID trip){context(owner,trip,false);return jdbc.queryForList("SELECT id FROM booking_attempts WHERE owner_id=? AND context_id=? ORDER BY created_at,id",String.class,owner,trip.toString()).stream().map(id->row(owner,UUID.fromString(id),false).attempt()).toList();}
    public StoredQuote readQuote(String owner,UUID id){var rows=jdbc.query("SELECT context_id,revision_id,quote FROM booking_quotes WHERE id=? AND owner_id=?",(rs,n)->new StoredQuote(id,UUID.fromString(rs.getString(1)),UUID.fromString(rs.getString(2)),json.readValue(rs.getString(3),Quote.class)),id.toString(),owner);require(!rows.isEmpty(),HttpStatus.NOT_FOUND,"NOT_FOUND","Quote not found.");return rows.getFirst();}
    public Attempt read(String owner,UUID id){return row(owner,id,false).attempt();}
    public Attempt authorize(String owner,UUID trip,UUID quoteId,BigDecimal ceiling,Instant requestedUntil,String requestKey){gate();var until=requestedUntil.truncatedTo(ChronoUnit.MICROS);return tx.execute(status->{
        var context=context(owner,trip,true);var old=jdbc.queryForList("SELECT id FROM booking_attempts WHERE owner_id=? AND request_key=?",String.class,owner,requestKey);
        if(!old.isEmpty()){var previous=row(owner,UUID.fromString(old.getFirst()),false).attempt();require(previous.contextId().equals(trip) && previous.quoteId().equals(quoteId) && previous.ceiling().compareTo(ceiling)==0 && previous.authorizedUntil().equals(until),HttpStatus.CONFLICT,"IDEMPOTENCY_CONFLICT","Authorization key was used for another scope.");return previous;}
        require(jdbc.queryForObject("SELECT COUNT(*) FROM booking_attempts WHERE owner_id=? AND context_id=? AND state IN ('AUTHORIZED','SUBMITTING','UNCERTAIN','CONFIRMED')",Integer.class,owner,trip.toString())==0,HttpStatus.CONFLICT,"BOOKING_ALREADY_ACTIVE","This trip already has an authorization, unresolved submission or confirmed stay.");
        var quotes=jdbc.queryForList("SELECT quote,revision_id FROM booking_quotes WHERE id=? AND context_id=? AND owner_id=?",quoteId.toString(),trip.toString(),owner);
        require(!quotes.isEmpty(),HttpStatus.NOT_FOUND,"NOT_FOUND","Quote not found.");var saved=quotes.getFirst();var q=json.readValue((String)saved.get("quote"),Quote.class);
        require(jdbc.queryForObject("SELECT COUNT(*) FROM booking_attempts WHERE quote_id=?",Integer.class,quoteId.toString())==0,HttpStatus.CONFLICT,"QUOTE_ALREADY_AUTHORIZED","Request a new quote after revoking or rejecting a previous authorization.");
        require(context.revision().toString().equals(saved.get("revision_id")),HttpStatus.CONFLICT,"TRIP_CHANGED","Trip details changed. Request a new quote.");validQuote(q,scope(context.requirements()),q.itemId());
        require(ceiling.signum()>0 && ceiling.scale()<=2 && ceiling.compareTo(context.requirements().budget())<=0 && ceiling.compareTo(q.total())>=0,HttpStatus.CONFLICT,"COST_CEILING","The quote must fit your explicit ceiling and saved trip budget.");
        require(until.isAfter(Instant.now()) && !until.isAfter(q.expiresAt()) && !until.isAfter(Instant.now().plusSeconds(600)) && until.getNano()%1000==0,HttpStatus.CONFLICT,"AUTHORIZATION_EXPIRED","Authorization must end within the quote expiry and ten minutes.");
        UUID id=UUID.randomUUID();jdbc.update("INSERT INTO booking_attempts(id,quote_id,context_id,owner_id,request_key,ceiling,authorized_until,state,provider_key) VALUES(?,?,?,?,?,?,?,'AUTHORIZED',?)",id.toString(),quoteId.toString(),trip.toString(),owner,requestKey,ceiling,java.sql.Timestamp.from(until),UUID.randomUUID().toString());return row(owner,id,false).attempt();});}
    private void update(UUID id,String state,String hold,String booking,String outcome){jdbc.update("UPDATE booking_attempts SET state=?,hold_id=COALESCE(?,hold_id),booking_id=COALESCE(?,booking_id),outcome=?,updated_at=CURRENT_TIMESTAMP(6) WHERE id=? AND state IN ('SUBMITTING','UNCERTAIN')",state,hold,booking,outcome,id.toString());}
    public Attempt revoke(String owner,UUID id){return tx.execute(status->{var row=row(owner,id,true);require(row.attempt().state().equals("AUTHORIZED") || row.attempt().state().equals("REVOKED"),HttpStatus.CONFLICT,"ALREADY_SUBMITTED","Only an unsubmitted authorization can be revoked.");jdbc.update("UPDATE booking_attempts SET state='REVOKED',outcome='USER_REVOKED' WHERE id=? AND state='AUTHORIZED'",id.toString());return read(owner,id);});}
    public Attempt execute(String owner,UUID id){gate();
        boolean claimed=Boolean.TRUE.equals(tx.execute(status->{var row=row(owner,id,false);var context=context(owner,row.attempt().contextId(),true);row=row(owner,id,true);
            if(!row.attempt().state().equals("AUTHORIZED")) return false;
            if(!row.attempt().authorizedUntil().isAfter(Instant.now()) || !row.quote().expiresAt().isAfter(Instant.now()) || !context.revision().equals(row.revision())) {jdbc.update("UPDATE booking_attempts SET state='REJECTED',outcome='AUTHORIZATION_EXPIRED_OR_CHANGED' WHERE id=?",id.toString());return false;}
            require(scope(context.requirements()).equals(row.quote().scope()) && row.quote().total().compareTo(row.attempt().ceiling())<=0 && row.attempt().ceiling().compareTo(context.requirements().budget())<=0,HttpStatus.CONFLICT,"COST_CEILING","Saved scope or spending ceiling no longer matches.");
            jdbc.update("UPDATE booking_attempts SET state='SUBMITTING',updated_at=CURRENT_TIMESTAMP(6) WHERE id=?",id.toString());return true;}));
        if(!claimed) return read(owner,id);
        var row=row(owner,id,false);try {
            var fresh=provider.readQuote(row.endpoint(),row.quote().id());validQuote(fresh,row.quote().scope(),row.quote().itemId());
            require(fresh.equals(row.quote()),HttpStatus.CONFLICT,"PRICE_CHANGED","Quote changed. Request and authorize a new quote.");
            var hold=provider.hold(row.endpoint(),new HoldRequest(row.quote().id(),row.key()));
            require(hold!=null && hold.id()!=null && hold.id().matches("[A-Za-z0-9._:-]{1,100}") && row.quote().id().equals(hold.quoteId()) && row.quote().total().compareTo(hold.total())==0 && hold.expiresAt()!=null && hold.expiresAt().isAfter(Instant.now()),HttpStatus.CONFLICT,"INVALID_HOLD","Hold scope, total or expiry failed validation.");
            update(id,"SUBMITTING",hold.id(),null,null);
            // Recheck saved user scope after external calls; commit the stable key before submission.
            boolean allowed=Boolean.TRUE.equals(tx.execute(status->{var current=context(owner,row.attempt().contextId(),true);var saved=row(owner,id,true);if(!current.revision().equals(row.revision()) || !saved.attempt().authorizedUntil().isAfter(Instant.now()) || !hold.expiresAt().isAfter(Instant.now()) || !row.quote().expiresAt().isAfter(Instant.now())){update(id,"REJECTED",null,null,"AUTHORIZATION_EXPIRED_OR_CHANGED");return false;}return true;}));
            if(!allowed) return read(owner,id);
            accept(row,provider.submit(row.endpoint(),new Submit(row.quote().id(),hold.id(),row.key())));
        } catch(BookingProvider.Failure failure){update(id,failure.definitive?"REJECTED":"UNCERTAIN",null,null,failure.code);}catch(ApiException invalid){update(id,"REJECTED",null,null,invalid.code());}catch(Exception unknown){update(id,"UNCERTAIN",null,null,"PROVIDER_INVALID_RESPONSE");}
        return read(owner,id);
    }
    private void accept(Row row,Status response){
        boolean valid=response!=null && row.key().equals(response.key()) && "CONFIRMED".equals(response.state()) && response.bookingId()!=null && response.bookingId().matches("[A-Za-z0-9._:-]{1,100}") && row.quote().id().equals(response.quoteId()) && response.total()!=null && response.total().compareTo(row.quote().total())==0 && row.quote().scope().currency().equals(response.currency());
        if(valid) update(row.attempt().id(),"CONFIRMED",null,response.bookingId(),"PROVIDER_CONFIRMED");else update(row.attempt().id(),"UNCERTAIN",null,null,"PROVIDER_INVALID_RESPONSE");
    }
    public Attempt reconcile(String owner,UUID id){var row=row(owner,id,false);if(!Set.of("SUBMITTING","UNCERTAIN").contains(row.attempt().state())) return row.attempt();
        try {var response=provider.status(row.endpoint(),row.key());if(response!=null && "CONFIRMED".equals(response.state())) accept(row,response);else update(id,"UNCERTAIN",null,null,"STATUS_UNRESOLVED");}catch(Exception unavailable){update(id,"UNCERTAIN",null,null,"STATUS_UNAVAILABLE");}return read(owner,id);}
}
