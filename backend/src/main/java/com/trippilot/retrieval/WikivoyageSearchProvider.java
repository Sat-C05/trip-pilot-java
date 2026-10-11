package com.trippilot.retrieval;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/** Credential-free travel-guide search, not live inventory/quotes or whole-web search. */
@Component
public class WikivoyageSearchProvider implements SearchProvider {
    private final SafeWebClient web;
    private final JsonMapper json = JsonMapper.builder().build();
    public WikivoyageSearchProvider(SafeWebClient web) { this.web = web; }
    public String id() { return "wikivoyage-v1"; }
    public List<Hit> search(String query, int count, int byteLimit, int seconds, BooleanSupplier cancelled) {
        if (query == null || query.isBlank() || query.length() > 512 || count < 1 || count > 10) throw new RetrievalFailure(RetrievalFailure.Code.SEARCH_INVALID);
        URI url = URI.create("https://en.wikivoyage.org/w/api.php?action=query&list=search&srnamespace=0&srprop=&format=json&formatversion=2&srlimit="
                + count + "&srsearch=" + URLEncoder.encode(query, StandardCharsets.UTF_8));
        var page = web.get(url, byteLimit, seconds, cancelled);
        try {
            var data = json.readTree(page.bytes());
            if (!data.path("query").path("search").isArray() || data.has("error")) throw new RetrievalFailure(RetrievalFailure.Code.SEARCH_INVALID);
            var hits = new ArrayList<Hit>();
            for (var hit : data.path("query").path("search")) {
                String title = hit.path("title").asText();
                if (title.isBlank() || title.length() > 300) continue;
                hits.add(new Hit(title, URI.create("https://en.wikivoyage.org/wiki/" + URLEncoder.encode(title.replace(' ', '_'), StandardCharsets.UTF_8).replace("+", "%20"))));
                if (hits.size() == count) break;
            }
            return List.copyOf(hits);
        } catch (RetrievalFailure failure) { throw failure;
        } catch (Exception ignored) { throw new RetrievalFailure(RetrievalFailure.Code.SEARCH_INVALID); }
    }
}
