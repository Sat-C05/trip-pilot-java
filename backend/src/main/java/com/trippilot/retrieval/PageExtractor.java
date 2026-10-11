package com.trippilot.retrieval;

import java.security.MessageDigest;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import org.jsoup.Jsoup;
import org.springframework.stereotype.Component;

@Component
public class PageExtractor {
    public record Extracted(String title, String text, String hash) {}
    public Extracted extract(SafeWebClient.Page page, int maxCharacters) {
        String text, title;
        if (page.contentType().startsWith("text/plain")) { text = page.text(); title = ""; }
        else if (page.contentType().startsWith("text/html") || page.contentType().startsWith("application/xhtml+xml")) {
            var document = Jsoup.parse(page.text(), page.finalUrl().toString());
            title = document.title();
            document.select("script,style,noscript,nav,footer,header,form,iframe,svg").remove();
            // Wikivoyage embeds a long table of contents inside the article page banner.
            // Remove navigational markup before passage offsets/hash are computed.
            document.select(".toc, #toc, .wpb-topbanner-toc, .wpb-banner-toc, .mw-editsection, .mw-indicators, #coordinates, #geoCoord, #siteSub, #mw-content-subtitle, #contentSub, #breadcrumbs").remove();
            // Prefer article content over an enclosing <main> or an unrelated parser-output widget.
            var main = document.selectFirst("#mw-content-text .mw-parser-output");
            if(main==null) main=document.selectFirst(".mw-parser-output");
            if(main==null) main=document.selectFirst("main, article");
            text = practicalText(main == null ? document.body() : main,maxCharacters);
        } else throw new RetrievalFailure(RetrievalFailure.Code.UNSUPPORTED_TYPE);
        text = text.replaceAll("\\s+", " ").strip();
        if (text.length() > maxCharacters) text = text.substring(0, maxCharacters);
        if (text.isBlank()) throw new RetrievalFailure(RetrievalFailure.Code.EMPTY_CONTENT);
        try { return new Extracted(title.length() > 300 ? title.substring(0, 300) : title, text,
                HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8))));
        } catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
    /** Long city guides must retain practical sections instead of spending the entire cap on history. */
    private static String practicalText(org.jsoup.nodes.Element article,int limit) {
        String full=article.text();if(full.length()<=limit) return full;
        var sections=new java.util.LinkedHashMap<String,String>();
        for(var heading:article.select("h2")) {
            String name=heading.text().strip().toLowerCase(java.util.Locale.ROOT);
            if(!java.util.Set.of("get in","get around","see","do","sleep").contains(name)) continue;
            var anchor=heading.parent().hasClass("mw-heading")?heading.parent():heading;
            var section=new StringBuilder(heading.text());
            for(var sibling=anchor.nextElementSibling();sibling!=null;sibling=sibling.nextElementSibling()) {
                if(sibling.tagName().equals("h2") || sibling.selectFirst("h2")!=null) break;
                section.append(' ').append(sibling.text());
            }
            sections.putIfAbsent(name,section.toString());
        }
        if(sections.isEmpty()) return full;
        String intro=completePrefix(full,Math.min(2500,limit/4));
        var selected=new StringBuilder(intro);
        int perSection=Math.max(0,(limit-intro.length()-sections.size())/sections.size());
        for(String section:sections.values()) selected.append('\n').append(completePrefix(section,perSection));
        return selected.toString();
    }
    private static String completePrefix(String text,int limit) {
        if(text.length()<=limit) return text;
        if(limit<=0) return "";
        var sentences=java.text.BreakIterator.getSentenceInstance(java.util.Locale.ENGLISH);sentences.setText(text);
        int end=0;
        for(int next=sentences.next();next!=java.text.BreakIterator.DONE && next<=limit;next=sentences.next()) end=next;
        return end==0?"":text.substring(0,end).strip();
    }
}
