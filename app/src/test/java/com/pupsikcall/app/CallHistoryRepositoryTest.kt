package com.pupsikcall.app

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

class CallHistoryRepositoryTest {
    @Test
    fun emptyHistoryStaysEmptyWithoutFallbackRecordsOrNames() = runBlocking {
        val repository = CallHistoryRepository(FakeCallHistoryGateway())
        repository.loadHistory()

        assertEquals(CallHistoryState.Empty, repository.state.value)
        repository.close()
    }

    @Test
    fun incomingAndOutgoingDirectionComesFromHistoryRows() = runBlocking {
        val gateway = FakeCallHistoryGateway().apply {
            rows = listOf(
                row(id(2), "2026-09-29T10:00:00Z", direction = "incoming"),
                row(id(1), "2026-09-29T09:00:00Z", direction = "outgoing"),
            )
        }
        val repository = CallHistoryRepository(gateway)
        repository.loadHistory()

        val calls = (repository.state.value as CallHistoryState.Loaded).calls
        assertEquals(listOf(CallHistoryDirection.Incoming, CallHistoryDirection.Outgoing), calls.map { it.direction })
        assertTrue(calls.all { it.counterpartUserId == counterpartId })
        repository.close()
    }

    @Test
    fun historyUsesNewestTimestampThenDescendingCallIdOrder() = runBlocking {
        val gateway = FakeCallHistoryGateway().apply {
            rows = listOf(
                row(id(3), "2026-09-29T10:00:00Z"),
                row(id(2), "2026-09-29T10:00:00Z"),
                row(id(4), "2026-09-29T09:59:59Z"),
            )
        }
        val repository = CallHistoryRepository(gateway)
        repository.loadHistory()

        val calls = (repository.state.value as CallHistoryState.Loaded).calls
        assertEquals(listOf(id(3), id(2), id(4)), calls.map(CallHistoryRecord::callId))
        repository.close()
    }

    @Test
    fun paginationUsesTimestampAndCallIdCursorAndMergesOlderPage() = runBlocking {
        val gateway = FakeCallHistoryGateway().apply {
            rows = (1..51).map { row(id(it), "2026-09-29T10:00:00Z") }
        }
        val repository = CallHistoryRepository(gateway)
        repository.loadHistory()
        val firstPage = repository.state.value as CallHistoryState.Loaded
        assertEquals(50, firstPage.calls.size)
        assertEquals(id(2), firstPage.nextCursor?.callId)

        repository.loadHistory(firstPage.nextCursor)
        val secondPage = repository.state.value as CallHistoryState.Loaded
        assertEquals(51, secondPage.calls.size)
        assertEquals(id(51), secondPage.calls.first().callId)
        assertEquals(id(1), secondPage.calls.last().callId)
        assertNull(secondPage.nextCursor)
        repository.close()
    }

    @Test
    fun duplicateRowsAreDeduplicatedByStableCallId() = runBlocking {
        val duplicate = row(id(3), "2026-09-29T10:00:00Z")
        val repository = CallHistoryRepository(FakeCallHistoryGateway().apply {
            rows = listOf(duplicate, duplicate, row(id(2), "2026-09-29T09:00:00Z"))
        })
        repository.loadHistory()

        val calls = (repository.state.value as CallHistoryState.Loaded).calls
        assertEquals(2, calls.size)
        assertEquals(1, calls.count { it.callId == id(3) })
        repository.close()
    }

    @Test
    fun durationRequiresConnectedAndEndedTimestampsAndUsesServerElapsedSeconds() = runBlocking {
        val gateway = FakeCallHistoryGateway().apply {
            rows = listOf(
                row(id(2), "2026-09-29T10:00:00Z", durationSeconds = 73),
                row(id(1), "2026-09-29T09:00:00Z", durationSeconds = 120, connectedAt = null),
            )
        }
        val repository = CallHistoryRepository(gateway)
        repository.loadHistory()

        val calls = (repository.state.value as CallHistoryState.Loaded).calls
        assertEquals(73L, calls.first().durationSeconds)
        assertNull(calls.last().durationSeconds)
        repository.close()
    }

