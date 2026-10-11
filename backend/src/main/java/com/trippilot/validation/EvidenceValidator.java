package com.trippilot.validation;

import static com.trippilot.validation.ValidatedResearch.*;
import com.trippilot.planning.TravelRequirements;
import com.trippilot.research.HarnessProperties;
import com.trippilot.research.ResearchRepository.Source;
import java.time.*;
import java.util.*;
import java.util.regex.Pattern;
import java.time.temporal.ChronoUnit;

/** Deterministic extractive support. No semantic entailment or live quote authority is inferred. */
public final class EvidenceValidator {
    public static final String VERSION="extractive-v2";
    public record Candidate(Name field, String destination, String arrival, String departure,
            Integer travelers, String currency, String quote, UUID passageId) {}
    private static final Pattern INSTRUCTIONS=Pattern.compile(
            "(?is)(ignore.{0,40}(instructions|rules|previous)|system\\s*(prompt|message)|assistant\\s*:|"+
            "book\\s+now|execute\\s+|tool[_ ]?call|override.{0,30}(rules|instructions)|reveal.{0,30}(secret|password)|"+
            "\\b(return|output|produce).{0,40}\\b(booking|json|tool|instructions?)\\b|\\bcite.{0,30}\\b(invented|fake|fabricated)\\b)");
    private static final Pattern VOLATILE=Pattern.compile("(?iu)(\\b(INR|Rs|USD|price|costs?|rates?|availability|available|refund|cancellation|policy|policies)\\b|[₹$€])");

