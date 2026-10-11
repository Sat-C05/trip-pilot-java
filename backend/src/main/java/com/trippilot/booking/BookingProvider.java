package com.trippilot.booking;

import static com.trippilot.booking.BookingContracts.*;
import java.net.URI;

public interface BookingProvider {
    Inventory inventory(URI endpoint,Scope scope);
    Quote quote(URI endpoint,QuoteRequest request);
    Quote readQuote(URI endpoint,String id);
    Hold hold(URI endpoint,HoldRequest request);
    Status submit(URI endpoint,Submit request);
    Status status(URI endpoint,String key);
    class Failure extends RuntimeException {
        public final String code;public final boolean definitive;
        public Failure(String code,boolean definitive){super(code);this.code=code;this.definitive=definitive;}
    }
}
