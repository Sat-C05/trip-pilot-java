package com.trippilot;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import com.trippilot.config.TripPilotProperties;
import com.trippilot.model.ModelAdapter;
import com.trippilot.research.*;
import com.trippilot.retrieval.SearchProvider;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.*;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

@EnabledIfEnvironmentVariable(named="PHASE1_DB_TESTS",matches="true")
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT)
@DirtiesContext(classMode=DirtiesContext.ClassMode.AFTER_CLASS)
class ResearchWorkerDatabaseTest {
    @Value("${local.server.port}") int port;
    @Autowired ResearchRepository repository;
    @Autowired ResearchExecutor executor;
    @Autowired RestartRecovery recovery;
    @Autowired TripPilotProperties properties;
    @Autowired DataSource dataSource;
    @MockitoBean SearchProvider search;
    @MockitoBean ModelAdapter model;

    @Test void sixJobsShareExactlyThreeWorkersAndSecondInstanceIsRejected() throws Exception {
        when(search.id()).thenReturn("wikivoyage-v1");
        var active=new AtomicInteger();var maximum=new AtomicInteger();var entered=new CountDownLatch(3);var release=new CountDownLatch(1);
        when(search.search(anyString(),anyInt(),anyInt(),anyInt(),any())).thenAnswer(invocation->{
            maximum.accumulateAndGet(active.incrementAndGet(),Math::max);entered.countDown();
            try { if(!release.await(30,TimeUnit.SECONDS)) throw new ResearchFailure("SYNTHETIC_TIMEOUT"); }
            finally { active.decrementAndGet(); }
            return List.of();
        });
        try(var browser=new ResearchTestBrowser(port)) {
            browser.signIn();UUID session=browser.session();UUID revision=browser.revision(session);var jobs=new ArrayList<UUID>();
            try {
                for(int i=0;i<6;i++) jobs.add(browser.job(session,revision,UUID.randomUUID().toString()));
                assertTrue(entered.await(20,TimeUnit.SECONDS));
                assertEquals(3,active.get());assertEquals(3,maximum.get());
                assertEquals(3,jobs.stream().filter(id->repository.read(browser.owner,id).status()==JobState.RUNNING).count());
                assertEquals(3,jobs.stream().filter(id->repository.read(browser.owner,id).status()==JobState.QUEUED).count());
                var second=new ResearchDispatcher(dataSource,repository,executor,recovery,properties);
                try { assertThrows(IllegalStateException.class,second::start); } finally { second.stop(); }
            } finally { release.countDown(); }
            long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(20);
            while(jobs.stream().anyMatch(id->!ResearchRepository.terminal(repository.read(browser.owner,id).status())) && System.nanoTime()<deadline) Thread.sleep(100);
            for(UUID id:jobs) { var job=repository.read(browser.owner,id);assertEquals(JobState.FAILED,job.status());assertEquals("EMPTY_SEARCH",job.outcomeCode());assertEquals(1,job.searches()); }
            assertEquals(3,maximum.get());verifyNoInteractions(model);
        }
    }
}
