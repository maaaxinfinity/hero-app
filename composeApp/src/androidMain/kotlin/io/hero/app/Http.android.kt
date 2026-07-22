package io.hero.app

import io.ktor.client.HttpClient
import io.ktor.client.HttpClientConfig
import io.ktor.client.engine.okhttp.OkHttp
import java.util.concurrent.TimeUnit

// Identical to the desktop actual: ktor-client-okhttp is a JVM artifact, so the
// engine reference cannot live in commonMain.
actual fun heroHttpClient(block: HttpClientConfig<*>.() -> Unit): HttpClient =
    HttpClient(OkHttp) {
        engine {
            config {
                // Reads can be re-issued by the caller; mutations must never be
                // replayed by the transport.
                retryOnConnectionFailure(false)
                connectTimeout(10, TimeUnit.SECONDS)
                // No socket read timeout — SSE streams have no heartbeat and
                // downloads may stall; HttpTimeout bounds unary calls instead.
                readTimeout(0, TimeUnit.MILLISECONDS)
            }
        }
        apply(block)
    }
