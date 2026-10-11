package com.trippilot.retrieval;

import static org.junit.jupiter.api.Assertions.*;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import tools.jackson.databind.json.JsonMapper;

class CommonsImagesTest {
    final JsonMapper json=JsonMapper.builder().build();
    String metadata(String author,String license,String thumb) {
        return "{\"query\":{\"pages\":[{\"title\":\"File:Jaipur.jpg\",\"imageinfo\":[{\"thumburl\":\""+thumb+"\",\"descriptionurl\":\"https://commons.wikimedia.org/wiki/File:Jaipur.jpg\",\"extmetadata\":{\"Artist\":{\"value\":\""+author+"\"},\"LicenseShortName\":{\"value\":\""+license+"\"},\"LicenseUrl\":{\"value\":\"https://creativecommons.org/licenses/by-sa/4.0/\"}}}]}]}}";
    }
    @Test void attributionIsPlainTextAndUnlicensedOrUnsafeImagesAreRejected() {
        String url="https://upload.wikimedia.org/wikipedia/commons/thumb/0/01/Jaipur.jpg/640px-Jaipur.jpg";
        var image=CommonsImages.parse(json.readTree(metadata("<b>Photographer</b>","CC BY-SA 4.0",url)),Instant.now()).orElseThrow();
        assertEquals("Photographer",image.author());assertEquals("CC BY-SA 4.0",image.license());
        assertTrue(CommonsImages.parse(json.readTree(metadata("","CC BY-SA 4.0",url)),Instant.now()).isEmpty());
        assertTrue(CommonsImages.parse(json.readTree(metadata("A","All rights reserved",url)),Instant.now()).isEmpty());
        assertTrue(CommonsImages.parse(json.readTree(metadata("A","CC BY-SA 4.0","https://127.0.0.1/private.jpg")),Instant.now()).isEmpty());
        assertTrue(CommonsImages.parse(json.readTree(metadata("A","CC BY-SA 4.0","javascript:alert(1)")),Instant.now()).isEmpty());
        assertTrue(CommonsImages.parse(json.readTree("{}"),Instant.now()).isEmpty());
    }
    @Test void onlyRasterFileLinksFromRetrievedWikivoyageArticlesAreConsidered() {
        String html="<div class='mw-parser-output'><a class='mw-file-description' href='/wiki/File:Jaipur.jpg'><img width='300' height='200' src='ignored'></a></div>";
        URI url=URI.create("https://en.wikivoyage.org/wiki/Jaipur");
        assertEquals("File:Jaipur.jpg",CommonsImages.file(new SafeWebClient.Page(url,url,Instant.now(),200,"text/html",html.getBytes(StandardCharsets.UTF_8))).orElseThrow());
        URI other=URI.create("https://example.org/wiki/Jaipur");
        assertTrue(CommonsImages.file(new SafeWebClient.Page(other,other,Instant.now(),200,"text/html",html.getBytes(StandardCharsets.UTF_8))).isEmpty());
        assertTrue(CommonsImages.file(new SafeWebClient.Page(url,url,Instant.now(),200,"text/html",html.replace(".jpg",".svg").getBytes(StandardCharsets.UTF_8))).isEmpty());
    }
    @Test @EnabledIfEnvironmentVariable(named="PHASE2_ONLINE_TESTS",matches="true")
    void actualJaipurGuideImageCarriesCommonsAttribution() {
        var web=new SafeWebClient();
        try {
            var guide=web.get(URI.create("https://en.wikivoyage.org/wiki/Jaipur"),2097152,20,()->false);
            String file=CommonsImages.file(guide).orElseThrow();
            var image=new CommonsImages(web).fetch(file,262144,20,()->false).orElseThrow();
            assertFalse(image.author().isBlank());assertFalse(image.license().isBlank());assertTrue(java.util.Set.of("upload.wikimedia.org","thumb.wikimedia.org").contains(URI.create(image.url()).getHost()));
        }finally{web.close();}
    }
}
