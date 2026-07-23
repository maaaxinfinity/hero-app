package io.hero.app.smoke;

import com.sun.net.httpserver.HttpServer;
import java.io.OutputStream;
import java.lang.reflect.Field;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ServiceConfigurationError;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * Release-ARTIFACT smoke. Compiled against, and executed on, the final desktop
 * uber jar's real classpath ({@code java -cp <final-jar>:<smoke-classes>}) —
 * never the Gradle runtime classpath. desktopTest runs unshrunk classes, and
 * the package task only proves ProGuard exited 0; v0.5.8's three public jars
 * passed both while a surviving META-INF/services entry pointed at an engine
 * class ProGuard had stripped, so {@code new Api(...)} threw
 * {@link ServiceConfigurationError} on every install. This runner proves the
 * shipped bytes work:
 *
 * <ol>
 *   <li>the jar self-reports the version the build derived from
 *       gradle.properties (arg 0), tying artifact identity to the release tag;
 *   <li>constructing the app's {@code Api} does not throw (the v0.5.7/v0.5.8
 *       failure point);
 *   <li>the engine the app actually selected is the explicitly constructed
 *       OkHttp one, not whatever ServiceLoader dug up;
 *   <li>one real HTTP request completes through the app's own client stack
 *       against a local loopback server (JDK built-in httpserver);
 *   <li>the client closes cleanly via the app's own lifecycle path.
 * </ol>
 *
 * Plain Java on purpose: javac's only classpath is the artifact under test, so
 * a class missing from the shipped jar fails the gate at smoke-compile time
 * already. That also dictates the odd-looking coroutine plumbing below —
 * {@code Api.probe} is a suspend fun and the shrunk jar (correctly) carries no
 * {@code runBlocking}, so the runner speaks the raw suspend ABI: pass a
 * {@code Continuation}, compare against the {@code COROUTINE_SUSPENDED}
 * sentinel, unwrap {@code Result.Failure} — machinery every compiled suspend
 * function keeps alive in the artifact.
 */
public final class SmokeRunner {

    public static void main(String[] args) {
        try {
            run(args.length > 0 ? args[0] : null);
            System.out.println("releaseArtifactSmoke PASS");
            System.exit(0);
        } catch (Throwable t) {
            for (Throwable c = t; c != null; c = c.getCause()) {
                if (c instanceof ServiceConfigurationError) {
                    System.err.println(
                        "FATAL: ServiceConfigurationError — a ServiceLoader provider class was stripped from the"
                            + " final jar behind a surviving META-INF/services entry (the v0.5.7/v0.5.8 regression)");
                    break;
                }
            }
            t.printStackTrace();
            System.exit(1);
        }
    }

    private static void run(String expectedVersion) throws Exception {
        System.out.println("smoke classpath: " + System.getProperty("java.class.path"));
        if (expectedVersion == null || expectedVersion.isEmpty()) {
            throw new IllegalStateException("no expected version passed to the smoke runner");
        }

        // 1. Artifact identity: the jar's baked-in AppVersion (generated
        // Version.kt) must equal the version source the release gate verified
        // against the tag. Read reflectively so THIS jar's value is compared,
        // not a compile-time constant folded in by javac.
        String appVersion = (String) Class.forName("io.hero.app.VersionKt").getField("AppVersion").get(null);
        System.out.println("jar AppVersion: " + appVersion + " (expected: " + expectedVersion + ")");
        if (!expectedVersion.equals(appVersion)) {
            throw new IllegalStateException(
                "final jar reports AppVersion " + appVersion + " but the version source says " + expectedVersion);
        }

        // 2. A real loopback control plane: Api.probe() GETs /api/auth/methods
        // and requires a 200 — the same first call the app makes against a real
        // server.
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/auth/methods", exchange -> {
            byte[] body = "{\"methods\":[\"password\"]}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(body);
            }
        });
        server.start();
        try {
            String baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();

            // 3. Construct the app's Api — v0.5.8's public jars threw right here.
            io.hero.app.Api api = new io.hero.app.Api(baseUrl, null);

            // 4. The engine inside the Api's own client must be OkHttp. Fully
            // reflective: the app never calls HttpClient.getEngine(), so the
            // shrunk jar legitimately drops the accessor — but the backing
            // "engine" field is what the client executes requests on, so it is
            // always present in a working artifact.
            Object client = readField(api, io.hero.app.Api.class, "client");
            Object engine = readField(client, client.getClass(), "engine");
            String engineName = engine.getClass().getName();
            System.out.println("selected engine: " + engineName);
            if (!engineName.equals("io.ktor.client.engine.okhttp.OkHttpEngine")) {
                throw new IllegalStateException("expected the OkHttp engine, got " + engineName);
            }

            // 5. One complete request through the shipped client stack, via the
            // raw suspend ABI (see class comment).
            CompletableFuture<Object> outcome = new CompletableFuture<>();
            kotlin.coroutines.Continuation<Object> continuation = new kotlin.coroutines.Continuation<Object>() {
                @Override
                public kotlin.coroutines.CoroutineContext getContext() {
                    return kotlin.coroutines.EmptyCoroutineContext.INSTANCE;
                }

                @Override
                public void resumeWith(Object result) {
                    outcome.complete(result);
                }
            };
            Object immediate = api.probe(continuation);
            Object result = immediate == kotlin.coroutines.intrinsics.IntrinsicsKt.getCOROUTINE_SUSPENDED()
                ? outcome.get(60, TimeUnit.SECONDS)
                : immediate;
            if (result instanceof kotlin.Result.Failure) {
                throw new IllegalStateException("probe() threw", ((kotlin.Result.Failure) result).exception);
            }
            System.out.println("probe over loopback: " + result);
            if (!Boolean.TRUE.equals(result)) {
                throw new IllegalStateException("probe() against the loopback control plane returned " + result);
            }

            // 6. Close via the app's lifecycle path (releases the engine's
            // dispatcher and connection pool).
            api.close();
        } finally {
            server.stop(0);
        }
    }

    private static Object readField(Object target, Class<?> declaring, String name) throws Exception {
        Field field = declaring.getDeclaredField(name);
        field.setAccessible(true);
        Object value = field.get(target);
        if (value == null) {
            throw new IllegalStateException(declaring.getName() + "." + name + " is null");
        }
        return value;
    }
}
