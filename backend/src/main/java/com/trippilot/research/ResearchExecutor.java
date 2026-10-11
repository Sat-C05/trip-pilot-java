package com.trippilot.research;

import com.trippilot.model.*;
import com.trippilot.retrieval.*;
import com.trippilot.validation.*;
import static com.trippilot.validation.ValidatedResearch.*;
import java.util.*;
import java.time.Instant;
import java.util.function.BooleanSupplier;
import org.springframework.stereotype.Component;

/** Backend-owned acquisition, extraction, repair, validation and one targeted retry. */
@Component
public class ResearchExecutor {
    private final ResearchRepository repository;
    private final SearchProvider search;
    private final SafeWebClient web;
    private final PageExtractor extractor;
    private final ModelAdapter model;
    private final StructuredExtraction extraction=new StructuredExtraction();
    private final EvidenceValidator validator=new EvidenceValidator();
    public ResearchExecutor(ResearchRepository repository,SearchProvider search,SafeWebClient web,PageExtractor extractor,ModelAdapter model) {
        this.repository=repository;this.search=search;this.web=web;this.extractor=extractor;this.model=model;
    }
    public void execute(ResearchRepository.Run run,UUID worker,BooleanSupplier stopping) {
        BooleanSupplier cancelled=()->stopping.getAsBoolean() || Thread.currentThread().isInterrupted() || repository.cancelled(run,worker);
        var decisions=new ArrayList<Decision>();
        var candidates=new ArrayList<EvidenceValidator.Candidate>();
        var supplied=new LinkedHashMap<UUID,String>();
        var seen=new HashSet<java.net.URI>();
        var repairs=new int[]{0};
        try {
            if(!Set.of("extractive-v1",StructuredExtraction.PROMPT_VERSION).contains(run.model().model().promptVersion())) throw new ResearchFailure("UNSUPPORTED_PROMPT_VERSION");
            if(!search.id().equals(run.job().searchProvider())) throw new ResearchFailure("PROVIDER_UNAVAILABLE");
            String focus=repository.focus(run.job().ownerId().toString(),run.job().id());
            String suffix=switch(focus) { case "STAY"->" accommodation";case "TRANSPORT"->" transport";case "ACTIVITY"->" attractions";default->""; };
            acquire(run,worker,run.requirements().destinationIntent().strip()+suffix,seen,cancelled);
            var sources=repository.sources(run.job().ownerId().toString(),run.job().id());
            if(sources.isEmpty()) throw new ResearchFailure("NO_EVIDENCE");
            extract(run,worker,sources,candidates,supplied,decisions,repairs,cancelled);
            var result=validate(run,sources,supplied,candidates,decisions);
            var job=repository.read(run.job().ownerId().toString(),run.job().id());
            // Retry only a guide field the configured provider can supply; never send dates/preferences.
            boolean guideGap=result.fields().stream().anyMatch(f->f.field()==Name.DESCRIPTION && f.state()!=FieldState.SUPPORTED);
            if(guideGap && job.searches()<run.harness().searches() && job.pages()<run.harness().pages()
                    && job.modelTurns()<run.harness().modelTurns() && remaining(run,run.harness().fetchSeconds())>1) {
                var before=sources.stream().map(ResearchRepository.Source::id).collect(java.util.stream.Collectors.toSet());
                acquire(run,worker,run.requirements().destinationIntent().strip()+" travel guide",seen,cancelled);
                sources=repository.sources(run.job().ownerId().toString(),run.job().id());
                var acquired=sources.stream().filter(source->!before.contains(source.id())).toList();
                if(!acquired.isEmpty()) extract(run,worker,acquired,candidates,supplied,decisions,repairs,cancelled);
                result=validate(run,sources,supplied,candidates,decisions);
            }
            if(cancelled.getAsBoolean()) throw new ResearchFailure("CANCELLED");
            repository.finish(run,worker,result.complete()?JobState.SUCCEEDED:JobState.PARTIAL,result.complete()?"VALIDATED_COMPLETE":"VALIDATED_WITH_GAPS",result);
        } catch(Exception failure) {
            String code=code(failure);if("LOST_OWNERSHIP".equals(code)) return;
            var sources=repository.sources(run.job().ownerId().toString(),run.job().id());
            // Failure results contain explicit gaps; cancellation/interruption publishes nothing.
            decisions.add(new Decision(Name.DESCRIPTION,code));
            var result=validate(run,sources,supplied,candidates,decisions);
            JobState state=stopping.getAsBoolean()?JobState.INTERRUPTED:sources.isEmpty()?JobState.FAILED:JobState.PARTIAL;
            repository.finish(run,worker,state,stopping.getAsBoolean()?"SERVER_STOPPED":code,result);
        }
    }
    private ValidatedResearch validate(ResearchRepository.Run run,List<ResearchRepository.Source> sources,Map<UUID,String> supplied,
            List<EvidenceValidator.Candidate> candidates,List<Decision> decisions) {
        return validator.validate(run.job().revisionId(),run.requirements(),run.harness(),sources,supplied,candidates,Instant.now(),decisions);
    }
    private void acquire(ResearchRepository.Run run,UUID worker,String query,Set<java.net.URI> seen,BooleanSupplier cancelled) {
        UUID task=repository.begin(run,worker,"SEARCH");long start=System.nanoTime();List<SearchProvider.Hit> hits;
        int available=run.harness().pages()-repository.read(run.job().ownerId().toString(),run.job().id()).pages();
        try {
            hits=search.search(query,Math.min(3,available),run.harness().pageBytes(),remaining(run,run.harness().fetchSeconds()),cancelled);
            repository.taskEnd(run,worker,task,null,Map.of("resultCount",hits.size(),"durationMs",elapsed(start)));
        } catch(Exception failure) { repository.taskEnd(run,worker,task,code(failure),Map.of("durationMs",elapsed(start)));throw failure; }
        if(hits.isEmpty() && seen.isEmpty()) throw new ResearchFailure("EMPTY_SEARCH");
        int fetched=0;String lastFailure=null;
        for(var hit:hits) {
            if(fetched>=Math.min(3,available)) break;
            if(repository.read(run.job().ownerId().toString(),run.job().id()).pages()>=run.harness().pages()) break;
            if(!seen.add(hit.url())) continue;
            fetched++;task=repository.begin(run,worker,"FETCH");start=System.nanoTime();
            try {
                var page=web.get(hit.url(),run.harness().pageBytes(),remaining(run,run.harness().fetchSeconds()),cancelled);
                var source=repository.save(run,worker,page,extractor.extract(page,run.harness().sourceCharacters()));
                repository.taskEnd(run,worker,task,null,Map.of("sourceId",source,"durationMs",elapsed(start)));
                // Optional metadata is charged as a fetch and cannot bypass cancellation, budgets or deadlines.
                var file=CommonsImages.file(page);
                if(file.isPresent() && repository.images(run.job().ownerId().toString(),run.job().id()).isEmpty()) {
                    UUID imageTask=null;
                    try {
                        imageTask=repository.begin(run,worker,"FETCH");
                        var image=new CommonsImages(web).fetch(file.get(),Math.min(run.harness().pageBytes(),262144),remaining(run,run.harness().fetchSeconds()),cancelled);
                        if(image.isPresent()) repository.saveImage(run,worker,source,image.get());
                        repository.taskEnd(run,worker,imageTask,image.isPresent()?null:"IMAGE_ATTRIBUTION_UNAVAILABLE",Map.of());
                    } catch(Exception imageFailure) {
                        if(imageTask!=null) repository.taskEnd(run,worker,imageTask,code(imageFailure),Map.of());
                        if(cancelled.getAsBoolean()) throw imageFailure;
                    }
                }
            } catch(Exception failure) {
                lastFailure=code(failure);repository.taskEnd(run,worker,task,lastFailure,Map.of("durationMs",elapsed(start)));
                if(cancelled.getAsBoolean() || "JOB_DEADLINE".equals(lastFailure) || "BUDGET_EXHAUSTED".equals(lastFailure)) throw failure;
            }
        }
        if(repository.sources(run.job().ownerId().toString(),run.job().id()).isEmpty()) throw new ResearchFailure(lastFailure==null?"NO_EVIDENCE":lastFailure);
    }
    private void extract(ResearchRepository.Run run,UUID worker,List<ResearchRepository.Source> sources,
            List<EvidenceValidator.Candidate> candidates,Map<UUID,String> supplied,List<Decision> decisions,int[] repairs,BooleanSupplier cancelled) {
        String focus=repository.focus(run.job().ownerId().toString(),run.job().id());
        if(!"ALL".equals(focus)) {extractTopic(run,worker,sources,candidates,supplied,decisions,repairs,cancelled,focus);return;}
        var topics=extraction.context(run,sources,null).guideTopics();
        extractTopic(run,worker,sources,candidates,supplied,decisions,repairs,cancelled,"DESCRIPTION");
        for(var topic:List.of(Name.STAY,Name.TRANSPORT,Name.ACTIVITY)) {
            if(!topics.contains(topic)) continue;
            var job=repository.read(run.job().ownerId().toString(),run.job().id());
            if(job.modelTurns()>=run.harness().modelTurns()) {decisions.add(new Decision(topic,"BUDGET_EXHAUSTED"));continue;}
            extractTopic(run,worker,sources,candidates,supplied,decisions,repairs,cancelled,topic.name());
        }
    }
    private void extractTopic(ResearchRepository.Run run,UUID worker,List<ResearchRepository.Source> sources,
            List<EvidenceValidator.Candidate> candidates,Map<UUID,String> supplied,List<Decision> decisions,int[] repairs,BooleanSupplier cancelled,String focus) {
        String previous=null;
        while(true) {
            StructuredExtraction.Context context;
            try { context=extraction.context(run,sources,previous,focus,repository.question(run.job().ownerId().toString(),run.job().id())); }
            catch(IllegalArgumentException failure) { throw new ResearchFailure("CONTEXT_EVIDENCE_LIMIT"); }
            UUID task=repository.begin(run,worker,"MODEL");long start=System.nanoTime();
            ModelAdapter.Response response=null;
            try {
                var config=run.model();
                response=model.infer(new InferenceConfiguration(config.model(),config.temperature(),config.seed(),
                        Math.min(config.outputTokens(),run.harness().outputTokens()),remaining(run,Math.min(config.callSeconds(),run.harness().modelCallSeconds()))),context.request(),cancelled);
                if(cancelled.getAsBoolean()) throw new ResearchFailure("CANCELLED");
                List<EvidenceValidator.Candidate> parsed;
                boolean wrapperRepaired=false;
                try {parsed=extraction.parse(run,response.text());}
                catch(IllegalArgumentException invalid) {
                    var inner=extraction.fencedJson(response.text());
                    if(inner.isEmpty() || repairs[0]>=run.harness().outputRepairs()) throw invalid;
                    parsed=extraction.parse(run,inner.get());
                    repairs[0]++;
                    wrapperRepaired=true;
                    decisions.add(new Decision(Name.valueOf(focus),"STRUCTURAL_WRAPPER_REPAIRED"));
                }
                // A successful call cannot cite evidence supplied only to another call.
                for(var candidate:parsed) {
                    if(context.supplied().containsKey(candidate.passageId())) candidates.add(candidate);
                    else decisions.add(new Decision(candidate.field(),"UNKNOWN_OR_UNSUPPLIED_EVIDENCE"));
                }
                context.supplied().forEach((id,text)->supplied.merge(id,text,(first,next)->first+"\n"+next));
                repository.taskEnd(run,worker,task,null,Map.of("inputTokens",response.inputTokens(),"outputTokens",response.outputTokens(),
                        "durationMs",elapsed(start),"repair",previous!=null || wrapperRepaired));
                return;
            } catch(IllegalArgumentException invalid) {
                repository.taskEnd(run,worker,task,"INVALID_EXTRACTION",Map.of("durationMs",elapsed(start)));
                decisions.add(new Decision(Name.valueOf(focus),"INVALID_EXTRACTION"));
                if(repairs[0]>=run.harness().outputRepairs()) throw new ResearchFailure("INVALID_EXTRACTION");
                repairs[0]++;
                previous=response==null ? "Invalid structure" : response.text().substring(0,Math.min(4000,response.text().length()));
            } catch(Exception failure) { repository.taskEnd(run,worker,task,code(failure),Map.of("durationMs",elapsed(start)));throw failure; }
        }
    }
    private static long elapsed(long start) { return java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-start); }
    private static int remaining(ResearchRepository.Run run,int maximum) {
        long seconds=java.time.Duration.between(Instant.now(),run.job().startedAt().plusSeconds(run.harness().jobSeconds())).getSeconds();
        if(seconds<1) throw new ResearchFailure("JOB_DEADLINE");return (int)Math.min(maximum,seconds);
    }
    private static String code(Exception failure) {
        if(failure instanceof ResearchFailure) return failure.getMessage();
        if(failure instanceof RetrievalFailure retrieval) return retrieval.code().name();
        if(failure instanceof ModelFailure model) return "MODEL_"+model.code().name();
        return "RESEARCH_FAILURE";
    }
}