    @Test
    fun everyAuthoritativeTerminalStatusMapsWithoutInventingValues() = runBlocking {
        val statuses = listOf("completed", "declined", "cancelled", "missed", "failed")
        val repository = CallHistoryRepository(FakeCallHistoryGateway().apply {
            rows = statuses.mapIndexed { index, status ->
                row(id(statuses.size - index), "2026-09-29T10:00:00Z", status = status)
            }
        })
        repository.loadHistory()

        val calls = (repository.state.value as CallHistoryState.Loaded).calls
        assertEquals(
            setOf(
                AuthenticatedCallStatus.COMPLETED,
                AuthenticatedCallStatus.DECLINED,
                AuthenticatedCallStatus.CANCELLED,
                AuthenticatedCallStatus.MISSED,
                AuthenticatedCallStatus.FAILED,
            ),
            calls.map(CallHistoryRecord::status).toSet(),
        )
        assertTrue(calls.all { it.status.isTerminal })
        repository.close()
    }

    @Test
    fun sessionLossDuringPageLoadClearsRowsAsSignedOut() = runBlocking {
        val gateway = FakeCallHistoryGateway().apply {
            rows = listOf(row(id(1), "2026-09-29T10:00:00Z"))
            loseSessionAfterRead = true
        }
        val repository = CallHistoryRepository(gateway)
        repository.loadHistory()

        assertEquals(CallHistoryState.SignedOut, repository.state.value)
        repository.close()
    }

    @Test
    fun failuresExposeOnlySanitizedErrorState() = runBlocking {
        val repository = CallHistoryRepository(FakeCallHistoryGateway().apply {
            failure = IllegalStateException("access_token=secret phone=+15551234567")
        })
        repository.loadHistory()

        assertEquals(CallHistoryState.Error, repository.state.value)
        assertFalse(repository.state.value.toString().contains("secret"))
        assertFalse(repository.state.value.toString().contains("+15551234567"))
        repository.close()
    }

    @Test
    fun missingProfileNameIsNotReplacedWithFakeData() = runBlocking {
        val repository = CallHistoryRepository(FakeCallHistoryGateway().apply {
            rows = listOf(row(id(1), "2026-09-29T10:00:00Z", displayName = null))
        })
        repository.loadHistory()

        val call = (repository.state.value as CallHistoryState.Loaded).calls.single()
        assertNull(call.counterpartDisplayName)
        assertEquals(counterpartId, call.counterpartUserId)
        repository.close()
    }

    private class FakeCallHistoryGateway : CallHistoryGateway {
        var userId: UUID? = currentUserId
        var rows: List<CallHistoryRow> = emptyList()
        var failure: Exception? = null
        var loseSessionAfterRead = false

        override suspend fun currentUserId(): UUID? = userId

        override suspend fun listCallHistory(
            userId: UUID,
            before: CallHistoryCursor?,
            limit: Int,
        ): List<CallHistoryRow> {
            failure?.let { throw it }
            val page = rows.sortedWith(
                compareByDescending<CallHistoryRow> { it.createdAt }
                    .thenByDescending { it.callId.toString() },
            ).filter { row ->
                before == null || row.createdAt < before.createdAt ||
                    row.createdAt == before.createdAt && row.callId.toString() < before.callId.toString()
            }.take(limit)
            if (loseSessionAfterRead) this.userId = null
            return page
        }
    }

    companion object {
        private val currentUserId = UUID.fromString("00000000-0000-4000-8000-000000000001")
        private val counterpartId = UUID.fromString("00000000-0000-4000-8000-000000000002")

        private fun id(value: Int) = UUID.fromString(
            "00000000-0000-4000-8000-${value.toString().padStart(12, '0')}",
        )

        private fun row(
            callId: UUID,
            createdAt: String,
            direction: String = "outgoing",
            status: String = "completed",
            durationSeconds: Long? = null,
            connectedAt: String? = "2026-09-29T09:59:00Z",
            displayName: String? = "Real profile",
        ) = CallHistoryRow(
            callId = callId,
            counterpartUserId = counterpartId,
            direction = direction,
            status = status,
            createdAt = createdAt,
            acceptedAt = connectedAt,
            connectedAt = connectedAt,
            endedAt = connectedAt?.let { "2026-09-29T10:00:13Z" },
            durationSeconds = durationSeconds,
            counterpartDisplayName = displayName,
            counterpartAvatarPath = null,
        )
    }
}
