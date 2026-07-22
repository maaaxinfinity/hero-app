package io.hero.app

import io.ktor.client.HttpClient
import io.ktor.client.HttpClientConfig

// heroHttpClient builds every HttpClient in the app on an explicitly named
// engine. ServiceLoader discovery is not an option here: the desktop release
// jar is minified, and a stripped engine class behind a surviving
// META-INF/services entry crashes at first use (v0.5.7 shipped that way).
// Engine policy (both actuals use OkHttp):
//   - retryOnConnectionFailure(false): reads can be re-issued by the caller;
//     mutations must never be replayed by the transport.
//   - read timeout 0: SSE and download streams idle legitimately; unary calls
//     are bounded by the HttpTimeout plugin at the client instead.
expect fun heroHttpClient(block: HttpClientConfig<*>.() -> Unit = {}): HttpClient
