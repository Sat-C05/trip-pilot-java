package com.trippilot;

import com.trippilot.config.PrivateApiCacheFilter;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.*;
import static org.junit.jupiter.api.Assertions.*;

class PrivateApiCacheFilterTest {
    @Test void errorsAndStreamsCannotBeCached() throws Exception {
        for (String path : new String[]{"/api/identity/me", "/api/research/jobs/test/events"}) {
            var response = new MockHttpServletResponse();
            new PrivateApiCacheFilter().doFilter(new MockHttpServletRequest("GET", path), response,
                    (request, result) -> ((jakarta.servlet.http.HttpServletResponse) result).setStatus(401));
            assertEquals(401, response.getStatus());
            assertTrue(response.getHeader("Cache-Control").contains("no-store"));
            assertEquals("no-store", response.getHeader("CDN-Cache-Control"));
            assertEquals("no-store", response.getHeader("Vercel-CDN-Cache-Control"));
        }
    }
}
