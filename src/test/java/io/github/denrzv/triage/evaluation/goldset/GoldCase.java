package io.github.denrzv.triage.evaluation.goldset;

import io.github.denrzv.triage.domain.model.Channel;
import io.github.denrzv.triage.domain.model.PrimaryIntent;
import io.github.denrzv.triage.domain.model.ProblemDomain;
import io.github.denrzv.triage.domain.model.ReasonCode;
import io.github.denrzv.triage.domain.model.RequestedOutcome;
import io.github.denrzv.triage.domain.model.RouteAction;
import io.github.denrzv.triage.domain.model.ServiceRequestType;
import java.util.List;
import java.util.regex.Pattern;

/**
 * One labeled, synthetic English goldset case (schema v1). Field order is the canonical JSON key order.
 *
 * <p>The compact constructor enforces record-local invariants; taxonomy cross-checks (version, reason code
 * ownership) are done by {@link GoldsetLoader}. {@code message} is untrusted text and is never logged.
 *
 * @param paraphraseGroup {@code null} unless the case deliberately paraphrases other cases of the same group
 */
public record GoldCase(
        String caseId,
        String message,
        Channel channel,
        LabelSource labelSource,
        String taxonomyVersion,
        Stratum stratum,
        PrimaryIntent intent,
        List<PrimaryIntent> secondaryIntents,
        ProblemDomain domain,
        RequestedOutcome requestedOutcome,
        ServiceRequestType formalSrType,
        String reasonCode,
        RouteAction recommendedRoute,
        boolean requiresHumanReview,
        GoldAlternatives alternatives,
        String paraphraseGroup,
        String labelRationale) {

    public static final int MIN_TEXT_LENGTH = 10;
    public static final int MAX_MESSAGE_LENGTH = 600;
    public static final int MAX_RATIONALE_LENGTH = 400;

    private static final Pattern CASE_ID = Pattern.compile("GS-\\d{4}");
    private static final Pattern GROUP_ID = Pattern.compile("PG-\\d{2}");
    private static final Pattern PRINTABLE_ASCII = Pattern.compile("[\\x20-\\x7E]+");
    private static final Pattern LONG_DIGIT_RUN = Pattern.compile("\\d{6,}");

    public GoldCase {
        require(caseId != null && CASE_ID.matcher(caseId).matches(), "caseId must match GS-NNNN");
        requireSyntheticText(message, MAX_MESSAGE_LENGTH, "message");
        requireSyntheticText(labelRationale, MAX_RATIONALE_LENGTH, "labelRationale");
        requireNonNull(channel, "channel");
        requireNonNull(labelSource, "labelSource");
        require(taxonomyVersion != null && !taxonomyVersion.isBlank(), "taxonomyVersion must not be blank");
        requireNonNull(stratum, "stratum");
        requireNonNull(intent, "intent");
        requireNonNull(domain, "domain");
        requireNonNull(requestedOutcome, "requestedOutcome");
        requireNonNull(formalSrType, "formalSrType");
        require(reasonCode != null && !reasonCode.isBlank(), "reasonCode must not be blank");
        requireNonNull(recommendedRoute, "recommendedRoute");
        requireNonNull(alternatives, "alternatives");
        secondaryIntents = GoldAlternatives.distinct(secondaryIntents, "secondaryIntents");
        require(
                paraphraseGroup == null || GROUP_ID.matcher(paraphraseGroup).matches(),
                "paraphraseGroup must be null or match PG-NN");
        requireRouteConsistency(formalSrType, recommendedRoute, requiresHumanReview);
        requirePrimaryNotAlternative(intent, secondaryIntents, domain, requestedOutcome, formalSrType,
                recommendedRoute, alternatives);
        requireStratumRules(stratum, intent, secondaryIntents, domain, formalSrType, reasonCode, recommendedRoute,
                alternatives);
    }

    /** Unit kept together by the split: the paraphrase group if any, otherwise the case itself. */
    public String splitUnit() {
        return paraphraseGroup != null ? paraphraseGroup : caseId;
    }

    private static void requireRouteConsistency(
            ServiceRequestType type, RouteAction route, boolean requiresHumanReview) {
        require(route != RouteAction.LLM_INVESTIGATION, "recommendedRoute must be a terminal route");
        require(
                requiresHumanReview == (route != RouteAction.DRAFT_PROPOSAL),
                "requiresHumanReview must be true exactly when recommendedRoute is not DRAFT_PROPOSAL");
        require(
                route != RouteAction.DRAFT_PROPOSAL || type != ServiceRequestType.UNCLASSIFIED,
                "DRAFT_PROPOSAL requires a formal type other than UNCLASSIFIED");
        require(route != RouteAction.ABSTAIN || type == ServiceRequestType.UNCLASSIFIED,
                "ABSTAIN requires formalSrType UNCLASSIFIED");
        require(type != ServiceRequestType.CLAIM || route == RouteAction.HUMAN_REVIEW,
                "CLAIM requires recommendedRoute HUMAN_REVIEW");
    }

    @SuppressWarnings("java:S107") // one argument per label dimension keeps the rule explicit
    private static void requirePrimaryNotAlternative(
            PrimaryIntent intent,
            List<PrimaryIntent> secondaryIntents,
            ProblemDomain domain,
            RequestedOutcome outcome,
            ServiceRequestType type,
            RouteAction route,
            GoldAlternatives alternatives) {
        require(!secondaryIntents.contains(intent), "secondaryIntents must not contain the primary intent");
        require(!alternatives.intent().contains(intent), "alternatives.intent must not contain the primary label");
        require(!alternatives.domain().contains(domain), "alternatives.domain must not contain the primary label");
        require(!alternatives.requestedOutcome().contains(outcome),
                "alternatives.requestedOutcome must not contain the primary label");
        require(!alternatives.formalSrType().contains(type),
                "alternatives.formalSrType must not contain the primary label");
        require(!alternatives.recommendedRoute().contains(route),
                "alternatives.recommendedRoute must not contain the primary label");
    }

    @SuppressWarnings("java:S107") // one argument per checked label keeps the rule explicit
    private static void requireStratumRules(
            Stratum stratum,
            PrimaryIntent intent,
            List<PrimaryIntent> secondaryIntents,
            ProblemDomain domain,
            ServiceRequestType type,
            String reasonCode,
            RouteAction route,
            GoldAlternatives alternatives) {
        if (stratum.isSimple()) {
            require(secondaryIntents.isEmpty() && alternatives.isEmpty(),
                    stratum + " cases must have no secondary intents and no alternatives");
        }
        switch (stratum) {
            case MULTI_INTENT -> require(!secondaryIntents.isEmpty(), "MULTI_INTENT requires secondaryIntents");
            case OUT_OF_TAXONOMY -> require(
                    type == ServiceRequestType.UNCLASSIFIED
                            && route == RouteAction.ABSTAIN
                            && ReasonCode.LOW_TAXONOMY_FIT.equals(reasonCode),
                    "OUT_OF_TAXONOMY requires UNCLASSIFIED / LOW_TAXONOMY_FIT / ABSTAIN");
            case DUPLICATE_CHARGE -> require(domain == ProblemDomain.PAYMENTS && type == ServiceRequestType.CLAIM,
                    "DUPLICATE_CHARGE requires domain PAYMENTS and formalSrType CLAIM");
            case NO_COMPLAINT_CONTROL -> require(
                    intent != PrimaryIntent.COMPLAINT && type != ServiceRequestType.CLAIM,
                    "NO_COMPLAINT_CONTROL must not be a complaint or CLAIM");
            case VAGUE, CONTRADICTORY, NEAR_TIE -> require(route != RouteAction.DRAFT_PROPOSAL,
                    stratum + " cases must not be auto-proposed");
            default -> {
                // Simple strata and DISSATISFACTION_WITH_TECHNICAL have no extra rule.
            }
        }
    }

    private static void requireSyntheticText(String value, int maxLength, String field) {
        require(value != null, field + " must not be null");
        require(value.length() >= MIN_TEXT_LENGTH && value.length() <= maxLength,
                field + " length must be within [" + MIN_TEXT_LENGTH + ", " + maxLength + "]");
        require(value.strip().equals(value), field + " must not have leading or trailing whitespace");
        require(PRINTABLE_ASCII.matcher(value).matches(), field + " must be single-line printable ASCII English");
        require(value.indexOf('@') < 0, field + " must not contain '@' (no e-mail addresses)");
        require(!LONG_DIGIT_RUN.matcher(value).find(),
                field + " must not contain 6 or more consecutive digits (no account-like numbers)");
    }

    private static void requireNonNull(Object value, String field) {
        require(value != null, field + " must not be null");
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new IllegalArgumentException(message);
        }
    }
}
