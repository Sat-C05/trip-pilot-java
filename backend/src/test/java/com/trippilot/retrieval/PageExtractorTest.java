package com.trippilot.retrieval;

import static org.junit.jupiter.api.Assertions.*;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class PageExtractorTest {
    @Test void longGuideRetainsPracticalSectionsBeyondTheOpeningCharacterCap() {
        String intro="Jaipur is in Rajasthan. "+"Historical background. ".repeat(200);
        String html="<title>Jaipur guide</title><div id='mw-content-text'><div class='mw-parser-output'><p>"+intro+"</p>"
                +"<div class='mw-heading mw-heading2'><h2>Get in</h2></div><p>Jaipur has a railway station.</p>"
                +"<div class='mw-heading mw-heading2'><h2>See</h2></div><p>Visit the museum in Jaipur.</p>"
                +"<div class='mw-heading mw-heading2'><h2>Sleep</h2></div><p>Jaipur has many hostels in the centre.</p>"
                +"<div class='mw-heading mw-heading2'><h2>Stay safe</h2></div><p>Unrelated tail.</p></div></div>";
        var uri=URI.create("https://en.wikivoyage.org/wiki/Jaipur");
        var page=new SafeWebClient.Page(uri,uri,Instant.now(),200,"text/html",html.getBytes(StandardCharsets.UTF_8));
        String text=new PageExtractor().extract(page,1000).text();assertTrue(text.length()<=1000);
        for(String sentence:java.util.List.of("Jaipur is in Rajasthan.","Jaipur has a railway station.","Visit the museum in Jaipur.","Jaipur has many hostels in the centre."))assertTrue(text.contains(sentence),text);
        assertFalse(text.contains("Unrelated tail."));
    }
    @Test void wikiArticleIsPreferredOverEnclosingMainAndIndicatorWidgets() {
        var html="<title>Jaipur guide</title><main><div class='mw-indicators'><div class='mw-parser-output'>coordinates and widgets</div></div><aside>irrelevant layout text</aside><div id='mw-content-text'><div class='mw-parser-output'><p>Jaipur is in Rajasthan.</p></div></div></main>";
        var uri=URI.create("https://en.wikivoyage.org/wiki/Jaipur");
        var page=new SafeWebClient.Page(uri,uri,Instant.now(),200,"text/html",html.getBytes(StandardCharsets.UTF_8));
        assertEquals("Jaipur is in Rajasthan.",new PageExtractor().extract(page,30000).text());
    }
    @Test void embeddedGuideNavigationCannotCrowdOutLeadOrInvalidateSourceOffsets() {
        var html="<title>Mumbai – Travel guide</title><div class='mw-parser-output'><div id='geoCoord'>19.031872.8487</div><div id='breadcrumbs'>From Wikivoyage Asia &gt; India</div><div class='wpb-topbanner'><h1>Mumbai</h1><div class='wpb-topbanner-toc'><div class='wpb-banner-toc'>Contents "+"districts history hotels ".repeat(100)+"</div></div></div><p>Mumbai is a city in Maharashtra, India.</p><h2>Get in<span class='mw-editsection'>edit</span></h2><p>Mumbai has a railway station.</p></div>";
        var uri=URI.create("https://en.wikivoyage.org/wiki/Mumbai");
        var page=new SafeWebClient.Page(uri,uri,Instant.now(),200,"text/html",html.getBytes(StandardCharsets.UTF_8));
        var text=new PageExtractor().extract(page,30000).text();
        assertTrue(text.startsWith("Mumbai Mumbai is a city in Maharashtra, India."),text);
        assertTrue(text.contains("Mumbai has a railway station."));assertFalse(text.contains("Contents"));assertFalse(text.contains("From Wikivoyage"));assertFalse(text.contains("edit"));assertFalse(text.contains("19.0318"));
    }
}
