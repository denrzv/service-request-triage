package io.github.denrzv.triage.infrastructure.typesafe;

import java.time.Duration;

/** Injectable pause between retry attempts so tests never sleep for real. */
@FunctionalInterface
interface Sleeper {

    Sleeper THREAD = duration -> Thread.sleep(duration.toMillis());

    void sleep(Duration duration) throws InterruptedException;
}
