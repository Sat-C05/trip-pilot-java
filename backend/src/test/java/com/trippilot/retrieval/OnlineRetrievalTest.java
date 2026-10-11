package com.trippilot.retrieval;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

@EnabledIfEnvironmentVariable(named="PHASE2_ONLINE_TESTS",matches="true")
class OnlineRetrievalTest {
    @Test void realTravelSearchAndSafeFetchAcquirePublicText() {
        var web=new SafeWebClient();
        try {
            var hits=new WikivoyageSearchProvider(web).search("Jaipur",3,2097152,15,()->false);
            assertFalse(hits.isEmpty());
            var page=web.get(hits.getFirst().url(),2097152,15,()->false);
            var extracted=new PageExtractor().extract(page,30000);
            assertEquals(200,page.status());assertTrue(extracted.text().contains("Jaipur"));assertEquals(64,extracted.hash().length());
            assertTrue(extracted.text().length()>1000);assertTrue(extracted.text().length()<=30000);
        } finally { web.close(); }
    }
}
