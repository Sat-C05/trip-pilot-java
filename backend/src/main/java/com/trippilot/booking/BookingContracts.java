package com.trippilot.booking;

import com.fasterxml.jackson.annotation.JsonFormat;
import java.math.BigDecimal;
import java.time.*;
import java.util.List;

/** Provider wire contract. No provider environment changes orchestration. */
public final class BookingContracts {
    private BookingContracts() {}
    public record Scope(String destination,String country,LocalDate arrival,LocalDate departure,int travelers,String currency) {}
    public record Item(String id,String name,String destination,String currency,@JsonFormat(shape=JsonFormat.Shape.STRING) BigDecimal nightlyPerTraveler,int remaining) {}
    public record Inventory(Scope scope,List<Item> items) {}
    public record QuoteRequest(Scope scope,String itemId) {}
    public record Quote(String id,String itemId,String name,Scope scope,@JsonFormat(shape=JsonFormat.Shape.STRING) BigDecimal total,Instant expiresAt,String policy) {}
    public record HoldRequest(String quoteId,String key) {}
    public record Hold(String id,String quoteId,Instant expiresAt,@JsonFormat(shape=JsonFormat.Shape.STRING) BigDecimal total) {}
    public record Submit(String quoteId,String holdId,String key) {}
    public record Status(String key,String state,String bookingId,String quoteId,@JsonFormat(shape=JsonFormat.Shape.STRING) BigDecimal total,String currency) {}
}
