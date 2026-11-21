package io.github.denrzv.triage.infrastructure.typesafe;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Loopback stand-in for the TypeSafe API built on the JDK {@code HttpServer}. Responses are scripted in order; the
 * last scripted response repeats once the script is exhausted.
 */
final class FakeJevServer implements AutoCloseable {

    /** One scripted reply. */
    record Reply(int status, String body, Map<String, String> headers, long delayMillis) {

        static Reply of(int status, String body) {
            return new Reply(status, body, Map.of(), 0L);
        }

        static Reply withHeader(int status, String body, String name, String value) {
            return new Reply(status, body, Map.of(name, value), 0L);
        }

        static Reply delayed(int status, String body, long delayMillis) {
            return new Reply(status, body, Map.of(), delayMillis);
        }
    }

    /** One request as seen by the server. */
    record Recorded(String method, String path, Map<String, List<String>> headers, String body) {

        String header(String name) {
            return headers.entrySet().stream()
                    .filter(entry -> entry.getKey().equalsIgnoreCase(name))
                    .flatMap(entry -> entry.getValue().stream())
                    .findFirst()
                    .orElse(null);
        }
    }

    private final HttpServer server;
    private final ExecutorService executor = Executors.newCachedThreadPool();
    private final List<Reply> script = new CopyOnWriteArrayList<>();
    private final List<Recorded> requests = new CopyOnWriteArrayList<>();
    private final AtomicInteger served = new AtomicInteger();

    FakeJevServer(Reply... replies) throws IOException {
        script.addAll(List.of(replies));
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/", this::handle);
        server.setExecutor(executor);
        server.start();
    }

    String baseUrl() {
        return "http://" + server.getAddress().getHostString() + ":" + server.getAddress().getPort();
    }

    List<Recorded> requests() {
        return requests;
    }

    private void handle(HttpExchange exchange) throws IOException {
        try (exchange) {
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            requests.add(new Recorded(
                    exchange.getRequestMethod(),
                    exchange.getRequestURI().getPath(),
                    Map.copyOf(exchange.getRequestHeaders()),
                    body));
            Reply reply = script.get(Math.min(served.getAndIncrement(), script.size() - 1));
            pause(reply.delayMillis());
            reply.headers().forEach((name, value) -> exchange.getResponseHeaders().add(name, value));
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            byte[] bytes = reply.body().getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(reply.status(), bytes.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(bytes);
            }
        } catch (IOException ignored) {
            // The client may have timed out and closed the connection; nothing to do.
        }
    }

    private static void pause(long millis) {
        if (millis > 0) {
            try {
                Thread.sleep(millis);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    @Override
    public void close() {
        server.stop(0);
        executor.shutdownNow();
    }
}