    public ValidatedResearch validate(UUID revision, TravelRequirements input, HarnessProperties policy,
            List<Source> sources, Map<UUID,String> suppliedPassages, List<Candidate> candidates, Instant now,
            List<Decision> extractionDecisions) {
        var decisions=new ArrayList<>(extractionDecisions);
        var accepted=new EnumMap<Name,List<Citation>>(Name.class);
        var stale=new EnumMap<Name,List<Citation>>(Name.class);
        var sourceByPassage=new HashMap<UUID,Source>();
        var textByPassage=new HashMap<UUID,String>();
        for(var source:sources) for(var passage:source.passages()) {
            // Reject corrupt offsets even if the ID exists in persistence.
            if(passage.startOffset()<0 || passage.endOffset()>source.text().length() || passage.endOffset()<=passage.startOffset()
                    || !source.text().substring(passage.startOffset(),passage.endOffset()).equals(passage.text())) continue;
            sourceByPassage.put(passage.id(),source); textByPassage.put(passage.id(),passage.text());
        }
        for(var candidate:candidates) {
            Name field=candidate.field();String code=null;
            var source=sourceByPassage.get(candidate.passageId());
            if(source==null || !suppliedPassages.containsKey(candidate.passageId())) code="UNKNOWN_OR_UNSUPPLIED_EVIDENCE";
            // Guide destination names may change letter case; dates/currency/travelers remain exact.
            else if(candidate.destination()==null || input.destinationIntent()==null || !candidate.destination().equalsIgnoreCase(input.destinationIntent())
                    || !Objects.equals(candidate.arrival(),date(input.arrival()))
                    || !Objects.equals(candidate.departure(),date(input.departureDate()))
                    || !Objects.equals(candidate.travelers(),input.travelers())
                    || !Objects.equals(candidate.currency(),input.currency())) code="WRONG_SCOPE";
            else if(candidate.quote()==null || candidate.quote().isBlank() || candidate.quote().length()>500
                    || !textByPassage.get(candidate.passageId()).contains(candidate.quote())
                    || !suppliedPassages.get(candidate.passageId()).contains(candidate.quote())) code="UNSUPPORTED_QUOTE";
            else if(INSTRUCTIONS.matcher(candidate.quote()).find()) code="INSTRUCTION_CONTENT";
            // General fetch time is not provider observation time, travel-guide prices are not dated quotes.
            else if(field==Name.PRICE || field==Name.AVAILABILITY || field==Name.POLICY) code="NO_SCOPED_PROVIDER_EVIDENCE";
            else if(VOLATILE.matcher(candidate.quote()).find()) code="VOLATILE_CLAIM_IN_GUIDE_FIELD";
            else if(!categoryMatches(field,candidate.quote())) code="FIELD_CATEGORY_UNVERIFIED";
            else if(!mentions(source.title(),input.destinationIntent()) || (!mentions(candidate.quote(),input.destinationIntent())
                    && !(destinationArticle(source,input.destinationIntent()) && wholeSentence(textByPassage.get(candidate.passageId()),candidate.quote())))) code="DESTINATION_NOT_ESTABLISHED";
            else if(source.retrievedAt().isAfter(now.plusSeconds(60))) code="FUTURE_EVIDENCE_TIME";
            if(code!=null) { decisions.add(new Decision(field,code));continue; }
            var citation=new Citation(source.id(),candidate.passageId(),source.finalUrl(),source.retrievedAt(),candidate.quote());
            var target=source.retrievedAt().plus(Duration.ofDays(policy.descriptionFreshDays())).isAfter(now) ? accepted : stale;
            target.computeIfAbsent(field,ignored->new ArrayList<>()).add(citation);
            decisions.add(new Decision(field,target==stale ? "STALE_EVIDENCE" : "EXACT_EXCERPT_ACCEPTED"));
        }
        var fields=new ArrayList<Field>();
        for(var name:Name.values()) {
            var citations=accepted.getOrDefault(name,List.of());
            var old=stale.getOrDefault(name,List.of());
            // Conservative alternatives: distinct excerpts are not silently reconciled or voted on.
            var alternatives=new LinkedHashSet<String>();citations.forEach(c->alternatives.add(c.quote()));
            if(alternatives.size()>1) fields.add(new Field(name,FieldState.CONFLICTING,null,"DIFFERING_EXCERPTS_REQUIRE_REVIEW",List.copyOf(citations)));
            else if(!citations.isEmpty()) fields.add(new Field(name,FieldState.SUPPORTED,citations.getFirst().quote(),"ATTRIBUTED_EXCERPT_SEMANTICS_UNVERIFIED",List.copyOf(citations)));
            else if(!old.isEmpty()) fields.add(new Field(name,FieldState.STALE,null,"RETRIEVAL_FRESHNESS_EXPIRED",List.copyOf(old)));
            else fields.add(new Field(name,FieldState.UNAVAILABLE,null,reason(name,decisions),List.of()));
        }
        var missing=new ArrayList<String>();
        if(input.departure()==null || input.departure().isBlank()) missing.add("departure");
        if(input.destinationIntent()==null || input.destinationIntent().isBlank()) missing.add("destinationIntent");
        if(input.arrival()==null) missing.add("arrival");
        if(input.departureDate()==null) missing.add("departureDate");
        if(input.travelers()==null) missing.add("travelers");
        if(input.budget()==null) missing.add("budget");
        if(input.currency()==null) missing.add("currency");
        Estimate duration=estimateDuration(revision,input);
        boolean complete=missing.isEmpty() && fields.stream().allMatch(f->f.state()==FieldState.SUPPORTED);
        return new ValidatedResearch(VERSION,revision,now,complete,List.copyOf(missing),List.copyOf(fields),
                List.copyOf(decisions),duration,"NO_VALIDATED_NUMERIC_PRICE_INPUTS");
    }
    private static String reason(Name name,List<Decision> decisions) {
        return decisions.stream().filter(d->d.field()==name).reduce((first,last)->last).map(Decision::code)
                .orElse(name==Name.PRICE || name==Name.AVAILABILITY || name==Name.POLICY ? "NO_SCOPED_PROVIDER_EVIDENCE" : "NO_ACCEPTED_EVIDENCE");
    }
    private static boolean mentions(String text,String destination) {
        return text!=null && destination!=null && !destination.isBlank() && Pattern.compile("(?iu)(?<![\\p{L}\\p{N}])"+
                Pattern.quote(destination.strip())+"(?![\\p{L}\\p{N}])").matcher(text).find();
    }
    private static String date(LocalDate date) { return date==null ? null : date.toString(); }
    private static boolean categoryMatches(Name field,String quote) {
        String words=switch(field) {
            case STAY -> "hotels?|hostels?|guesthouses?|accommodation|stays?";
            case TRANSPORT -> "trains?|railways?|bus|buses|airports?|taxis?|transport";
            case ACTIVITY -> "visits?|museums?|forts?|palaces?|walks?|activity|activities|tours?";
            default -> ".*";
        };
        return Pattern.compile("(?iu)\\b("+words+")\\b").matcher(quote).find();
    }
    static boolean usableGuideExcerpt(Name field,Source source,String quote,String destination) {
        return field!=Name.PRICE && field!=Name.AVAILABILITY && field!=Name.POLICY
                && quote.length()<=500 && !INSTRUCTIONS.matcher(quote).find() && !VOLATILE.matcher(quote).find()
                && categoryMatches(field,quote) && mentions(source.title(),destination)
                && !(field==Name.STAY && Pattern.compile("(?iu)district articles|information about specific sights|designer|architectural|landmarks").matcher(quote).find())
                && !(field==Name.ACTIVITY && Pattern.compile("(?iu)\\b(weather|climate|terminal|railway|station|airport|bus|traffic|footpaths)\\b|time to visit|pedestrian foot bridge").matcher(quote).find())
                && (mentions(quote,destination) || destinationArticle(source,destination));
    }
    private static boolean destinationArticle(Source source,String destination) {
        return destination!=null && source.title()!=null && mentions(source.text(),destination)
                && Pattern.compile("(?iu)^"+Pattern.quote(destination.strip())+"(?=$|/|\\s+[–—-]|\\s+guide\\b)").matcher(source.title()).find();
    }
    private static boolean wholeSentence(String passage,String quote) {
        var sentences=java.text.BreakIterator.getSentenceInstance(Locale.ENGLISH);sentences.setText(passage);
        int start=sentences.first();
        for(int end=sentences.next();end!=java.text.BreakIterator.DONE;start=end,end=sentences.next())
            if(passage.substring(start,end).strip().equals(quote)) return true;
        return false;
    }
    /** Stored revisions remain immutable; the read view can only downgrade freshness, never upgrade support. */
    public static ValidatedResearch refresh(ValidatedResearch result,HarnessProperties policy,Instant now) {
        var fields=result.fields().stream().map(field->{
            Duration window=switch(field.field()) {
                case PRICE -> Duration.ofHours(policy.priceFreshHours());
                case AVAILABILITY -> Duration.ofMinutes(policy.availabilityFreshMinutes());
                case POLICY -> Duration.ofDays(policy.policyFreshDays());
                default -> Duration.ofDays(policy.descriptionFreshDays());
            };
            if((field.state()==FieldState.SUPPORTED || field.state()==FieldState.CONFLICTING)
                    && field.citations().stream().anyMatch(c->!c.retrievedAt().plus(window).isAfter(now)))
                return new Field(field.field(),FieldState.STALE,null,"RESULT_FRESHNESS_EXPIRED",field.citations());
            return field;
        }).toList();
        return new ValidatedResearch(result.validatorVersion(),result.revisionId(),result.validatedAt(),
                result.complete() && fields.stream().allMatch(f->f.state()==FieldState.SUPPORTED),result.missingRequirements(),fields,result.decisions(),
                result.durationEstimate(),result.costEstimateReason());
    }
    public static Estimate estimateDuration(UUID revision,TravelRequirements input) {
        if(input.arrival()==null || input.departureDate()==null || !input.departureDate().isAfter(input.arrival()))
            return new Estimate(FieldState.UNAVAILABLE,null,"DATES_REQUIRED",revision,date(input.arrival()),date(input.departureDate()),List.of());
        return new Estimate(FieldState.ESTIMATED,ChronoUnit.DAYS.between(input.arrival(),input.departureDate()),"DETERMINISTIC_DATE_DIFFERENCE",
                revision,date(input.arrival()),date(input.departureDate()),List.of("One overnight stay per calendar date interval; transport nights and check-in rules are unverified."));
    }
}
