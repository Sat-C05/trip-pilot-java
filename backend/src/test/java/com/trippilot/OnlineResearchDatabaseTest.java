package com.trippilot;

import static org.junit.jupiter.api.Assertions.*;
import com.trippilot.research.*;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.*;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;

/** Real database + real internet + real pinned model + actual worker/SSE path. */
@EnabledIfEnvironmentVariable(named="PHASE2_LIVE_TESTS",matches="true")
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT)
@DirtiesContext(classMode=DirtiesContext.ClassMode.AFTER_CLASS)
class OnlineResearchDatabaseTest {
    @Value("${local.server.port}") int port;
    @Autowired ResearchRepository repository;
    @Test void liveEvidenceIsDurableAndStreamsWithinSnapshottedBudgets() throws Exception {
        try(var browser=new ResearchTestBrowser(port)) {
            browser.signIn();UUID session=browser.session();UUID revision=browser.revision(session);
            UUID id=browser.job(session,revision,UUID.randomUUID().toString());
            // Subscribe while queued/running and reconnect until a persisted terminal record exists.
            StringBuilder streamed=new StringBuilder();long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(120);
            do {
                var stream=browser.get("/api/research/jobs/"+id+"/events");assertEquals(200,stream.statusCode(),stream.body());streamed.append(stream.body());
            } while(!ResearchRepository.terminal(repository.read(browser.owner,id).status()) && System.nanoTime()<deadline);
            var job=repository.read(browser.owner,id);
            assertTrue(job.status()==JobState.SUCCEEDED || job.status()==JobState.PARTIAL,job.status()+" "+job.outcomeCode());
            var sources=repository.sources(browser.owner,id);assertFalse(sources.isEmpty());
            var result=repository.result(browser.owner,id);assertFalse(result.complete());assertEquals(7,result.fields().size());
            assertTrue(result.fields().stream().filter(f->f.field()==com.trippilot.validation.ValidatedResearch.Name.PRICE
                    || f.field()==com.trippilot.validation.ValidatedResearch.Name.AVAILABILITY || f.field()==com.trippilot.validation.ValidatedResearch.Name.POLICY)
                    .allMatch(f->f.state()==com.trippilot.validation.FieldState.UNAVAILABLE));
            for(var field:result.fields()) for(var citation:field.citations()) {
                var source=sources.stream().filter(s->s.id().equals(citation.sourceId())).findFirst().orElseThrow();
                assertEquals(source.finalUrl(),citation.url());assertTrue(source.text().contains(citation.quote()));
            }
            assertEquals(200,browser.get("/api/research/jobs/"+id+"/result").statusCode());
            assertTrue(sources.stream().anyMatch(source->source.text().contains("Jaipur")));
            assertTrue(job.searches()>=1 && job.searches()<=2);assertTrue(job.pages()>=1 && job.pages()<=6);assertTrue(job.modelTurns()>=1 && job.modelTurns()<=3);
            for(var source:sources) {
                assertEquals(64,source.contentHash().length());assertFalse(source.passages().isEmpty());assertNotNull(source.retrievedAt());
                for(var passage:source.passages()) assertEquals(source.text().substring(passage.startOffset(),passage.endOffset()),passage.text());
            }
            assertTrue(streamed.toString().contains("event:SOURCE_SAVED"));assertTrue(streamed.toString().contains("event:JOB_FINISHED"));
            assertTrue(repository.tasks(browser.owner,id).stream().anyMatch(task->task.kind().equals("MODEL")));
            var events=repository.events(browser.owner,id,0);assertFalse(events.toString().contains(browser.email));
            var replay=browser.call("GET","/api/research/jobs/"+id+"/events",null,false,null,Long.toString(events.getFirst().id()));
            assertEquals(200,replay.statusCode());assertFalse(replay.body().contains("event:JOB_QUEUED"));
            System.out.println("Live research verified: state="+job.status()+", sources="+sources.size()+", pages="+job.pages()+", modelTurns="+job.modelTurns()+", events="+events.size());
        }
    }
}

