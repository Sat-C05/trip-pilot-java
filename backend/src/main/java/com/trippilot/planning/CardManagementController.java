package com.trippilot.planning;

import com.trippilot.presentation.ApiException;
import com.trippilot.research.ResearchRepository;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.security.Principal;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

/** CRUD for personal card metadata. Research scope, evidence and bookings stay immutable. */
@RestController
@RequestMapping("/api/planning/cards")
public class CardManagementController {
    private final JdbcTemplate jdbc;
    private final ResearchRepository research;
    public CardManagementController(JdbcTemplate jdbc,ResearchRepository research) { this.jdbc=jdbc;this.research=research; }
    public record Edit(@NotBlank @Size(max=120) String title,@NotNull @Size(max=2000) String notes) {}
    private ResearchRepository.Job lock(String owner,UUID id) {
        if(jdbc.queryForList("SELECT id FROM research_jobs WHERE id=? AND owner_id=? FOR UPDATE",String.class,id.toString(),owner).isEmpty())
            throw new ApiException(HttpStatus.NOT_FOUND,"NOT_FOUND","Card not found.");
        return research.read(owner,id);
    }
    @GetMapping("/{id}")
    ResearchRepository.Job read(Principal principal,@PathVariable UUID id) { return research.read(principal.getName(),id); }
    @PutMapping("/{id}") @Transactional
    ResearchRepository.Job edit(Principal principal,@PathVariable UUID id,@Valid @RequestBody Edit input) {
        var job=lock(principal.getName(),id);
        if(job.cardDeletedAt()!=null) throw new ApiException(HttpStatus.CONFLICT,"CARD_IN_TRASH","Restore this card before editing.");
        jdbc.update("UPDATE research_jobs SET card_title=?,card_notes=? WHERE id=? AND owner_id=?",input.title().strip(),input.notes().strip(),id.toString(),principal.getName());
        return research.read(principal.getName(),id);
    }
    @DeleteMapping("/{id}") @ResponseStatus(HttpStatus.NO_CONTENT) @Transactional
    void delete(Principal principal,@PathVariable UUID id) {
        var job=lock(principal.getName(),id);
        if(!ResearchRepository.terminal(job.status())) throw new ApiException(HttpStatus.CONFLICT,"RESEARCH_ACTIVE","Cancel running research and wait for it to finish before deleting the card.");
        jdbc.update("UPDATE research_jobs SET card_deleted_at=COALESCE(card_deleted_at,CURRENT_TIMESTAMP(6)) WHERE id=? AND owner_id=?",id.toString(),principal.getName());
    }
    @PostMapping("/{id}/restore") @Transactional
    ResearchRepository.Job restore(Principal principal,@PathVariable UUID id) {
        lock(principal.getName(),id);
        jdbc.update("UPDATE research_jobs SET card_deleted_at=NULL WHERE id=? AND owner_id=?",id.toString(),principal.getName());
        return research.read(principal.getName(),id);
    }
}
