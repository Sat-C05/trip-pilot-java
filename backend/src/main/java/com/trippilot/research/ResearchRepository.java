package com.trippilot.research;

import com.trippilot.model.*;
import com.trippilot.observability.TraceRedactor;
import com.trippilot.planning.TravelRequirements;
import com.trippilot.presentation.ApiException;
import com.trippilot.retrieval.*;
import com.trippilot.validation.ValidatedResearch;
import java.time.Instant;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

@Repository
public class ResearchRepository {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final JsonMapper json = JsonMapper.builder().findAndAddModules().build();
    private final ModelConfigurationFactory models;
    private final HarnessProperties policy;
    public ResearchRepository(JdbcTemplate jdbc, org.springframework.transaction.PlatformTransactionManager manager,
            ModelConfigurationFactory models, HarnessProperties policy) {
        this.jdbc = jdbc; this.tx = new TransactionTemplate(manager); this.models = models; this.policy = policy;
    }
    public record Revision(UUID id, int revisionNo, TravelRequirements requirements) {}
    public record Job(UUID id, UUID ownerId, UUID sessionId, UUID contextId, UUID revisionId, JobState status,
            long version, UUID traceId, String outcomeCode, Instant createdAt, Instant startedAt,
            int modelTurns, int searches, int pages, String searchProvider,
            String cardTitle, String cardNotes, Instant cardDeletedAt) {}
    public record Run(Job job, InferenceConfiguration model, HarnessProperties harness, TravelRequirements requirements) {}
    public record Task(UUID id, String kind, String status, String outcomeCode) {}
    public record Event(long id, String type, Map<String,Object> data, Instant createdAt) {}
    public record Source(UUID id, String requestedUrl, String finalUrl, Instant retrievedAt, String contentType,
            String title, String contentHash, String text, List<Passage> passages) {}
    public record Passage(UUID id, int startOffset, int endOffset, String text) {}
    public record ModelPolicy(String provider, String modelId, String artifactFingerprint,
            String runtimeVersion, int contextTokens, String promptVersion, int outputTokens, int callSeconds) {}
    public record Inspection(Job job, UUID retryOf, String focus, ModelPolicy model,
            HarnessProperties budgets, List<Task> tasks, List<Source> sources, ValidatedResearch result) {}
    /** Owner-scoped diagnostic view. Never expose endpoint configuration, prompts or raw model responses. */
    @org.springframework.transaction.annotation.Transactional(readOnly=true)
    public Inspection inspect(String owner, UUID id) {
        var job=read(owner,id);
        var row=jdbc.queryForMap("SELECT model_snapshot,harness_snapshot,retry_of FROM research_jobs WHERE id=? AND owner_id=?",id.toString(),owner);
        var configuration=json.readValue((String)row.get("model_snapshot"),InferenceConfiguration.class);
        var model=configuration.model();
        var captured=new ModelPolicy(model.provider(),model.modelId(),model.artifactFingerprint(),model.runtimeVersion(),
                model.contextTokens(),model.promptVersion(),configuration.outputTokens(),configuration.callSeconds());
        ValidatedResearch result=null;
        try { result=result(owner,id); }
        catch(ApiException missingResult) { if(!"RESULT_NOT_READY".equals(missingResult.code())) throw missingResult; }
        return new Inspection(job,row.get("retry_of")==null?null:UUID.fromString((String)row.get("retry_of")),focus(owner,id),captured,
                json.readValue((String)row.get("harness_snapshot"),HarnessProperties.class),tasks(owner,id),sources(owner,id),result);
    }
    private static final Set<String> TRACE_KEYS = Set.of("state","code","taskId","kind","sourceId","passageCount","resultCount",
            "inputTokens","outputTokens","durationMs","selectedPassageIds","provider","modelId","artifactFingerprint","version",
            "complete","supportedCount","gapCount","repair","validatorVersion");

