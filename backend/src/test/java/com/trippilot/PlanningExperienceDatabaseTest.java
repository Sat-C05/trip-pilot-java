package com.trippilot;

import static org.junit.jupiter.api.Assertions.*;
import com.jayway.jsonpath.JsonPath;
import com.trippilot.research.*;
import com.trippilot.retrieval.*;
import com.trippilot.validation.*;
import com.trippilot.model.*;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.*;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.json.JsonMapper;

@EnabledIfEnvironmentVariable(named="PHASE1_DB_TESTS",matches="true")
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties="trippilot.dispatch.enabled=false")
class PlanningExperienceDatabaseTest {
    @Value("${local.server.port}") int port;
    @Autowired ResearchRepository repository;
    @Autowired JdbcTemplate jdbc;
    final JsonMapper json=JsonMapper.builder().findAndAddModules().build();
    final UUID worker=UUID.randomUUID();
    ResearchTestBrowser browser() throws Exception { var b=new ResearchTestBrowser(port);b.signIn();return b; }
    UUID completed(ResearchTestBrowser b,UUID session,String destination) throws Exception {
        var requirements=new com.trippilot.planning.TravelRequirements("Delhi",destination,"IN",null,null,null,null,null,List.of());
        var revision=repository.revise(b.owner,session,requirements);
        var job=repository.admit(b.owner,session,revision.id(),UUID.randomUUID().toString(),null);
        execute(b,job.id(),destination);
        return job.id();
    }
    void execute(ResearchTestBrowser b,UUID job,String destination) {
        var run=repository.claim(worker,job);
        SearchProvider search=new SearchProvider() {
            public String id(){return "wikivoyage-v1";}
            public List<Hit> search(String query,int count,int bytes,int seconds,BooleanSupplier cancelled){return List.of(new Hit(destination,URI.create("https://example.org/guide")));}
        };
        var web=new SafeWebClient(){public Page get(URI url,int bytes,int seconds,BooleanSupplier cancelled){return new Page(url,url,Instant.now(),200,"text/html",("<title>"+destination+"</title><main>"+destination+" is a destination in India.</main>").getBytes(StandardCharsets.UTF_8));}};
        ModelAdapter model=(config,request,cancelled)->{
            var evidence=request.prompt().lines().filter(line->line.startsWith("{") && line.contains("passageId")).map(json::readTree).findFirst().orElseThrow();
            var scope=new LinkedHashMap<String,Object>();scope.put("field","DESCRIPTION");scope.put("quote",destination+" is a destination in India.");scope.put("passageId",evidence.path("passageId").asText());
            return new ModelAdapter.Response(json.writeValueAsString(Map.of("candidates",List.of(scope))),100,70);
        };
        try {new ResearchExecutor(repository,search,web,new PageExtractor(),model).execute(run,worker,()->false);}finally{web.close();}
    }
    UUID select(ResearchTestBrowser b,UUID session,UUID job) throws Exception {
        var response=b.post("/api/planning/sessions/"+session+"/selected-trips",json.writeValueAsString(Map.of("jobId",job)));
        assertEquals(201,response.statusCode(),response.body());return UUID.fromString(JsonPath.read(response.body(),"$.id"));
    }
    String followup(UUID trip,String destination,String question,String key,String focus) {
        return json.writeValueAsString(Map.of("requirements",Map.of("departure","Delhi","destinationIntent",destination,"country","IN","preferences",List.of()),"question",question,"requestKey",key,"focus",focus));
    }
    @Test void comparisonSelectionAndIndependentFollowupsSurviveReload() throws Exception {
        try(var b=browser();var other=browser()) {
            UUID session=b.session();UUID jaipur=completed(b,session,"Jaipur"),udaipur=completed(b,session,"Udaipur");
            assertEquals(2,JsonPath.<List<?>>read(b.get("/api/planning/sessions/"+session+"/jobs").body(),"$").size());
            UUID first=select(b,session,jaipur),second=select(b,session,udaipur);
            assertEquals(first,select(b,session,jaipur));assertNotEquals(first,second);
            String key=UUID.randomUUID().toString(),body=followup(first,"Jaipur","Look into transport, private-question-42",key,"TRANSPORT");
            var queued=b.post("/api/planning/trips/"+first+"/research",body);assertEquals(202,queued.statusCode(),queued.body());
            UUID follow=UUID.fromString(JsonPath.read(queued.body(),"$.id"));
            assertEquals(follow.toString(),JsonPath.read(b.post("/api/planning/trips/"+first+"/research",body).body(),"$.id"));
            assertEquals(first,repository.read(b.owner,follow).contextId());assertEquals("TRANSPORT",repository.focus(b.owner,follow));
            assertEquals(409,b.post("/api/planning/trips/"+first+"/research",followup(first,"Jaipur","changed",key,"TRANSPORT")).statusCode());
            assertEquals(409,b.post("/api/planning/trips/"+second+"/research",followup(second,"Udaipur","changed",key,"ALL")).statusCode());
            execute(b,follow,"Jaipur");
            assertEquals(1,JsonPath.<List<?>>read(b.get("/api/planning/trips/"+first+"/messages").body(),"$").size());
            assertEquals("[]",b.get("/api/planning/trips/"+second+"/messages").body());
            assertEquals("[]",json.readTree(b.get("/api/planning/trips/"+second).body()).path("jobs").toString());
            assertTrue(b.get("/api/planning/trips/"+first).body().contains(follow.toString()));
            assertFalse(repository.events(b.owner,follow,0).toString().contains("private-question-42"));
            assertFalse(b.get("/api/planning/sessions/"+session+"/jobs").body().contains(follow.toString()));
            assertEquals(404,other.get("/api/planning/trips/"+first).statusCode());
            assertEquals(404,other.get("/api/planning/trips/"+first+"/messages").statusCode());
            assertEquals(404,other.get("/api/planning/sessions/"+session+"/selected-trips").statusCode());
            assertEquals(404,other.post("/api/planning/trips/"+first+"/research",followup(first,"Jaipur","attack",UUID.randomUUID().toString(),"ALL")).statusCode());
            assertEquals(404,other.get("/api/research/jobs/"+follow+"/images").statusCode());
            assertEquals(404,other.get("/api/planning/revisions/"+repository.read(b.owner,follow).revisionId()).statusCode());
            assertEquals(403,b.call("POST","/api/planning/trips/"+first+"/research",body,false,"application/json",null).statusCode());
        }
    }
    @Test void selectedRevisionCannotBeAdmittedIntoPlanningOrAnotherTrip() throws Exception {
        try(var b=browser()) {
            UUID session=b.session(),first=select(b,session,completed(b,session,"Jaipur")),second=select(b,session,completed(b,session,"Udaipur"));
            var response=b.post("/api/planning/trips/"+first+"/research",followup(first,"Jaipur","Refresh",UUID.randomUUID().toString(),"PRICE"));
            UUID job=UUID.fromString(JsonPath.read(response.body(),"$.id"));var revision=repository.read(b.owner,job).revisionId();
            assertThrows(com.trippilot.presentation.ApiException.class,()->repository.admit(b.owner,session,revision,UUID.randomUUID().toString(),null));
            assertThrows(com.trippilot.presentation.ApiException.class,()->repository.admit(b.owner,session,second,revision,UUID.randomUUID().toString(),null,"ALL"));
            assertEquals(400,b.post("/api/planning/trips/"+first+"/research",followup(first,"Agra","Move trip",UUID.randomUUID().toString(),"ALL")).statusCode());
            repository.cancel(b.owner,job);
            assertEquals(400,b.post("/api/planning/trips/"+first+"/research",followup(first,"Jaipur","invalid topic",UUID.randomUUID().toString(),"SUBMIT_BOOKING")).statusCode());
        }
    }
    @Test void staleSelectionsDowngradeAndRefreshDoesNotOverwritePreviousEvidence() throws Exception {
        try(var b=browser()) {
            UUID session=b.session(),job=completed(b,session,"Jaipur"),trip=select(b,session,job);
            jdbc.update("UPDATE validated_research_results SET result=JSON_SET(result,'$.fields[0].citations[0].retrievedAt',?) WHERE job_id=?",Instant.now().minusSeconds(31L*86400).toString(),job.toString());
            assertEquals("STALE",JsonPath.read(b.get("/api/planning/trips/"+trip).body(),"$.selectedResult.fields[0].state"));
            UUID old=completed(b,session,"Agra");
            jdbc.update("UPDATE validated_research_results SET result=JSON_SET(result,'$.fields[0].citations[0].retrievedAt',?) WHERE job_id=?",Instant.now().minusSeconds(31L*86400).toString(),old.toString());
            assertEquals(409,b.post("/api/planning/sessions/"+session+"/selected-trips",json.writeValueAsString(Map.of("jobId",old))).statusCode());
            var refresh=b.post("/api/planning/trips/"+trip+"/research",followup(trip,"Jaipur","Refresh current prices",UUID.randomUUID().toString(),"PRICE"));
            UUID fresh=UUID.fromString(JsonPath.read(refresh.body(),"$.id"));execute(b,fresh,"Jaipur");
            assertEquals(1,repository.read(b.owner,fresh).searches());assertEquals(1,repository.sources(b.owner,fresh).size());
            assertNotEquals(repository.sources(b.owner,job).getFirst().id(),repository.sources(b.owner,fresh).getFirst().id());
            assertEquals(FieldState.UNAVAILABLE,repository.result(b.owner,fresh).fields().stream().filter(f->f.field()==ValidatedResearch.Name.PRICE).findFirst().orElseThrow().state());
            assertEquals("STALE",JsonPath.read(b.get("/api/planning/trips/"+trip).body(),"$.selectedResult.fields[0].state"));
        }
    }
    @Test void clarificationValidatesDatesAndKeepsUnknownInputsExplicit() throws Exception {
        try(var b=browser()) {
            var missing=b.post("/api/planning/clarification","{\"country\":\"IN\",\"destinationIntent\":\"Jaipur\",\"preferences\":[]}");
            assertEquals(200,missing.statusCode());assertEquals(6,JsonPath.<List<?>>read(missing.body(),"$.missing").size());
            assertEquals(400,b.post("/api/planning/clarification","{\"country\":\"IN\",\"arrival\":\"2026-12-10\",\"departureDate\":\"2026-12-09\",\"preferences\":[]}").statusCode());
            assertEquals(400,b.post("/api/planning/clarification","{\"country\":\"US\",\"preferences\":[]}").statusCode());
            assertEquals(400,b.post("/api/planning/clarification","{\"country\":\"IN\",\"travelers\":0,\"preferences\":[]}").statusCode());
        }
    }
    @Test void imagePublicationIsScopedAndCancellationFenced() throws Exception {
        try(var b=browser();var other=browser()) {
            UUID session=b.session(),revision=b.revision(session),job=b.job(session,revision,UUID.randomUUID().toString());
            var run=repository.claim(worker,job);
            URI url=URI.create("https://en.wikivoyage.org/wiki/Jaipur");
            var page=new SafeWebClient.Page(url,url,Instant.now(),200,"text/html","<title>Jaipur</title><main>Jaipur is in India.</main>".getBytes(StandardCharsets.UTF_8));
            UUID source=repository.save(run,worker,page,new PageExtractor().extract(page,1000));
            var image=new CommonsImages.Image("https://upload.wikimedia.org/image.jpg","https://commons.wikimedia.org/wiki/File:Jaipur.jpg","File:Jaipur.jpg","Synthetic photographer","CC BY-SA 4.0","https://creativecommons.org/licenses/by-sa/4.0/",Instant.now());
            repository.saveImage(run,worker,source,image);
            assertEquals(1,repository.images(b.owner,job).size());
            assertEquals(200,b.get("/api/research/jobs/"+job+"/images").statusCode());
            assertEquals(404,other.get("/api/research/jobs/"+job+"/images").statusCode());
            assertThrows(com.trippilot.presentation.ApiException.class,()->repository.saveImage(run,worker,UUID.randomUUID(),image));
            repository.cancel(b.owner,job);
            assertThrows(ResearchFailure.class,()->repository.saveImage(run,worker,source,image));
            repository.finish(run,worker,JobState.PARTIAL,"CANCELLED");
            assertEquals(JobState.CANCELLED,repository.read(b.owner,job).status());
        }
    }
    @Test void selectingAnAmbiguousOptionPreservesItsUnresolvedAlternatives() throws Exception {
        try(var b=browser()) {
            UUID session=b.session(),job=completed(b,session,"Jaipur");
            // Seed the already-tested validator's public conflict view; selection must never resolve it.
            jdbc.update("UPDATE validated_research_results SET result=JSON_SET(result,'$.fields[0].state','CONFLICTING','$.fields[0].value',CAST('null' AS JSON),'$.fields[0].reason','DIFFERING_EXCERPTS') WHERE job_id=?",job.toString());
            UUID trip=select(b,session,job);
            var result=json.readTree(b.get("/api/planning/trips/"+trip).body()).path("selectedResult").path("fields").path(0);
            assertEquals("CONFLICTING",result.path("state").asText());assertTrue(result.path("value").isNull());assertFalse(result.path("citations").isEmpty());
        }
    }
    @Test @EnabledIfEnvironmentVariable(named="PHASE4_BROWSER_TESTS",matches="true")
    void productionBrowserPlanningAndMobileFlow() throws Exception {
        try(var b=browser()) {
            UUID session=b.session();completed(b,session,"Jaipur");completed(b,session,"Udaipur");
            var root=java.nio.file.Path.of("..").toAbsolutePath().normalize();
            var output=root.resolve("runtime-data/phase4-browser");java.nio.file.Files.createDirectories(output);
            var builder=new ProcessBuilder("node",root.resolve("scripts/verify-planning-ui.cjs").toString(),Integer.toString(port),b.email,output.toString());
            builder.redirectErrorStream(true).redirectOutput(output.resolve("browser.log").toFile());
            var process=builder.start();
            try { assertTrue(process.waitFor(90,java.util.concurrent.TimeUnit.SECONDS),"Browser verification deadline");assertEquals(0,process.exitValue(),java.nio.file.Files.readString(output.resolve("browser.log"))); }
            finally{if(process.isAlive())process.destroyForcibly();}
        }
    }
}
