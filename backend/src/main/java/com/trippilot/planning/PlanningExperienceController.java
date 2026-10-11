package com.trippilot.planning;

import com.trippilot.presentation.ApiException;
import com.trippilot.research.ResearchRepository;
import com.trippilot.validation.ValidatedResearch;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.security.Principal;
import java.time.Instant;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import tools.jackson.databind.json.JsonMapper;

/** Selected contexts own revisions, messages and new evidence; chat is never treated as evidence. */
@RestController
public class PlanningExperienceController {
    private final JdbcTemplate jdbc;
    private final ResearchRepository research;
    private final JsonMapper json=JsonMapper.builder().findAndAddModules().build();
    public PlanningExperienceController(JdbcTemplate jdbc,ResearchRepository research) { this.jdbc=jdbc;this.research=research; }
    public enum Focus { ALL, DESCRIPTION, STAY, TRANSPORT, ACTIVITY, PRICE, AVAILABILITY, POLICY }
    public record Selection(@NotNull UUID jobId) {}
    public record Snapshot(UUID jobId,UUID revisionId,TravelRequirements requirements,ValidatedResearch result) {}
    public record Context(UUID id,UUID sessionId,UUID selectedJobId,String destination,Instant createdAt) {}
    public record Detail(Context context,TravelRequirements requirements,ValidatedResearch selectedResult,List<ResearchRepository.Job> jobs) {}
    public record Followup(@NotNull @Valid TravelRequirements requirements,@NotBlank @Size(max=2000) String question,
            @NotNull Focus focus,@NotBlank @Pattern(regexp="[A-Za-z0-9._:-]{1,100}") String requestKey) {}
    public record Message(UUID id,String role,String content,UUID jobId,Instant createdAt) {}
    public record Clarification(TravelRequirements requirements,List<String> missing,String message) {}

