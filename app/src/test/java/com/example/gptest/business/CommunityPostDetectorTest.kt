package com.example.gptest.business

import org.junit.Assert.*
import org.junit.Test

class CommunityPostDetectorTest {
    private fun post(id: Long, time: Long = id) = CommunityPost(id, time, "动态 $id", "https://c.10jqka.com.cn/post/$id")

    @Test fun firstNonemptyResponseOnlyEstablishesBaseline() {
        val empty = CommunityPostDetector.compare(CommunityCheckpoint(), emptyList())
        assertFalse(empty.checkpoint.initialized)
        val result = CommunityPostDetector.compare(empty.checkpoint, listOf(post(3), post(2)))
        assertTrue(result.checkpoint.initialized)
        assertTrue(result.newPosts.isEmpty())
        assertEquals(setOf(3L, 2L), result.checkpoint.seenIds)
    }

    @Test fun duplicateAndEditedPostsDoNotNotifyAgain() {
        val initial = CommunityPostDetector.compare(CommunityCheckpoint(), listOf(post(3))).checkpoint
        val result = CommunityPostDetector.compare(initial, listOf(post(3).copy(text = "编辑后的内容"), post(3)))
        assertTrue(result.newPosts.isEmpty())
        assertEquals(initial, result.checkpoint)
    }

    @Test fun allNewPostsAreDeliveredOnceInPublicationOrder() {
        val initial = CommunityPostDetector.compare(CommunityCheckpoint(), listOf(post(3))).checkpoint
        val result = CommunityPostDetector.compare(initial, listOf(post(5), post(4), post(4), post(3)))
        assertEquals(listOf(4L, 5L), result.newPosts.map { it.id })
        val restored = result.checkpoint.copy(seenIds = result.checkpoint.seenIds.toSet())
        assertTrue(CommunityPostDetector.compare(restored, listOf(post(5), post(4))).newPosts.isEmpty())
    }

    @Test fun sameSecondPostsAnd64BitIdsAreSupported() {
        val initial = CommunityPostDetector.compare(CommunityCheckpoint(), listOf(post(2427500719, 100))).checkpoint
        val result = CommunityPostDetector.compare(initial, listOf(post(2427500720, 100), post(2427500719, 100)))
        assertEquals(2427500720L, result.newPosts.single().id)
    }

    @Test fun oldPinnedPostsAndStaleResponsesDoNotMoveWatermarkBackwards() {
        val initial = CommunityPostDetector.compare(CommunityCheckpoint(), listOf(post(50))).checkpoint
        val stale = CommunityPostDetector.compare(initial, listOf(post(2), post(1)))
        assertTrue(stale.newPosts.isEmpty())
        assertEquals(50L, stale.checkpoint.newestAtSeconds)
        assertEquals(listOf(51L), CommunityPostDetector.compare(stale.checkpoint,
            listOf(post(51), post(50), post(1))).newPosts.map { it.id })
    }

    @Test fun emptyResponsePreservesCheckpoint() {
        val initial = CommunityPostDetector.compare(CommunityCheckpoint(), listOf(post(3))).checkpoint
        assertEquals(initial, CommunityPostDetector.compare(initial, emptyList()).checkpoint)
    }

    @Test fun fullPageWithoutOverlapWarnsAboutMissedPosts() {
        val initial = CommunityPostDetector.compare(CommunityCheckpoint(), listOf(post(1))).checkpoint
        val result = CommunityPostDetector.compare(initial, (10L..24L).map { post(it) })
        assertTrue(result.possibleGap)
        assertEquals(15, result.newPosts.size)
        assertFalse(CommunityPostDetector.compare(result.checkpoint, (11L..25L).map { post(it) }).possibleGap)
    }

    @Test fun historyIsBoundedAndDoesNotReadmitOldPosts() {
        var checkpoint = CommunityCheckpoint()
        for (id in 1L..600L) checkpoint = CommunityPostDetector.compare(checkpoint, listOf(post(id))).checkpoint
        assertEquals(512, checkpoint.seenIds.size)
        assertTrue(CommunityPostDetector.compare(checkpoint, listOf(post(1))).newPosts.isEmpty())
    }

    @Test fun errorsBackOffAndSuccessfulPollingUsesSelectedInterval() {
        assertEquals(30L, CommunityPostDetector.retrySeconds(30, 0))
        assertEquals(60L, CommunityPostDetector.retrySeconds(30, 1))
        assertEquals(120L, CommunityPostDetector.retrySeconds(30, 2))
        assertEquals(900L, CommunityPostDetector.retrySeconds(60, 100))
    }
}
