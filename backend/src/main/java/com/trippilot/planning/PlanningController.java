package com.trippilot.planning;

import com.trippilot.presentation.ApiException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.security.Principal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/planning/sessions")
public class PlanningController {
    private final JdbcTemplate jdbc;
    public PlanningController(JdbcTemplate jdbc) { this.jdbc = jdbc; }
    public record Create(@NotBlank @Size(max = 120) String title) {}
    public record Session(UUID id, String title, Instant createdAt) {}

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Transactional
    Session create(Principal principal, @Valid @RequestBody Create input) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO planning_sessions(id,owner_id,title) VALUES (?,?,?)", id.toString(), principal.getName(), input.title().strip());
        jdbc.update("INSERT INTO trip_contexts(id,session_id,owner_id,kind) VALUES (?,?,?,'PLANNING')", UUID.randomUUID().toString(), id.toString(), principal.getName());
        return read(principal, id);
    }

    @GetMapping
    List<Session> list(Principal principal) {
        return jdbc.query("SELECT id,title,created_at FROM planning_sessions WHERE owner_id=? ORDER BY created_at DESC,id DESC LIMIT 100",
                (rs, row) -> new Session(UUID.fromString(rs.getString("id")), rs.getString("title"), rs.getTimestamp("created_at").toInstant()), principal.getName());
    }

    @GetMapping("/{id}")
    Session read(Principal principal, @PathVariable UUID id) {
        var rows = jdbc.query("SELECT id,title,created_at FROM planning_sessions WHERE id=? AND owner_id=?",
                (rs, row) -> new Session(UUID.fromString(rs.getString("id")), rs.getString("title"), rs.getTimestamp("created_at").toInstant()), id.toString(), principal.getName());
        if (rows.isEmpty()) { throw new ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "Planning session not found."); }
        return rows.getFirst();
    }

    @PutMapping("/{id}")
    @Transactional
    Session rename(Principal principal, @PathVariable UUID id, @Valid @RequestBody Create input) {
        read(principal,id);
        jdbc.update("UPDATE planning_sessions SET title=? WHERE id=? AND owner_id=?",input.title().strip(),id.toString(),principal.getName());
        return read(principal,id);
    }
}
