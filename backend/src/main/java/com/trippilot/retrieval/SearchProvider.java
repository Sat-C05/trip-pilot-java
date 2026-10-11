package com.trippilot.retrieval;

import java.net.URI;
import java.util.List;
import java.util.function.BooleanSupplier;

public interface SearchProvider {
    record Hit(String title, URI url) {}
    String id();
    List<Hit> search(String query, int count, int byteLimit, int seconds, BooleanSupplier cancelled);
}
