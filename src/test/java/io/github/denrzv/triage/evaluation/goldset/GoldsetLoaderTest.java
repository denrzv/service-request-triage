package io.github.denrzv.triage.evaluation.goldset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.denrzv.triage.domain.model.Channel;
import io.github.denrzv.triage.domain.model.RouteAction;
import io.github.denrzv.triage.domain.model.ServiceRequestType;
import io.github.denrzv.triage.infrastructure.taxonomy.ShippedDefinitions;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

class GoldsetLoaderTest {

    private final GoldsetLoader loader = new GoldsetLoader(ShippedDefinitions.taxonomyV1());

    @TempDir
    Path dir;

    @Test
    void loadsValidRecordWithTypedLabels() throws IOException {
        List<GoldCase> cases = loader.load(write(GoldCaseJson.valid().line()));

        assertThat(cases).singleElement().satisfies(gold -> {
            assertThat(gold.caseId()).isEqualTo("GS-0001");
            assertThat(gold.channel()).isEqualTo(Channel.WEB_FORM);
            assertThat(gold.formalSrType()).isEqualTo(ServiceRequestType.CLAIM);
            assertThat(gold.recommendedRoute()).isEqualTo(RouteAction.HUMAN_REVIEW);
            assertThat(gold.splitUnit()).isEqualTo("GS-0001");
        });
    }

    @Test
    void paraphraseGroupIsTheSplitUnit() throws IOException {
        GoldCase gold = loader.load(write(GoldCaseJson.valid().with("paraphraseGroup", "PG-01").line())).getFirst();

        assertThat(gold.splitUnit()).isEqualTo("PG-01");
    }

