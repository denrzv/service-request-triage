package io.github.denrzv.triage;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/** Verifies keyless startup and minimal actuator exposure (no cloud credentials involved). */
@ExtendWith(OutputCaptureExtension.class)
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "triage.jev.api-key=" + StartupHealthTest.SENTINEL,
            "triage.deepseek.api-key=" + StartupHealthTest.SENTINEL
        })
class StartupHealthTest {

    static final String SENTINEL = "synthetic-sentinel-not-a-real-key";
    private static final String HEALTH = "/actuator/health";
    private static final String STATUS_UP = "\"status\":\"UP\"";
    private static final String DETAILS = "components";

    @Autowired
    private TestRestTemplate rest;

    @Test
    void healthLivenessAndReadinessAreUpWithoutDetails() {
        for (String path : new String[] {HEALTH, HEALTH + "/liveness", HEALTH + "/readiness"}) {
            ResponseEntity<String> response = rest.getForEntity(path, String.class);
            assertThat(response.getStatusCode()).as(path).isEqualTo(HttpStatus.OK);
            assertThat(response.getBody()).as(path).contains(STATUS_UP).doesNotContain(DETAILS);
        }
    }

    @Test
    void onlyHealthAndInfoAreExposed() {
        assertThat(rest.getForEntity("/actuator/env", String.class).getStatusCode().is2xxSuccessful())
                .isFalse();
        assertThat(rest.getForEntity("/actuator/beans", String.class).getStatusCode().is2xxSuccessful())
                .isFalse();
    }

    @Test
    void configuredKeyValuesAreNeverExposedOrLogged(CapturedOutput output) {
        String health = rest.getForObject(HEALTH, String.class);
        String info = rest.getForObject("/actuator/info", String.class);
        assertThat(health).doesNotContain(SENTINEL);
        assertThat(info).doesNotContain(SENTINEL);
        assertThat(output.getAll()).doesNotContain(SENTINEL);
    }
}
