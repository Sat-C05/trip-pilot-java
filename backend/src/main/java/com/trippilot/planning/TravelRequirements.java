package com.trippilot.planning;

import jakarta.validation.constraints.*;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import com.fasterxml.jackson.annotation.JsonFormat;

/** Nullable essential fields mean clarification is required, never guessed defaults. */
public record TravelRequirements(@Size(max = 200) String departure,
                                 @Size(max = 500) String destinationIntent,
                                 @NotBlank @Pattern(regexp = "IN") String country,
                                 LocalDate arrival, LocalDate departureDate,
                                 @JsonFormat(shape = JsonFormat.Shape.STRING) @DecimalMin("0.01") BigDecimal budget,
                                 @Pattern(regexp = "[A-Z]{3}") String currency,
                                 @Min(1) @Max(20) Integer travelers,
                                 @NotNull @Size(max = 20) List<@NotBlank @Size(max = 200) String> preferences) {
    public TravelRequirements { if (preferences != null) { preferences = List.copyOf(preferences); } }
    @AssertTrue
    @com.fasterxml.jackson.annotation.JsonIgnore
    public boolean isDateOrderValid() { return arrival == null || departureDate == null || departureDate.isAfter(arrival); }
    @AssertTrue
    @com.fasterxml.jackson.annotation.JsonIgnore
    public boolean isCurrencyValid() {
        if (currency == null) { return true; }
        try { java.util.Currency.getInstance(currency); return true; } catch (IllegalArgumentException invalid) { return false; }
    }
}
