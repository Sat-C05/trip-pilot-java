package com.trippilot.research;

import java.sql.Connection;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.sql.DataSource;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import com.trippilot.config.TripPilotProperties;

/** Three application-wide workers; MySQL named lock rejects a second dispatcher instance. */
@Component
@ConditionalOnProperty(name="trippilot.dispatch.enabled",havingValue="true",matchIfMissing=true)
public class ResearchDispatcher {
    private final DataSource dataSource;
    private final ResearchRepository repository;
    private final ResearchExecutor executor;
    private final RestartRecovery recovery;
    private final ExecutorService workers;
    private final UUID instance=UUID.randomUUID();
    private final AtomicBoolean stopping=new AtomicBoolean();
    private Connection ownership;
    public ResearchDispatcher(DataSource dataSource,ResearchRepository repository,ResearchExecutor executor,RestartRecovery recovery,TripPilotProperties properties) {
        this.dataSource=dataSource;this.repository=repository;this.executor=executor;this.recovery=recovery;
        workers=Executors.newFixedThreadPool(properties.research().workers(),Thread.ofPlatform().name("research-worker-",0).factory());
    }
    @EventListener(ApplicationReadyEvent.class)
    public void start() throws Exception {
        ownership=dataSource.getConnection();
        try(var statement=ownership.prepareStatement("SELECT GET_LOCK(CONCAT('trippilot-research-',DATABASE()),0)")) {
            try(var result=statement.executeQuery()) {
                if(!result.next() || result.getInt(1)!=1) { ownership.close(); ownership=null; throw new IllegalStateException("Another research dispatcher owns this database"); }
            }
        }
        recovery.interruptOrphanedRuns();
        // Use one submitted long-lived loop per configured worker; never queue jobs in memory.
        int count=((ThreadPoolExecutor)workers).getCorePoolSize();
        for(int i=0;i<count;i++) workers.submit(this::loop);
    }
    private void loop() {
        while(!stopping.get()) {
            try {
                if(ownership==null || !ownership.isValid(2)) { stopping.set(true); workers.shutdownNow(); return; }
                var run=repository.claim(instance);
                if(run==null) Thread.sleep(500); else executor.execute(run,instance,stopping::get);
            } catch(InterruptedException ignored) { Thread.currentThread().interrupt(); return;
            } catch(Exception failure) {
                // No raw SQL/provider bodies in application logs. Recovery remains durable on restart.
                org.slf4j.LoggerFactory.getLogger(getClass()).warn("Research worker failed; durable job retained for recovery");
                try { Thread.sleep(500); } catch(InterruptedException ignored) { Thread.currentThread().interrupt(); return; }
            }
        }
    }
    @jakarta.annotation.PreDestroy public void stop() throws Exception {
        stopping.set(true);workers.shutdownNow();
        if(workers.awaitTermination(5,TimeUnit.SECONDS) && ownership!=null) {
            try(var statement=ownership.prepareStatement("SELECT RELEASE_LOCK(CONCAT('trippilot-research-',DATABASE()))")) { statement.execute(); }
            ownership.close(); ownership=null;
        }
    }
}