    @GetMapping("/api/planning/sessions/{session}/jobs")
    List<ResearchRepository.Job> jobs(Principal principal,@PathVariable UUID session) {
        return research.jobs(principal.getName(),session,research.planningContext(principal.getName(),session));
    }
    @GetMapping("/api/planning/revisions/{id}")
    ResearchRepository.Revision revision(Principal principal,@PathVariable UUID id) { return research.revision(principal.getName(),id); }
    @PostMapping("/api/planning/clarification")
    Clarification clarification(@Valid @RequestBody TravelRequirements input) {
        var missing=new ArrayList<String>();
        if(input.departure()==null || input.departure().isBlank()) missing.add("departure");
        if(input.destinationIntent()==null || input.destinationIntent().isBlank()) missing.add("destinationIntent");
        if(input.arrival()==null) missing.add("arrival");if(input.departureDate()==null) missing.add("departureDate");
        if(input.travelers()==null) missing.add("travelers");if(input.budget()==null) missing.add("budget");if(input.currency()==null) missing.add("currency");
        return new Clarification(input,List.copyOf(missing),missing.isEmpty()?"Ready for guide research; provider quotes remain unavailable.":"Please clarify the listed details. You may explore destination guides while these remain unknown.");
    }
    private void session(String owner,UUID id,boolean lock) {
        if(jdbc.queryForList("SELECT id FROM planning_sessions WHERE id=? AND owner_id=?"+(lock?" FOR UPDATE":""),String.class,id.toString(),owner).isEmpty()) throw missing();
    }
    private Snapshot snapshot(String owner,UUID context) {
        var rows=jdbc.queryForList("SELECT selection_snapshot FROM trip_contexts WHERE id=? AND owner_id=? AND kind='SELECTED'",String.class,context.toString(),owner);
        if(rows.isEmpty()) throw missing();return json.readValue(rows.getFirst(),Snapshot.class);
    }
    private Context context(String owner,UUID id) {
        var rows=jdbc.query("SELECT id,session_id,selected_job_id,created_at FROM trip_contexts WHERE id=? AND owner_id=? AND kind='SELECTED'",(rs,n)->
            new Context(UUID.fromString(rs.getString(1)),UUID.fromString(rs.getString(2)),UUID.fromString(rs.getString(3)),snapshot(owner,id).requirements().destinationIntent(),rs.getTimestamp(4).toInstant()),id.toString(),owner);
        if(rows.isEmpty()) throw missing();return rows.getFirst();
    }
    @PostMapping("/api/planning/sessions/{session}/selected-trips") @ResponseStatus(HttpStatus.CREATED) @Transactional
    Context select(Principal principal,@PathVariable UUID session,@Valid @RequestBody Selection input) {
        String owner=principal.getName();session(owner,session,true);
        var job=research.read(owner,input.jobId());
        if(!job.sessionId().equals(session) || !job.contextId().equals(research.planningContext(owner,session))) throw missing();
        var existing=jdbc.queryForList("SELECT id FROM trip_contexts WHERE session_id=? AND owner_id=? AND selected_job_id=?",String.class,session.toString(),owner,job.id().toString());
        if(!existing.isEmpty()) return context(owner,UUID.fromString(existing.getFirst()));
        var result=research.result(owner,job.id());
        if(!ResearchRepository.terminal(job.status()) || result.fields().stream().noneMatch(f->f.state()==com.trippilot.validation.FieldState.SUPPORTED || f.state()==com.trippilot.validation.FieldState.CONFLICTING))
            throw new ApiException(HttpStatus.CONFLICT,"NO_SUPPORTED_SELECTION","Select a completed option with current validated evidence. Refresh stale or unavailable options first.");
        var requirements=research.revision(owner,job.revisionId()).requirements();UUID id=UUID.randomUUID();
        jdbc.update("INSERT INTO trip_contexts(id,session_id,owner_id,kind,selection_snapshot,selected_job_id) VALUES (?,?,?,'SELECTED',?,?)",
                id.toString(),session.toString(),owner,json.writeValueAsString(new Snapshot(job.id(),job.revisionId(),requirements,result)),job.id().toString());
        research.revise(owner,session,id,requirements);
        return context(owner,id);
    }
    @GetMapping("/api/planning/sessions/{session}/selected-trips")
    List<Context> selected(Principal principal,@PathVariable UUID session) {
        String owner=principal.getName();session(owner,session,false);
        return jdbc.queryForList("SELECT id FROM trip_contexts WHERE session_id=? AND owner_id=? AND kind='SELECTED' ORDER BY created_at,id",String.class,session.toString(),owner)
                .stream().map(id->context(owner,UUID.fromString(id))).toList();
    }
    @GetMapping("/api/planning/trips/{id}")
    Detail detail(Principal principal,@PathVariable UUID id) {
        String owner=principal.getName();var context=context(owner,id);
        String revision=jdbc.queryForObject("SELECT id FROM request_revisions WHERE context_id=? AND owner_id=? ORDER BY revision_no DESC LIMIT 1",String.class,id.toString(),owner);
        return new Detail(context,research.revision(owner,UUID.fromString(revision)).requirements(),research.result(owner,context.selectedJobId()),research.jobs(owner,context.sessionId(),id));
    }
    @GetMapping("/api/planning/trips/{id}/messages")
    List<Message> messages(Principal principal,@PathVariable UUID id) {
        context(principal.getName(),id);
        return jdbc.query("SELECT id,role,content,job_id,created_at FROM conversation_messages WHERE context_id=? AND owner_id=? ORDER BY created_at,id LIMIT 200",
                (rs,n)->new Message(UUID.fromString(rs.getString(1)),rs.getString(2),rs.getString(3),rs.getString(4)==null?null:UUID.fromString(rs.getString(4)),rs.getTimestamp(5).toInstant()),id.toString(),principal.getName());
    }
    @PostMapping("/api/planning/trips/{id}/research") @ResponseStatus(HttpStatus.ACCEPTED) @Transactional
    ResearchRepository.Job followup(Principal principal,@PathVariable UUID id,@Valid @RequestBody Followup input) {
        String owner=principal.getName();var context=context(owner,id);session(owner,context.sessionId(),true);
        var existing=jdbc.queryForList("SELECT id,context_id FROM research_jobs WHERE owner_id=? AND request_key=?",owner,input.requestKey());
        if(!existing.isEmpty()) {
            var job=research.read(owner,UUID.fromString((String)existing.getFirst().get("id")));
            if(!job.contextId().equals(id)) throw new ApiException(HttpStatus.CONFLICT,"IDEMPOTENCY_CONFLICT","Request key belongs to another context.");
            String question=jdbc.queryForObject("SELECT content FROM conversation_messages WHERE job_id=? AND role='USER'",String.class,job.id().toString());
            if(!job.contextId().equals(id) || !input.requirements().equals(research.revision(owner,job.revisionId()).requirements()) || !input.question().strip().equals(question) || !input.focus().name().equals(research.focus(owner,job.id())))
                throw new ApiException(HttpStatus.CONFLICT,"IDEMPOTENCY_CONFLICT","Request key was already used for another payload.");
            return job;
        }
        if(!context.destination().equals(input.requirements().destinationIntent())) throw new ApiException(HttpStatus.BAD_REQUEST,"DESTINATION_LOCKED","Research another destination in a separate trip context.");
        var revision=research.revise(owner,context.sessionId(),id,input.requirements());
        var job=research.admit(owner,context.sessionId(),id,revision.id(),input.requestKey(),null,input.focus().name());
        jdbc.update("INSERT INTO conversation_messages(id,context_id,session_id,owner_id,role,content,job_id) VALUES (?,?,?,?,'USER',?,?)",
                UUID.randomUUID().toString(),id.toString(),context.sessionId().toString(),owner,input.question().strip(),job.id().toString());
        return job;
    }
    private static ApiException missing() { return new ApiException(HttpStatus.NOT_FOUND,"NOT_FOUND","Planning context not found."); }
}