    static List<Object[]> invalidRecords() {
        return List.of(
                row("bad case id", "caseId must match", Map.of("caseId", "CASE-1")),
                row("unknown property", "Unrecognized field", Map.of("customerName", "Synthetic")),
                row("unknown enum", "not one of the values accepted", Map.of("intent", "PRAISE")),
                row("unknown channel", "not one of the values accepted", Map.of("channel", "SMS")),
                row("boolean as string", "requiresHumanReview", Map.of("requiresHumanReview", "true")),
                row("blank message", "message length", Map.of("message", "  ")),
                row("non ascii message", "printable ASCII", Map.of("message", "I was charged twice — please fix")),
                row("multi line message", "printable ASCII", Map.of("message", "Line one of the text\nline two")),
                row("email in message", "e-mail", Map.of("message", "Write to me at someone@example.test please")),
                row("account number", "6 or more consecutive digits", Map.of("message", "My account 12345678 is blocked")),
                row("too long message", "message length", Map.of("message", "a".repeat(601))),
                row("blank rationale", "labelRationale length", Map.of("labelRationale", "")),
                row("llm route", "terminal route", Map.of("recommendedRoute", "LLM_INVESTIGATION")),
                row("review flag mismatch", "requiresHumanReview must be true",
                        Map.of("requiresHumanReview", false)),
                row("claim auto proposed", "CLAIM requires recommendedRoute HUMAN_REVIEW",
                        Map.of("recommendedRoute", "DRAFT_PROPOSAL", "requiresHumanReview", false)),
                row("abstain with type", "ABSTAIN requires formalSrType UNCLASSIFIED",
                        Map.of("recommendedRoute", "ABSTAIN")),
                row("reason code of other type", "belongs to TECHNICAL_REQUEST",
                        Map.of("reasonCode", "TECHNICAL_MALFUNCTION")),
                row("unknown reason code", "unknown reason code", Map.of("reasonCode", "REFUND_GRANTED")),
                row("taxonomy mismatch", "does not match loaded taxonomy", Map.of("taxonomyVersion", "v9")),
                row("secondary equals primary", "secondaryIntents must not contain the primary",
                        Map.of("secondaryIntents", List.of("COMPLAINT"))),
                row("duplicate secondary", "secondaryIntents must not contain duplicates",
                        Map.of("stratum", "MULTI_INTENT", "secondaryIntents", List.of("INQUIRY", "INQUIRY"))),
                row("alternative equals primary", "alternatives.formalSrType must not contain the primary",
                        Map.of("stratum", "NEAR_TIE",
                                "alternatives", GoldCaseJson.alternatives(List.of(), List.of("CLAIM")))),
                row("simple with alternatives", "must have no secondary intents and no alternatives",
                        Map.of("alternatives", GoldCaseJson.alternatives(List.of(), List.of("TECHNICAL_REQUEST")))),
                row("bad paraphrase group", "paraphraseGroup must be null or match", Map.of("paraphraseGroup", "G1")),
                row("multi intent without secondary", "MULTI_INTENT requires secondaryIntents",
                        Map.of("stratum", "MULTI_INTENT")),
                row("out of taxonomy labelled as claim", "OUT_OF_TAXONOMY requires",
                        Map.of("stratum", "OUT_OF_TAXONOMY")),
                row("control labelled complaint", "NO_COMPLAINT_CONTROL must not be a complaint",
                        Map.of("stratum", "NO_COMPLAINT_CONTROL")),
                row("vague auto proposed", "must not be auto-proposed",
                        Map.of("stratum", "VAGUE", "intent", "INQUIRY", "formalSrType", "INFORMATION_REQUEST",
                                "reasonCode", "GENERAL_INFORMATION", "recommendedRoute", "DRAFT_PROPOSAL",
                                "requiresHumanReview", false)),
                row("duplicate charge outside payments", "DUPLICATE_CHARGE requires domain PAYMENTS",
                        Map.of("stratum", "DUPLICATE_CHARGE", "domain", "ACCOUNTS",
                                "reasonCode", "ACCOUNT_COMPLAINT")));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("invalidRecords")
    void rejectsInvalidRecordNamingLineAndReason(String scenario, String expected, Map<String, Object> changes)
            throws IOException {
        GoldCaseJson json = GoldCaseJson.valid();
        changes.forEach(json::with);
        Path file = write(json.line());

        assertThatThrownBy(() -> loader.load(file))
                .isInstanceOf(GoldsetValidationException.class)
                .hasMessageContaining("cases.jsonl:1")
                .hasMessageContaining(expected);
    }

    @Test
    void rejectsMissingProperty() throws IOException {
        Path file = write(GoldCaseJson.valid().without("labelSource").line());

        assertThatThrownBy(() -> loader.load(file)).hasMessageContaining("labelSource");
    }

    @Test
    void rejectsDuplicateJsonKey() throws IOException {
        String line = GoldCaseJson.valid().line().replaceFirst("\\{", "{\"caseId\":\"GS-0002\",");
        Path file = write(line);

        assertThatThrownBy(() -> loader.load(file)).hasMessageContaining("Duplicate field 'caseId'");
    }

    @Test
    void collectsViolationsOfAllLines() throws IOException {
        String valid = GoldCaseJson.valid().line();
        String invalid = GoldCaseJson.valid().with("caseId", "GS-0002").with("intent", "PRAISE").line();
        Path file = write(valid + "\n" + invalid + "\n\n" + invalid);

        assertThatThrownBy(() -> loader.load(file))
                .isInstanceOfSatisfying(GoldsetValidationException.class, e -> assertThat(e.violations())
                        .hasSize(3)
                        .anySatisfy(v -> assertThat(v).startsWith("cases.jsonl:2 GS-0002"))
                        .anySatisfy(v -> assertThat(v).isEqualTo("cases.jsonl:3 blank line")));
    }

    @Test
    void errorsNeverEchoMessageText() throws IOException {
        Path file = write(GoldCaseJson.valid().with("message", "Secret synthetic text é here").line());

        assertThatThrownBy(() -> loader.load(file)).message().doesNotContain("Secret synthetic text");
    }

    @Test
    void rejectsCarriageReturnsAndMissingFinalNewline() throws IOException {
        Path crlf = dir.resolve("crlf.jsonl");
        Files.writeString(crlf, GoldCaseJson.valid().line() + "\r\n", StandardCharsets.UTF_8);
        Path noNewline = dir.resolve("open.jsonl");
        Files.writeString(noNewline, GoldCaseJson.valid().line(), StandardCharsets.UTF_8);

        assertThatThrownBy(() -> loader.load(crlf)).hasMessageContaining("CR line endings");
        assertThatThrownBy(() -> loader.load(noNewline)).hasMessageContaining("missing final newline");
    }

    @Test
    void sha256IsLowerCaseHexOfBytes() throws IOException {
        Path file = dir.resolve("abc.txt");
        Files.writeString(file, "abc", StandardCharsets.US_ASCII);

        assertThat(GoldsetLoader.sha256(file))
                .isEqualTo("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad");
    }

    private Path write(String content) throws IOException {
        Path file = dir.resolve("cases.jsonl");
        Files.writeString(file, content + "\n", StandardCharsets.UTF_8);
        return file;
    }

    private static Object[] row(String scenario, String expected, Map<String, Object> changes) {
        return new Object[] {scenario, expected, changes};
    }
}
