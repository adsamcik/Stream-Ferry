package com.adsamcik.streamferry.data.jellyfin

import org.json.JSONObject
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class JellyfinWatchMutationJsonCodecTest {

    @Test
    fun roundTripPreservesAccountScopedAbsoluteIntents() {
        val entries = listOf(
            mutation("first", JellyfinWatchMutationKind.MARK_PLAYED, 10L),
            mutation("second", JellyfinWatchMutationKind.RESET_PROGRESS, 20L),
        )

        assertEquals(entries, JellyfinWatchMutationJsonCodec.decode(JellyfinWatchMutationJsonCodec.encode(entries)))
    }

    @Test
    fun malformedEntryDoesNotDiscardOtherPendingIntents() {
        val valid = mutation("valid", JellyfinWatchMutationKind.MARK_UNPLAYED, 10L)
        val document = JellyfinWatchMutationJsonCodec.encode(listOf(valid))
        document.getJSONArray("entries").put(JSONObject().apply {
            put("operationId", "bad")
            put("serverId", "server")
            put("userId", "user")
            put("itemId", "item")
            put("kind", "FUTURE_UNKNOWN_KIND")
            put("createdAtMillis", 20L)
        })

        assertEquals(listOf(valid), JellyfinWatchMutationJsonCodec.decode(document))
    }

    @Test
    fun unsupportedVersionIsRejectedAsAWholeDocument() {
        val document = JellyfinWatchMutationJsonCodec.encode(listOf(mutation("only", JellyfinWatchMutationKind.MARK_PLAYED, 1L)))
        document.put("version", 999)

        assertNull(JellyfinWatchMutationJsonCodec.decode(document))
    }

    private fun mutation(
        id: String,
        kind: JellyfinWatchMutationKind,
        createdAtMillis: Long,
    ) = JellyfinWatchMutation(
        operationId = id,
        serverId = "server",
        userId = "user",
        itemId = "item-$id",
        kind = kind,
        createdAtMillis = createdAtMillis,
    )
}
