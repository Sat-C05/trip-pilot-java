package com.trippilot.booking;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;
import java.security.Principal;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/booking")
public class BookingController {
    private final BookingService service;
    private final String label;
    public BookingController(BookingService service,@Value("${trippilot.booking.environment-label:Demo provider — simulated stays, no payment or real reservations}") String label){this.service=service;this.label=label;}
    public record Capability(boolean enabled,String environmentLabel) {}
    public record QuoteInput(@NotBlank @Pattern(regexp="[A-Za-z0-9._:-]{1,100}") String itemId) {}
    public record Authorization(@NotNull UUID quoteId,@NotNull @DecimalMin("0.01") @Digits(integer=10,fraction=2) BigDecimal ceiling,@NotNull Instant authorizedUntil,@NotBlank @Pattern(regexp="[A-Za-z0-9._:-]{1,100}") String requestKey,@AssertTrue boolean accepted) {}
    @GetMapping("/capability") Capability capability(){return new Capability(service.enabled(),label);}
    @GetMapping("/quotes/{id}") BookingService.StoredQuote quote(Principal p,@PathVariable UUID id){return service.readQuote(p.getName(),id);}
    @PostMapping("/trips/{id}/inventory") BookingContracts.Inventory inventory(Principal p,@PathVariable UUID id){return service.inventory(p.getName(),id);}
    @PostMapping("/trips/{id}/quotes") BookingService.StoredQuote quote(Principal p,@PathVariable UUID id,@Valid @RequestBody QuoteInput input){return service.quote(p.getName(),id,input.itemId());}
    @PostMapping("/trips/{id}/authorizations") BookingService.Attempt authorize(Principal p,@PathVariable UUID id,@Valid @RequestBody Authorization input){return service.authorize(p.getName(),id,input.quoteId(),input.ceiling(),input.authorizedUntil(),input.requestKey());}
    @GetMapping("/trips/{id}/attempts") List<BookingService.Attempt> attempts(Principal p,@PathVariable UUID id){return service.attempts(p.getName(),id);}
    @GetMapping("/attempts/{id}") BookingService.Attempt read(Principal p,@PathVariable UUID id){return service.read(p.getName(),id);}
    @PostMapping("/attempts/{id}/execute") BookingService.Attempt execute(Principal p,@PathVariable UUID id){return service.execute(p.getName(),id);}
    @PostMapping("/attempts/{id}/status") BookingService.Attempt status(Principal p,@PathVariable UUID id){return service.reconcile(p.getName(),id);}
    @PostMapping("/attempts/{id}/revoke") BookingService.Attempt revoke(Principal p,@PathVariable UUID id){return service.revoke(p.getName(),id);}
}
