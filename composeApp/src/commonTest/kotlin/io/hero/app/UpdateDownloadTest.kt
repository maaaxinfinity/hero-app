package io.hero.app

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

// Covers the pure, engine-free validation gates the updater applies around a
// download: status, declared size, and post-transfer size. The byte-copy loop
// itself (streamDownload) is exercised in desktopTest where a coroutine builder
// is available.
class UpdateDownloadTest {
    @Test
    fun rejectsNon2xxStatus() {
        // A 404/302/500 body (rate-limit page, redirect HTML, error text) must
        // never be treated as the asset.
        assertFailsWith<UpdateDownloadException> { validateDownloadStatus(404, 1000) }
        assertFailsWith<UpdateDownloadException> { validateDownloadStatus(302, 1000) }
        assertFailsWith<UpdateDownloadException> { validateDownloadStatus(500, null) }
    }

    @Test
    fun acceptsSuccessAndReturnsDeclaredLength() {
        assertEquals(1234L, validateDownloadStatus(200, 1234))
        assertEquals(1234L, validateDownloadStatus(206, 1234)) // partial content is 2xx
        assertNull(validateDownloadStatus(200, null))          // no Content-Length is allowed
    }

    @Test
    fun rejectsEmptyOrOversizeDeclaredLength() {
        assertFailsWith<UpdateDownloadException> { validateDownloadStatus(200, 0) }
        assertFailsWith<UpdateDownloadException> { validateDownloadStatus(200, -1) }
        assertFailsWith<UpdateDownloadException> { validateDownloadStatus(200, 11, maxBytes = 10) }
    }

    @Test
    fun completeSizeMustMatchDeclaredAndBeNonEmpty() {
        validateDownloadSize(received = 100, expected = 100) // exact match: ok
        validateDownloadSize(received = 100, expected = null) // no declared length: ok
        // A truncated stream (e.g. an idle read aborting a quiet source) is caught.
        assertFailsWith<UpdateDownloadException> { validateDownloadSize(received = 99, expected = 100) }
        assertFailsWith<UpdateDownloadException> { validateDownloadSize(received = 101, expected = 100) }
        // Nothing arrived at all.
        assertFailsWith<UpdateDownloadException> { validateDownloadSize(received = 0, expected = null) }
    }
}
