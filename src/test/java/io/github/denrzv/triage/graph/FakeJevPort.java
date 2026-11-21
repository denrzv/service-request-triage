package io.github.denrzv.triage.graph;

import io.github.denrzv.triage.application.assessment.JevResults;
import io.github.denrzv.triage.domain.model.JevAnswer;
import io.github.denrzv.triage.domain.model.JevClassificationRequest;
import io.github.denrzv.triage.domain.model.JevClassificationResult;
import io.github.denrzv.triage.domain.model.PrimaryIntent;
import io.github.denrzv.triage.domain.model.ProblemDomain;
import io.github.denrzv.triage.domain.model.RequestedOutcome;
import io.github.denrzv.triage.domain.port.JevClassificationPort;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/** Offline Jev double: returns a scripted result or throws; records every request it receives. */
final class FakeJevPort implements JevClassificationPort {

    private final Supplier<JevClassificationResult> behaviour;
    private final List<JevClassificationRequest> requests = new ArrayList<>();

    private FakeJevPort(Supplier<JevClassificationResult> behaviour) {
        this.behaviour = behaviour;
    }

    static FakeJevPort returning(Map<String, JevAnswer> answers) {
        JevClassificationResult result = JevResults.result(answers);
        return new FakeJevPort(() -> result);
    }

    static FakeJevPort returning(
            PrimaryIntent intent, ProblemDomain domain, RequestedOutcome outcome, double top, double taxonomyFit) {
        return returning(JevResults.answers(intent, domain, outcome, top, taxonomyFit));
    }

    static FakeJevPort throwing(Supplier<? extends RuntimeException> failure) {
        return new FakeJevPort(() -> {
            throw failure.get();
        });
    }

    @Override
    public synchronized JevClassificationResult classify(JevClassificationRequest request) {
        requests.add(request);
        return behaviour.get();
    }

    synchronized List<JevClassificationRequest> requests() {
        return List.copyOf(requests);
    }
}
