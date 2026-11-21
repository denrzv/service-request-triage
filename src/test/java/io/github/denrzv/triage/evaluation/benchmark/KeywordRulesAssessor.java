package io.github.denrzv.triage.evaluation.benchmark;

import io.github.denrzv.triage.domain.model.ChoiceJudgment;
import io.github.denrzv.triage.domain.model.PrimaryIntent;
import io.github.denrzv.triage.domain.model.ProblemDomain;
import io.github.denrzv.triage.domain.model.RequestedOutcome;
import io.github.denrzv.triage.domain.model.SemanticAssessment;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * The {@code RULES_ONLY} baseline: a small, transparent keyword assessor authored from the taxonomy descriptions and
 * the development split only (ADR FEATURE-005 decision 3). It produces one-hot probabilities with confidence 1.0 and
 * Noul values of 0 or 1, so policy thresholds have no effect on it; the Policy Engine still makes every decision.
 * It reads only the message text.
 */
public final class KeywordRulesAssessor {

    public static final String MODEL = "keyword-rules";
    public static final String VERSION = "rules-v1";

    private record Rule<E>(E value, Pattern pattern) {}

    private static final String MALFUNCTION = "crash\\w*|error|not working|stopped working|does not work"
            + "|will not|declined|declines|reject\\w*|blank|freez\\w*|glitch|swallowed|expired"
            + "|not refreshed|does nothing|timed out|not supported|stops at";

    private static final List<Rule<PrimaryIntent>> INTENT_RULES = List.of(
            rule(PrimaryIntent.COMPLAINT, "unhappy|not happy|angry|furious|disappointed|disappointing|fed up"
                    + "|unacceptable|disgust\\w*|disgrace\\w*|appalled|annoyed|annoying|ridiculous|worst|sick of"
                    + "|tired of|rude|useless|poor service|insulting|offensive|losing (my )?patience|not impressed"),
            rule(PrimaryIntent.INCIDENT_REPORT, MALFUNCTION),
            rule(PrimaryIntent.INQUIRY, "tell me|explain|what is|what are|how (long|often|do|does|would|many)"
                    + "|when (will|is|does)|which|is there|are there|do you offer|does (it|the)"),
            rule(PrimaryIntent.ACTION_REQUEST, "please|i want to|i would like|i need|can you|could you|set up"
                    + "|book me|order a"));

    private static final List<Rule<ProblemDomain>> DOMAIN_RULES = List.of(
            rule(ProblemDomain.SUPPORT_SERVICE, "support|agent|adviser|advisor|staff|on hold|colleague"
                    + "|department|rude"),
            rule(ProblemDomain.DIGITAL_CHANNELS, "app|website|online banking|log ?in|logged|password|passcode"
                    + "|browser|homepage|notifications?|two-step|face recognition|fingerprint|online"),
            rule(ProblemDomain.PAYMENTS, "charg\\w*|payments?|paid|transfers?|refund\\w*|fees?|card|direct debit"
                    + "|standing order|payee|limit|debit|cash machine|terminal|deposit|money|salary|overdraft"
                    + "|instalments?|twice"),
            rule(ProblemDomain.ACCOUNTS, "accounts?|statements?|chequebook|holder|address|savings|interest"));

    private static final List<Rule<RequestedOutcome>> OUTCOME_RULES = List.of(
            rule(RequestedOutcome.REFUND, "refund\\w*|money back|give back|returned|return the|reverse|back "
                    + "immediately|want (that|the) \\w+ back"),
            rule(RequestedOutcome.FIX_PROBLEM, "fix|sort (it )?out|change it back|unblock|unlock"),
            rule(RequestedOutcome.INVESTIGATE, "look into|find out|check|chase|investigate|review"),
            rule(RequestedOutcome.EXPLAIN, "explain|why|what|how|when|which|tell me"),
            rule(RequestedOutcome.PERFORM_ACTION, "set up|cancel|change|update|open|close|increase|add|order"
                    + "|send|enable|switch on|convert|book|arrange"));

    private static final Pattern TECHNICAL = word(MALFUNCTION);
    private static final Pattern MULTI_INTENT = word("also|separately|while you are at it|and what|and is");

    public SemanticAssessment assess(String message) {
        if (message == null || message.isBlank()) {
            throw new IllegalArgumentException("message must not be blank");
        }
        String text = message.toLowerCase(Locale.ROOT);
        Optional<PrimaryIntent> intent = first(INTENT_RULES, text);
        Optional<ProblemDomain> domain = first(DOMAIN_RULES, text);
        RequestedOutcome outcome = first(OUTCOME_RULES, text).orElse(RequestedOutcome.NOT_SPECIFIED);
        PrimaryIntent selectedIntent = intent.orElse(
                text.contains("?") ? PrimaryIntent.INQUIRY : PrimaryIntent.ACTION_REQUEST);
        boolean fits = intent.isPresent() || domain.isPresent();
        return new SemanticAssessment(
                oneHot(selectedIntent, PrimaryIntent.class),
                oneHot(domain.orElse(ProblemDomain.OTHER), ProblemDomain.class),
                oneHot(outcome, RequestedOutcome.class),
                flag(outcome == RequestedOutcome.REFUND),
                flag(TECHNICAL.matcher(text).find()),
                flag(MULTI_INTENT.matcher(text).find()),
                flag(fits),
                MODEL,
                VERSION);
    }

    private static <E> Optional<E> first(List<Rule<E>> rules, String text) {
        return rules.stream()
                .filter(rule -> rule.pattern().matcher(text).find())
                .map(Rule::value)
                .findFirst();
    }

    private static <E extends Enum<E>> ChoiceJudgment<E> oneHot(E selected, Class<E> type) {
        Map<E, Double> probabilities = new EnumMap<>(type);
        for (E constant : type.getEnumConstants()) {
            probabilities.put(constant, constant == selected ? 1.0 : 0.0);
        }
        return new ChoiceJudgment<>(selected, 1.0, probabilities);
    }

    private static double flag(boolean value) {
        return value ? 1.0 : 0.0;
    }

    private static <E> Rule<E> rule(E value, String alternatives) {
        return new Rule<>(value, word(alternatives));
    }

    private static Pattern word(String alternatives) {
        return Pattern.compile("\\b(?:" + alternatives + ")\\b");
    }
}
