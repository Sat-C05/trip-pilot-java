package com.trippilot.retrieval;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Optional;
import java.util.function.BooleanSupplier;
import org.jsoup.Jsoup;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** One optional source illustration, only when Commons supplies explicit reusable attribution. */
public final class CommonsImages {
    public record Image(String url,String sourceUrl,String title,String author,String license,String licenseUrl,Instant retrievedAt) {}
    private final SafeWebClient web;
    public CommonsImages(SafeWebClient web) { this.web=web; }
    public static Optional<String> file(SafeWebClient.Page page) {
        if(!"en.wikivoyage.org".equals(page.finalUrl().getHost()) || !page.contentType().startsWith("text/html")) return Optional.empty();
        var doc=Jsoup.parse(page.text(),page.finalUrl().toString());
        for(var anchor:doc.select(".mw-parser-output a.mw-file-description:has(img)")) {
            var img=anchor.selectFirst("img");
            try { if(Integer.parseInt(img.attr("width"))<100 || Integer.parseInt(img.attr("height"))<60) continue; }
            catch(NumberFormatException unknownSize) { continue; }
            String href=anchor.absUrl("href");
            if(href.startsWith("https://en.wikivoyage.org/wiki/File:")) {
                String file=java.net.URLDecoder.decode(href.substring(href.indexOf("/wiki/")+6),StandardCharsets.UTF_8);
                if(file.length()<250 && file.toLowerCase(java.util.Locale.ROOT).matches("file:.*\\.(jpg|jpeg|png|webp)")) return Optional.of(file);
            }
        }
        return Optional.empty();
    }
    public Optional<Image> fetch(String file,int bytes,int seconds,BooleanSupplier cancelled) {
        URI url=URI.create("https://commons.wikimedia.org/w/api.php?action=query&prop=imageinfo&iiprop=url%7Cextmetadata&iiurlwidth=640&format=json&formatversion=2&titles="+URLEncoder.encode(file,StandardCharsets.UTF_8));
        var page=web.get(url,bytes,seconds,cancelled);
        return parse(JsonMapper.builder().build().readTree(page.bytes()),page.retrievedAt());
    }
    public static Optional<Image> parse(JsonNode data,Instant now) {
        for(var page:data.path("query").path("pages")) {
            var info=page.path("imageinfo").path(0);var meta=info.path("extmetadata");
            String url=info.path("thumburl").asText(),source=info.path("descriptionurl").asText();
            String author=plain(meta.path("Artist").path("value").asText()),license=plain(meta.path("LicenseShortName").path("value").asText());
            String licenseUrl=meta.path("LicenseUrl").path("value").asText();
            if(licenseUrl.startsWith("//")) licenseUrl="https:"+licenseUrl;
            if(!(safe(url,"upload.wikimedia.org") || safe(url,"thumb.wikimedia.org")) || !safe(source,"commons.wikimedia.org") || author.isBlank() || author.length()>500 || license.length()>80) continue;
            if(!(license.matches("CC BY(-SA)? [0-9]+\\.[0-9]+") || license.equals("CC0") || license.equals("Public domain"))) continue;
            if(!license.equals("Public domain") && !safe(licenseUrl,"creativecommons.org")) continue;
            if(license.equals("Public domain")) licenseUrl=source;
            String title=plain(page.path("title").asText());if(title.isBlank() || title.length()>300) continue;
            return Optional.of(new Image(url,source,title,author,license,licenseUrl,now));
        }
        return Optional.empty();
    }
    private static String plain(String value) { return Jsoup.parse(value).text(); }
    private static boolean safe(String value,String host) {
        try { var uri=URI.create(value);return uri.getScheme().equals("https") && host.equals(uri.getHost()) && uri.getRawUserInfo()==null && uri.getPort()==-1; }
        catch(Exception invalid) { return false; }
    }
}
