package io.github.denrzv.triage.application.assessment;

import io.github.denrzv.triage.domain.model.ChoiceQuestion;
import io.github.denrzv.triage.domain.model.JevQuestion;
import io.github.denrzv.triage.domain.model.JevQuestionSet;
import io.github.denrzv.triage.domain.model.NoulQuestion;
import io.github.denrzv.triage.domain.model.PrimaryIntent;
import io.github.denrzv.triage.domain.model.ProblemDomain;
import io.github.denrzv.triage.domain.model.RequestedOutcome;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Versioned triage question sets sent to Jev in one call (ADR FEATURE-005 decision 2). Choice criteria keys are the
 * lower-case names of the taxonomy enums, so {@link JevAssessmentMapper} can map them back without a lookup table.
 * Any change to a question name, instruction or criterion requires a new version constant.
 *
 * <p>{@value #TAXONOMY_FIT} is the Companion Noul: it asks whether any offered category fits, because a Choice
 * always picks an option even when none applies.
 */
public final class TriageQuestionSets {

    /** Version of {@link #v1()}; echoed in every result and assessment. */
    public static final String V1 = "triage-jev-v1";

    public static final String PRIMARY_INTENT = "primary_intent";
    public static final String PROBLEM_DOMAIN = "problem_domain";
    public static final String REQUESTED_OUTCOME = "requested_outcome";
    public static final String REFUND_REQUESTED = "refund_requested";
    public static final String TECHNICAL_PROBLEM = "technical_problem";
    public static final String MULTI_INTENT = "multi_intent";
    public static final String TAXONOMY_FIT = "taxonomy_fit";

    private static final JevQuestionSet V1_SET = buildV1();

    private TriageQuestionSets() {}

    /** The {@value #V1} question set: three Choice dimensions and four Noul signals. */
    public static JevQuestionSet v1() {
        return V1_SET;
    }

    /** Criteria key used for an enum constant in Choice questions. */
    public static String criterionKey(Enum<?> constant) {
        return constant.name().toLowerCase(Locale.ROOT);
    }

    private static JevQuestionSet buildV1() {
        Map<String, JevQuestion> questions = new LinkedHashMap<>();
        questions.put(PRIMARY_INTENT, new ChoiceQuestion(
                "What is the customer's primary intent in this message to the bank?",
                criteria(Map.of(
                        PrimaryIntent.COMPLAINT,
                        "Expresses dissatisfaction or a formal grievance about something the bank did or failed to do",
                        PrimaryIntent.INQUIRY, "Asks for clarification or information without complaining",
                        PrimaryIntent.ACTION_REQUEST, "Asks the bank to perform a specific service action",
                        PrimaryIntent.INCIDENT_REPORT,
                        "Reports a malfunction or problem without clearly expressing a complaint"),
                        PrimaryIntent.class)));
        questions.put(PROBLEM_DOMAIN, new ChoiceQuestion(
                "Which domain best matches the central problem or subject of the message?",
                criteria(Map.of(
                        ProblemDomain.PAYMENTS, "Charges, transfers, card payments, fees or billing",
                        ProblemDomain.ACCOUNTS, "Account access, account details or account status",
                        ProblemDomain.DIGITAL_CHANNELS, "Mobile app, login, website or online banking",
                        ProblemDomain.SUPPORT_SERVICE, "Staff conduct or quality of customer support",
                        ProblemDomain.OTHER, "Anything outside these domains"),
                        ProblemDomain.class)));
        questions.put(REQUESTED_OUTCOME, new ChoiceQuestion(
                "What outcome does the customer ask for?",
                criteria(Map.of(
                        RequestedOutcome.REFUND, "Money to be returned",
                        RequestedOutcome.FIX_PROBLEM, "A problem to be fixed",
                        RequestedOutcome.INVESTIGATE, "Something to be looked into",
                        RequestedOutcome.EXPLAIN, "An explanation",
                        RequestedOutcome.PERFORM_ACTION, "A specific service action to be performed",
                        RequestedOutcome.NOT_SPECIFIED, "No outcome is stated"),
                        RequestedOutcome.class)));
        questions.put(REFUND_REQUESTED, new NoulQuestion(
                "Does the customer explicitly ask for money to be returned or refunded?"));
        questions.put(TECHNICAL_PROBLEM, new NoulQuestion(
                "Does the message describe a technical malfunction, such as an app, website, login, card or "
                        + "terminal error?"));
        questions.put(MULTI_INTENT, new NoulQuestion(
                "Does the message contain two or more independent requests or problems that would each need "
                        + "separate handling?"));
        questions.put(TAXONOMY_FIT, new NoulQuestion(
                "Is this message a customer service request to the bank that at least one offered category "
                        + "accurately describes: a complaint, inquiry, action request or incident report about "
                        + "payments, accounts, digital channels or customer support?"));
        return new JevQuestionSet(V1, questions);
    }

    /** Orders criteria by enum declaration order and requires one description per constant. */
    private static <E extends Enum<E>> Map<String, String> criteria(Map<E, String> descriptions, Class<E> type) {
        Map<String, String> criteria = new LinkedHashMap<>();
        for (E constant : type.getEnumConstants()) {
            String description = descriptions.get(constant);
            if (description == null) {
                throw new IllegalStateException("missing criterion for " + constant);
            }
            criteria.put(criterionKey(constant), description);
        }
        return criteria;
    }
}
