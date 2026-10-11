package com.trippilot.presentation;

import com.trippilot.planning.TravelRequirements;
import com.trippilot.research.ResearchRepository;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.security.Principal;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

@RestController
public class ResearchController {
    private final ResearchRepository repository;
    public ResearchController(ResearchRepository repository) { this.repository=repository; }
    public record Start(@NotNull UUID revisionId,@NotBlank @Pattern(regexp="[A-Za-z0-9._:-]{1,100}") String requestKey,UUID retryOf) {}
    @PostMapping("/api/planning/sessions/{id}/revisions") @ResponseStatus(HttpStatus.CREATED)
    ResearchRepository.Revision revise(Principal principal,@PathVariable UUID id,@Valid @RequestBody TravelRequirements input) { return repository.revise(principal.getName(),id,input); }
    @PostMapping("/api/planning/sessions/{id}/jobs") @ResponseStatus(HttpStatus.ACCEPTED)
    ResearchRepository.Job start(Principal principal,@PathVariable UUID id,@Valid @RequestBody Start input) { return repository.admit(principal.getName(),id,input.revisionId(),input.requestKey(),input.retryOf()); }
    @GetMapping("/api/research/jobs/{id}")
    ResearchRepository.Job read(Principal principal,@PathVariable UUID id) { return repository.read(principal.getName(),id); }
    @PostMapping("/api/research/jobs/{id}/cancel")
    ResearchRepository.Job cancel(Principal principal,@PathVariable UUID id) { return repository.cancel(principal.getName(),id); }
    @GetMapping("/api/research/jobs/{id}/tasks")
    List<ResearchRepository.Task> tasks(Principal principal,@PathVariable UUID id) { return repository.tasks(principal.getName(),id); }
    @GetMapping("/api/research/jobs/{id}/sources")
    List<ResearchRepository.Source> sources(Principal principal,@PathVariable UUID id) { return repository.sources(principal.getName(),id); }
    @GetMapping("/api/research/jobs/{id}/result")
    com.trippilot.validation.ValidatedResearch result(Principal principal,@PathVariable UUID id) { return repository.result(principal.getName(),id); }
    @GetMapping("/api/research/jobs/{id}/images")
    List<com.trippilot.retrieval.CommonsImages.Image> images(Principal principal,@PathVariable UUID id) { return repository.images(principal.getName(),id); }
    @GetMapping("/api/research/jobs/{id}/event-log")
    List<ResearchRepository.Event> events(Principal principal,@PathVariable UUID id,@RequestParam(defaultValue="0") long after) {
        if(after<0) throw new ApiException(HttpStatus.BAD_REQUEST,"INVALID_EVENT_CURSOR","Event ID must be non-negative.");
        return repository.events(principal.getName(),id,after);
    }
}