    private void ownerSession(String owner, UUID session, boolean lock) {
        var ids = jdbc.queryForList("SELECT id FROM planning_sessions WHERE id=? AND owner_id=?" + (lock ? " FOR UPDATE" : ""), String.class, session.toString(), owner);
        if (ids.isEmpty()) throw missing();
    }
    public Revision revise(String owner, UUID session, TravelRequirements requirements) {
        return revise(owner,session,planningContext(owner,session),requirements);
    }
    public UUID planningContext(String owner,UUID session) {
        ownerSession(owner,session,false);
        return UUID.fromString(jdbc.queryForObject("SELECT id FROM trip_contexts WHERE session_id=? AND owner_id=? AND kind='PLANNING'",String.class,session.toString(),owner));
    }
    public Revision revise(String owner, UUID session, UUID context, TravelRequirements requirements) {
        return tx.execute(status -> {
            ownerSession(owner, session, true);
            if(jdbc.queryForList("SELECT id FROM trip_contexts WHERE id=? AND session_id=? AND owner_id=?",String.class,context.toString(),session.toString(),owner).isEmpty()) throw missing();
            int number = jdbc.queryForObject("SELECT COALESCE(MAX(revision_no),0)+1 FROM request_revisions WHERE session_id=?", Integer.class, session.toString());
            UUID id = UUID.randomUUID();
            jdbc.update("INSERT INTO request_revisions(id,session_id,owner_id,revision_no,requirements,context_id) VALUES (?,?,?,?,?,?)",
                    id.toString(),session.toString(),owner,number,json.writeValueAsString(requirements),context.toString());
            return new Revision(id,number,requirements);
        });
    }
    public Job admit(String owner, UUID session, UUID revision, String key, UUID retryOf) {
        return admit(owner,session,planningContext(owner,session),revision,key,retryOf,"ALL");
    }
    public Job admit(String owner, UUID session, UUID context, UUID revision, String key, UUID retryOf,String focus) {
        return tx.execute(status -> {
            ownerSession(owner, session, true);
            jdbc.queryForObject("SELECT id FROM schema_baseline WHERE id=1 FOR UPDATE",Integer.class);
            var previous = jdbc.queryForList("SELECT id,request_revision_id,session_id,retry_of FROM research_jobs WHERE owner_id=? AND request_key=?", owner,key);
            if (!previous.isEmpty()) {
                var row = previous.getFirst();
                if (!read(owner,UUID.fromString((String)row.get("id"))).contextId().equals(context) || !focus.equals(focus(owner,UUID.fromString((String)row.get("id"))))
                        || !revision.toString().equals(row.get("request_revision_id")) || !session.toString().equals(row.get("session_id"))
                        || !Objects.equals(retryOf == null ? null : retryOf.toString(),row.get("retry_of"))) throw new ApiException(HttpStatus.CONFLICT,"IDEMPOTENCY_CONFLICT","Request key was already used for another payload.");
                return read(owner,UUID.fromString((String)row.get("id")));
            }
            var requirements = jdbc.queryForList("SELECT requirements FROM request_revisions WHERE id=? AND session_id=? AND owner_id=? AND context_id=?",String.class,revision.toString(),session.toString(),owner,context.toString());
            if (requirements.isEmpty()) throw missing();
            var input = json.readValue(requirements.getFirst(),TravelRequirements.class);
            if (input.destinationIntent() == null || input.destinationIntent().isBlank()) throw new ApiException(HttpStatus.BAD_REQUEST,"DESTINATION_REQUIRED","Provide a destination intent before research.");
            if (retryOf != null) {
                var old = read(owner,retryOf);
                if (!old.contextId().equals(context) || !terminal(old.status())) throw new ApiException(HttpStatus.CONFLICT,"INVALID_RETRY","Retry must reference a terminal job in this context.");
            }
            // Prevent unbounded durable backlog. Serialization covers concurrent admissions from all owners.
            if (jdbc.queryForObject("SELECT COUNT(*) FROM research_jobs WHERE status IN ('QUEUED','RUNNING','CANCEL_REQUESTED')",Integer.class) >= 100)
                throw new ApiException(HttpStatus.TOO_MANY_REQUESTS,"QUEUE_FULL","Research queue is full. Try later.");
            UUID id = UUID.randomUUID();
            var captured=models.snapshot();
            jdbc.update("INSERT INTO research_jobs(id,owner_id,session_id,context_id,request_revision_id,model_snapshot,harness_snapshot,status,trace_id,request_key,retry_of,focus) VALUES (?,?,?,?,?,?,?,'QUEUED',?,?,?,?)",
                    id.toString(),owner,session.toString(),context.toString(),revision.toString(),json.writeValueAsString(captured),json.writeValueAsString(policy),UUID.randomUUID().toString(),key,retryOf == null ? null : retryOf.toString(),focus);
            var job = read(owner,id);
            event(job,"JOB_QUEUED",Map.of("state",job.status(),"provider",job.searchProvider(),"modelId",captured.model().modelId(),
                    "artifactFingerprint",captured.model().artifactFingerprint(),"version",captured.model().runtimeVersion()));
            return job;
        });
    }
    public Job read(String owner, UUID id) {
        var rows = jdbc.query("SELECT * FROM research_jobs WHERE id=? AND owner_id=?",(rs,n) -> new Job(
                UUID.fromString(rs.getString("id")),UUID.fromString(rs.getString("owner_id")),UUID.fromString(rs.getString("session_id")),
                UUID.fromString(rs.getString("context_id")),UUID.fromString(rs.getString("request_revision_id")),JobState.valueOf(rs.getString("status")),
                rs.getLong("version"),UUID.fromString(rs.getString("trace_id")),rs.getString("outcome_code"),rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("started_at") == null ? null : rs.getTimestamp("started_at").toInstant(),rs.getInt("model_turns"),rs.getInt("searches"),rs.getInt("pages"),rs.getString("search_provider"),
                rs.getString("card_title"),rs.getString("card_notes"),rs.getTimestamp("card_deleted_at")==null?null:rs.getTimestamp("card_deleted_at").toInstant()),id.toString(),owner);
        if (rows.isEmpty()) throw missing(); return rows.getFirst();
    }
    public String focus(String owner,UUID job) {
        read(owner,job);
        return jdbc.queryForObject("SELECT focus FROM research_jobs WHERE id=? AND owner_id=?",String.class,job.toString(),owner);
    }
    public String question(String owner,UUID job) {
        read(owner,job);
        var rows=jdbc.queryForList("SELECT content FROM conversation_messages WHERE job_id=? AND owner_id=? AND role='USER' LIMIT 1",String.class,job.toString(),owner);
        return rows.isEmpty()?null:rows.getFirst();
    }
    public Revision revision(String owner,UUID id) {
        var rows=jdbc.query("SELECT revision_no,requirements FROM request_revisions WHERE id=? AND owner_id=?",
                (rs,n)->new Revision(id,rs.getInt(1),json.readValue(rs.getString(2),TravelRequirements.class)),id.toString(),owner);
        if(rows.isEmpty()) throw missing();return rows.getFirst();
    }
    public List<Job> jobs(String owner,UUID session,UUID context) {
        return jobs(owner,session,context,false);
    }
    public List<Job> jobs(String owner,UUID session,UUID context,boolean includeDeleted) {
        ownerSession(owner,session,false);
        return jdbc.queryForList("SELECT id FROM research_jobs WHERE session_id=? AND context_id=? AND owner_id=?"+(includeDeleted?"":" AND card_deleted_at IS NULL")+" ORDER BY created_at DESC,id DESC LIMIT 100",String.class,session.toString(),context.toString(),owner)
                .stream().map(id->read(owner,UUID.fromString(id))).toList();
    }
    public void saveImage(Run run,UUID worker,UUID source,CommonsImages.Image image) {
        tx.executeWithoutResult(status->{var job=lock(run,worker);active(job,run.harness);
            if(jdbc.queryForObject("SELECT COUNT(*) FROM source_snapshots WHERE id=? AND job_id=? AND owner_id=?",Integer.class,source.toString(),job.id.toString(),job.ownerId.toString())!=1) throw missing();
            jdbc.update("INSERT INTO research_images(id,source_id,context_id,owner_id,metadata) VALUES (?,?,?,?,?)",UUID.randomUUID().toString(),source.toString(),job.contextId.toString(),job.ownerId.toString(),json.writeValueAsString(image));
        });
    }
    public List<CommonsImages.Image> images(String owner,UUID job) {
        read(owner,job);
        return jdbc.queryForList("SELECT i.metadata FROM research_images i JOIN source_snapshots s ON s.id=i.source_id WHERE s.job_id=? AND i.owner_id=? ORDER BY s.retrieved_at,s.id",String.class,job.toString(),owner)
                .stream().map(value->json.readValue(value,CommonsImages.Image.class)).toList();
    }
    public Run claim(UUID workerOwner) {
        return claim(workerOwner,null);
    }
    /** A targeted claim also supports explicit administrative dispatch without replaying terminal work. */
    public Run claim(UUID workerOwner,UUID target) {
        return tx.execute(status -> {
            var rows = target==null ? jdbc.queryForList("SELECT id,owner_id FROM research_jobs WHERE status='QUEUED' ORDER BY created_at,id LIMIT 1 FOR UPDATE SKIP LOCKED")
                    : jdbc.queryForList("SELECT id,owner_id FROM research_jobs WHERE status='QUEUED' AND id=? LIMIT 1 FOR UPDATE SKIP LOCKED",target.toString());
            if (rows.isEmpty()) return null;
            var row = rows.getFirst(); var id = UUID.fromString((String)row.get("id")); String owner = (String)row.get("owner_id");
            jdbc.update("UPDATE research_jobs SET status='RUNNING',run_owner=?,started_at=CURRENT_TIMESTAMP(6),version=version+1,updated_at=CURRENT_TIMESTAMP(6) WHERE id=?",workerOwner.toString(),id.toString());
            var job = read(owner,id); event(job,"JOB_RUNNING",Map.of("state",job.status()));
            var snapshots = jdbc.queryForMap("SELECT model_snapshot,harness_snapshot FROM research_jobs WHERE id=?",id.toString());
            var requirements = jdbc.queryForObject("SELECT requirements FROM request_revisions WHERE id=?",String.class,job.revisionId().toString());
            try {
                return new Run(job,json.readValue((String)snapshots.get("model_snapshot"),InferenceConfiguration.class),
                        json.readValue((String)snapshots.get("harness_snapshot"),HarnessProperties.class),json.readValue(requirements,TravelRequirements.class));
            } catch(Exception invalidSnapshot) {
                jdbc.update("UPDATE research_jobs SET status='FAILED',outcome_code='INVALID_SNAPSHOT',version=version+1,updated_at=CURRENT_TIMESTAMP(6) WHERE id=?",id.toString());
                event(job,"JOB_FINISHED",Map.of("state",JobState.FAILED,"code","INVALID_SNAPSHOT"));
                return null;
            }
        });
    }
    private Job lock(Run run, UUID worker) {
        var row = jdbc.queryForMap("SELECT run_owner FROM research_jobs WHERE id=? FOR UPDATE",run.job.id.toString());
        if (!worker.toString().equals(row.get("run_owner"))) throw new ResearchFailure("LOST_OWNERSHIP");
        var job=read(run.job.ownerId.toString(),run.job.id);
        if(job.status!=JobState.RUNNING && job.status!=JobState.CANCEL_REQUESTED) throw new ResearchFailure("LOST_OWNERSHIP");
        return job;
    }
    private void active(Job job, HarnessProperties harness) {
        if (job.status != JobState.RUNNING) throw new ResearchFailure("CANCELLED");
        if (job.startedAt.plusSeconds(harness.jobSeconds()).isBefore(Instant.now())) throw new ResearchFailure("JOB_DEADLINE");
    }
    public UUID begin(Run run, UUID worker, String kind) {
        return tx.execute(status -> {
            var job = lock(run,worker); active(job,run.harness);
            String column; int used,limit;
            switch(kind) {
                case "SEARCH" -> { column="searches"; used=job.searches; limit=run.harness.searches(); }
                case "FETCH" -> { column="pages"; used=job.pages; limit=run.harness.pages(); }
                case "MODEL" -> { column="model_turns"; used=job.modelTurns; limit=run.harness.modelTurns(); }
                default -> throw new IllegalArgumentException("Unknown task kind");
            }
            if (used >= limit) throw new ResearchFailure("BUDGET_EXHAUSTED");
            jdbc.update("UPDATE research_jobs SET "+column+"="+column+"+1,version=version+1,updated_at=CURRENT_TIMESTAMP(6) WHERE id=?",job.id.toString());
            UUID id=UUID.randomUUID();
            jdbc.update("INSERT INTO research_tasks(id,job_id,context_id,owner_id,kind,status) VALUES (?,?,?,?,?,'RUNNING')",id.toString(),job.id.toString(),job.contextId.toString(),job.ownerId.toString(),kind);
            event(job,"TASK_STARTED",Map.of("taskId",id,"kind",kind)); return id;
        });
    }
    public void taskEnd(Run run, UUID worker, UUID task, String code, Map<String,?> details) {
        tx.executeWithoutResult(status -> {
            var job=lock(run,worker);
            String state = job.status==JobState.CANCEL_REQUESTED ? "CANCELLED" : code==null ? "SUCCEEDED" : "FAILED";
            int changed=jdbc.update("UPDATE research_tasks SET status=?,outcome_code=?,updated_at=CURRENT_TIMESTAMP(6) WHERE id=? AND job_id=? AND status='RUNNING'",state,code,task.toString(),job.id.toString());
            if(changed==0) return;
            var fields=new HashMap<String,Object>(); if(!"CANCELLED".equals(state)) fields.putAll(details); fields.put("taskId",task); fields.put("state",state); if(code!=null) fields.put("code",code);
            event(job,"TASK_FINISHED",fields);
        });
    }
    public UUID save(Run run, UUID worker, SafeWebClient.Page page, PageExtractor.Extracted extracted) {
        return tx.execute(status -> {
            var job=lock(run,worker); active(job,run.harness);
            UUID source=UUID.randomUUID();
            jdbc.update("INSERT INTO source_snapshots(id,job_id,context_id,owner_id,final_url,retrieved_at,content_hash,content,requested_url,response_status,content_type,title) VALUES (?,?,?,?,?,?,?,?,?,?,?,?)",
                    source.toString(),job.id.toString(),job.contextId.toString(),job.ownerId.toString(),page.finalUrl().toString(),java.sql.Timestamp.from(page.retrievedAt()),extracted.hash(),extracted.text(),page.requestedUrl().toString(),page.status(),page.contentType(),extracted.title());
            int count=0;
            for(int start=0;start<extracted.text().length();start+=3000) {
                int end=Math.min(start+3000,extracted.text().length()); String text=extracted.text().substring(start,end);
                if(text.isBlank()) continue;
                jdbc.update("INSERT INTO evidence_passages(id,source_id,context_id,owner_id,start_offset,end_offset,passage) VALUES (?,?,?,?,?,?,?)",
                        UUID.randomUUID().toString(),source.toString(),job.contextId.toString(),job.ownerId.toString(),start,end,text); count++;
            }
            event(job,"SOURCE_SAVED",Map.of("sourceId",source,"passageCount",count)); return source;
        });
    }
    public boolean cancelled(Run run, UUID worker) {
        var row=jdbc.queryForMap("SELECT status,run_owner FROM research_jobs WHERE id=?",run.job.id.toString());
        return !"RUNNING".equals(row.get("status")) || !worker.toString().equals(row.get("run_owner"));
    }
    public Job cancel(String owner,UUID id) {
        return tx.execute(status -> {
            jdbc.queryForList("SELECT id FROM research_jobs WHERE id=? AND owner_id=? FOR UPDATE",id.toString(),owner);
            var job=read(owner,id);
            JobState next=job.status==JobState.QUEUED ? JobState.CANCELLED : job.status==JobState.RUNNING ? JobState.CANCEL_REQUESTED : job.status;
            if(next!=job.status) {
                jdbc.update("UPDATE research_jobs SET status=?,version=version+1,updated_at=CURRENT_TIMESTAMP(6) WHERE id=?",next.name(),id.toString());
                event(job,"JOB_STATE",Map.of("state",next));
            }
            return read(owner,id);
        });
    }
    public void finish(Run run,UUID worker,JobState requested,String code) {
        finish(run,worker,requested,code,null);
    }
    /** Result and terminal state commit atomically under the same ownership/cancellation fence. */
    public void finish(Run run,UUID worker,JobState requested,String code,ValidatedResearch result) {
        tx.executeWithoutResult(status -> {
            var job=lock(run,worker);
            JobState next=job.status==JobState.CANCEL_REQUESTED ? JobState.CANCELLED : requested;
            if(!job.status.permits(next)) throw new ResearchFailure("INVALID_TRANSITION");
            if(result!=null && next!=JobState.CANCELLED && next!=JobState.INTERRUPTED) {
                if(!job.revisionId.equals(result.revisionId())) throw new ResearchFailure("WRONG_RESULT_REVISION");
                jdbc.update("INSERT INTO validated_research_results(job_id,context_id,owner_id,validator_version,result) VALUES (?,?,?,?,?)",
                        job.id.toString(),job.contextId.toString(),job.ownerId.toString(),result.validatorVersion(),json.writeValueAsString(result));
                event(job,"RESULT_VALIDATED",Map.of("complete",result.complete(),"validatorVersion",result.validatorVersion(),
                        "supportedCount",result.fields().stream().filter(f->f.state()==com.trippilot.validation.FieldState.SUPPORTED).count(),
                        "gapCount",result.fields().stream().filter(f->f.state()!=com.trippilot.validation.FieldState.SUPPORTED).count()));
            }
            jdbc.update("UPDATE research_jobs SET status=?,outcome_code=?,version=version+1,updated_at=CURRENT_TIMESTAMP(6) WHERE id=?",next.name(),code,job.id.toString());
            jdbc.update("UPDATE research_tasks SET status=?,outcome_code=?,updated_at=CURRENT_TIMESTAMP(6) WHERE job_id=? AND status='RUNNING'",next==JobState.CANCELLED ? "CANCELLED" : next==JobState.INTERRUPTED ? "INTERRUPTED" : "FAILED",code,job.id.toString());
            event(job,"JOB_FINISHED",Map.of("state",next,"code",code==null ? "EVIDENCE_ACQUIRED" : code));
        });
    }
    public ValidatedResearch result(String owner,UUID job) {
        read(owner,job);
        var rows=jdbc.queryForList("SELECT result FROM validated_research_results WHERE job_id=? AND owner_id=?",String.class,job.toString(),owner);
        if(rows.isEmpty()) throw new ApiException(HttpStatus.NOT_FOUND,"RESULT_NOT_READY","No validated result is available for this job.");
        var harness=json.readValue(jdbc.queryForObject("SELECT harness_snapshot FROM research_jobs WHERE id=? AND owner_id=?",String.class,job.toString(),owner),HarnessProperties.class);
        return com.trippilot.validation.EvidenceValidator.refresh(json.readValue(rows.getFirst(),ValidatedResearch.class),harness,Instant.now());
    }
    public List<Task> tasks(String owner,UUID job) {
        read(owner,job);
        return jdbc.query("SELECT id,kind,status,outcome_code FROM research_tasks WHERE job_id=? AND owner_id=? ORDER BY created_at,id",(rs,n)->new Task(UUID.fromString(rs.getString(1)),rs.getString(2),rs.getString(3),rs.getString(4)),job.toString(),owner);
    }
    public List<Source> sources(String owner,UUID job) {
        read(owner,job);
        return jdbc.query("SELECT * FROM source_snapshots WHERE job_id=? AND owner_id=? ORDER BY retrieved_at,id",(rs,n)-> {
            UUID source=UUID.fromString(rs.getString("id"));
            var passages=jdbc.query("SELECT id,start_offset,end_offset,passage FROM evidence_passages WHERE source_id=? AND owner_id=? ORDER BY start_offset",(p,m)->new Passage(UUID.fromString(p.getString(1)),p.getInt(2),p.getInt(3),p.getString(4)),source.toString(),owner);
            return new Source(source,rs.getString("requested_url"),rs.getString("final_url"),rs.getTimestamp("retrieved_at").toInstant(),rs.getString("content_type"),rs.getString("title"),rs.getString("content_hash"),rs.getString("content"),passages);
        },job.toString(),owner);
    }
    public List<Event> events(String owner,UUID job,long after) {
        read(owner,job);
        return jdbc.query("SELECT id,event_type,data,created_at FROM research_events WHERE job_id=? AND owner_id=? AND id>? ORDER BY id LIMIT 100",
                (rs,n)->new Event(rs.getLong(1),rs.getString(2),json.readValue(rs.getString(3),Map.class),rs.getTimestamp(4).toInstant()),job.toString(),owner,after);
    }
    private void event(Job job,String type,Map<String,?> data) {
        var allowlisted=new HashMap<String,Object>(); data.forEach((key,value)-> { if(TRACE_KEYS.contains(key)) allowlisted.put(key,value); });
        jdbc.update("INSERT INTO research_events(job_id,context_id,owner_id,event_type,data) VALUES (?,?,?,?,?)",job.id.toString(),job.contextId.toString(),job.ownerId.toString(),type,json.writeValueAsString(TraceRedactor.redact(allowlisted,List.of())));
    }
    public static boolean terminal(JobState state) { return switch(state) { case QUEUED,RUNNING,CANCEL_REQUESTED -> false; default -> true; }; }
    private static ApiException missing() { return new ApiException(HttpStatus.NOT_FOUND,"NOT_FOUND","Research record not found."); }
}
