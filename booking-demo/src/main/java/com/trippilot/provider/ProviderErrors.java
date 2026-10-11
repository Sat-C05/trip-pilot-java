package com.trippilot.provider;

import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestControllerAdvice
public class ProviderErrors {
    @ExceptionHandler(ResponseStatusException.class)
    ResponseEntity<?> known(ResponseStatusException failure){return ResponseEntity.status(failure.getStatusCode()).body(Map.of("code",failure.getReason()==null?"PROVIDER_REJECTED":failure.getReason()));}
    @ExceptionHandler(Exception.class)
    ResponseEntity<?> unknown(Exception failure){return ResponseEntity.status(500).body(Map.of("code","PROVIDER_UNAVAILABLE"));}
}
