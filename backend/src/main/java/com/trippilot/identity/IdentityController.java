package com.trippilot.identity;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.security.Principal;
import org.springframework.http.HttpStatus;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/identity")
public class IdentityController {
    private final IdentityService service;
    public IdentityController(IdentityService service) { this.service = service; }

    public record Registration(@NotBlank @Email @Size(max = 191) String email,
                               @NotBlank @Size(min = 12, max = 72) String password) {}
    public record Csrf(String headerName, String token) {}

    @GetMapping("/csrf")
    Csrf csrf(CsrfToken token) { return new Csrf(token.getHeaderName(), token.getToken()); }

    @PostMapping("/register")
    @ResponseStatus(HttpStatus.CREATED)
    IdentityService.Account register(@Valid @RequestBody Registration registration) {
        return service.register(registration.email(), registration.password());
    }

    @GetMapping("/me")
    IdentityService.Account me(Principal principal) { return service.account(principal.getName()); }
}
