package com.trippilot.presentation;

import com.trippilot.research.ResearchRepository;
import java.security.Principal;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import org.springframework.jdbc.core.JdbcTemplate;

@RestController
public class ResearchEventsController {
    private final ResearchRepository repository;
    private final JdbcTemplate jdbc;
    private final Semaphore subscriptions=new Semaphore(64);
    private final ScheduledExecutorService scheduler=Executors.newSingleThreadScheduledExecutor(Thread.ofPlatform().daemon().name("research-events").factory());
    public ResearchEventsController(ResearchRepository repository,JdbcTemplate jdbc) { this.repository=repository;this.jdbc=jdbc; }
    @jakarta.annotation.PreDestroy public void close() { scheduler.shutdownNow(); }

    @GetMapping(value="/api/research/jobs/{id}/events",produces=MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter subscribe(Principal principal,@PathVariable UUID id,@RequestHeader(value="Last-Event-ID",required=false) String lastId) {
        String owner=principal.getName(); repository.read(owner,id);
        long after=0;
        if(lastId!=null) {
            try { after=Long.parseLong(lastId); if(after<0) throw new NumberFormatException(); }
            catch(NumberFormatException invalid) { throw new ApiException(HttpStatus.BAD_REQUEST,"INVALID_EVENT_CURSOR","Last-Event-ID must be a non-negative event ID."); }
        }
        if(after>0 && jdbc.queryForObject("SELECT COUNT(*) FROM research_events WHERE id=? AND job_id=? AND owner_id=?",Integer.class,after,id.toString(),owner)==0)
            throw new ApiException(HttpStatus.CONFLICT,"EVENT_CURSOR_UNAVAILABLE","Reconnect without Last-Event-ID to read retained events.");
        if(!subscriptions.tryAcquire()) throw new ApiException(HttpStatus.TOO_MANY_REQUESTS,"STREAM_LIMIT","Too many event subscriptions.");
        var emitter=new SseEmitter(30000L);
        var cursor=new AtomicLong(after);
        var closed=new AtomicBoolean();
        var timer=new AtomicReference<ScheduledFuture<?>>();
        Runnable cleanup=()-> {
            if(closed.compareAndSet(false,true)) { subscriptions.release(); var scheduled=timer.get(); if(scheduled!=null) scheduled.cancel(false); }
        };
        emitter.onCompletion(cleanup);emitter.onTimeout(()-> { cleanup.run();emitter.complete(); });emitter.onError(error->cleanup.run());
        timer.set(scheduler.scheduleWithFixedDelay(()-> {
            if(closed.get()) return;
            try {
                var events=repository.events(owner,id,cursor.get());
                for(var event:events) {
                    emitter.send(SseEmitter.event().id(Long.toString(event.id())).name(event.type()).data(event));cursor.set(event.id());
                }
                if(ResearchRepository.terminal(repository.read(owner,id).status()) && events.size()<100) { cleanup.run();emitter.complete(); }
                else if(events.isEmpty()) emitter.send(SseEmitter.event().comment("heartbeat"));
            } catch(Exception ignored) { cleanup.run();emitter.complete(); }
        },0,500,TimeUnit.MILLISECONDS));
        if(closed.get()) timer.get().cancel(false);
        return emitter;
    }
}
