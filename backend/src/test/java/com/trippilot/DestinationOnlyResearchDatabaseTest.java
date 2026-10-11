package com.trippilot;

import static org.junit.jupiter.api.Assertions.*;
import com.trippilot.research.*;
import com.trippilot.validation.*;
import java.util.UUID;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.*;
import org.springframework.boot.test.context.SpringBootTest;

/** Destination-only UI defaults against actual guide sources and the pinned model. */
@EnabledIfEnvironmentVariable(named="PHASE2_LIVE_TESTS",matches="true")
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties="trippilot.dispatch.enabled=false")
class DestinationOnlyResearchDatabaseTest {
    // Fetch each public guide/search once per suite; repeat model extraction with new job/passage IDs.
    // This exercises format/scope variability without repeatedly hammering the public provider.
    static final java.util.Map<String,java.util.List<com.trippilot.retrieval.SearchProvider.Hit>> hits=new java.util.HashMap<>();
    static final java.util.Map<java.net.URI,com.trippilot.retrieval.SafeWebClient.Page> pages=new java.util.HashMap<>();
    static class TestWeb extends com.trippilot.retrieval.SafeWebClient implements AutoCloseable {}
    @Value("${local.server.port}") int port;
    @Autowired ResearchRepository repository;
    @Autowired com.trippilot.retrieval.SearchProvider search;
    @Autowired com.trippilot.retrieval.SafeWebClient web;
    @Autowired com.trippilot.retrieval.PageExtractor extractor;
    @Autowired com.trippilot.model.ModelAdapter model;
    @RepeatedTest(3) void jaipurWithNoTravelDetailsHasAttributedGuideInformation() throws Exception {research("Jaipur");}
    @RepeatedTest(3) void lowercaseMumbaiWithNoTravelDetailsHasAttributedGuideInformation() throws Exception {research("mumbai");}
    @RepeatedTest(2) void datedMumbaiHasGuideCategories() throws Exception {research("mumbai",true);}
    void research(String destination) throws Exception {research(destination,false);}
    void research(String destination,boolean dated) throws Exception {
        try(var browser=new ResearchTestBrowser(port)) {
            browser.signIn();var session=browser.session();
            var input=dated?new com.trippilot.planning.TravelRequirements("Delhi",destination,"IN",java.time.LocalDate.parse("2026-11-01"),java.time.LocalDate.parse("2026-11-04"),new java.math.BigDecimal("20000"),"INR",1,java.util.List.of()):new com.trippilot.planning.TravelRequirements(null,destination,"IN",null,null,null,"INR",null,java.util.List.of());
            var revision=repository.revise(browser.owner,session,input);var job=repository.admit(browser.owner,session,revision.id(),UUID.randomUUID().toString(),null);
            var fixtures=new java.util.ArrayList<String>();
            com.trippilot.model.ModelAdapter diagnostic=(configuration,request,cancelled)-> {
                var response=model.infer(configuration,request,cancelled);
                fixtures.add(request.system()+"\n"+request.prompt()+"\nRESPONSE\n"+response.text());return response;
            };
            var cachedSearch=new com.trippilot.retrieval.SearchProvider() {
                public String id(){return search.id();}
                public java.util.List<Hit> search(String query,int count,int bytes,int seconds,java.util.function.BooleanSupplier cancelled) {
                    return hits.computeIfAbsent(query,q->search.search(q,count,bytes,seconds,cancelled));
                }
            };
            try(var cachedWeb=new TestWeb() {
                public Page get(java.net.URI url,int bytes,int seconds,java.util.function.BooleanSupplier cancelled) {
                    return pages.computeIfAbsent(url,u->web.get(u,bytes,seconds,cancelled));
                }
            }) {
                var worker=UUID.randomUUID();new ResearchExecutor(repository,cachedSearch,cachedWeb,extractor,diagnostic).execute(repository.claim(worker,job.id()),worker,()->false);
            }
            var completed=repository.read(browser.owner,job.id());var result=repository.result(browser.owner,job.id());
            var description=result.fields().stream().filter(f->f.field()==ValidatedResearch.Name.DESCRIPTION).findFirst().orElseThrow();
            // Only public guide text and this synthetic destination-only fixture; no production/user traces.
            if((description.state()!=FieldState.SUPPORTED && description.state()!=FieldState.CONFLICTING)
                    || (dated && result.fields().stream().filter(f->java.util.List.of(ValidatedResearch.Name.STAY,ValidatedResearch.Name.TRANSPORT,ValidatedResearch.Name.ACTIVITY).contains(f.field())).anyMatch(f->f.state()!=FieldState.SUPPORTED && f.state()!=FieldState.CONFLICTING))) {
                var output=java.nio.file.Path.of("../runtime-data/diagnosis/destination-only-"+job.id()+".txt");
                java.nio.file.Files.createDirectories(output.getParent());java.nio.file.Files.writeString(output,String.join("\nNEXT TURN\n",fixtures));
            }
            assertTrue(description.state()==FieldState.SUPPORTED || description.state()==FieldState.CONFLICTING,completed.outcomeCode()+" "+result.decisions());
            assertFalse(description.citations().isEmpty());assertEquals(dated?0:5,result.missingRequirements().size());
            if(dated) for(var name:java.util.List.of(ValidatedResearch.Name.STAY,ValidatedResearch.Name.TRANSPORT,ValidatedResearch.Name.ACTIVITY)) {
                var field=result.fields().stream().filter(f->f.field()==name).findFirst().orElseThrow();
                assertTrue(field.state()==FieldState.SUPPORTED || field.state()==FieldState.CONFLICTING,name+" "+result.decisions());assertFalse(field.citations().isEmpty());
                if(name==ValidatedResearch.Name.STAY) assertFalse(field.citations().stream().anyMatch(c->c.quote().contains("district articles")));
                if(name==ValidatedResearch.Name.ACTIVITY) assertFalse(field.citations().stream().anyMatch(c->c.quote().contains("time to visit")));
            }
            assertTrue(result.fields().stream().filter(f->f.field()==ValidatedResearch.Name.PRICE || f.field()==ValidatedResearch.Name.AVAILABILITY || f.field()==ValidatedResearch.Name.POLICY).allMatch(f->f.state()==FieldState.UNAVAILABLE));
            System.out.println("Actual guide research: "+destination+", dated="+dated+", fields="+result.fields().stream().map(f->f.field()+":"+f.state()).toList()+", sources="+repository.sources(browser.owner,job.id()).size()+", turns="+completed.modelTurns());
        }
    }
}
