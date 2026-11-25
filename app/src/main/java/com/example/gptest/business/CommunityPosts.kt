package com.example.gptest.business

data class CommunityPost(
    val id: Long,
    val createdAtSeconds: Long,
    val text: String,
    val url: String
)

object CommunityAccount {
    const val UID = 819295397L
    const val NAME = "重生之散户一哥"
    const val HOME = "https://t.10jqka.com.cn/lgt/community/personal-homepage.html?user_code=1179giyc4jr06d6785e69e"
    const val POSTS = "https://t.10jqka.com.cn/lgt/post/open/api/user/post?uid=$UID&page=1&page_size=15&pid=0"
    val intervals = listOf(15, 30, 60)
}

data class CommunityCheckpoint(
    val initialized: Boolean = false,
    val newestAtSeconds: Long = 0,
    val seenIds: Set<Long> = emptySet()
)

data class CommunityChanges(
    val checkpoint: CommunityCheckpoint,
    val newPosts: List<CommunityPost>,
    val possibleGap: Boolean
)

object CommunityPostDetector {
    fun compare(previous: CommunityCheckpoint, posts: List<CommunityPost>): CommunityChanges {
        // Empty responses must never establish or erase a baseline.
        if (posts.isEmpty()) return CommunityChanges(previous, emptyList(), false)
        val unique = posts.distinctBy { it.id }
        val fresh = if (!previous.initialized) emptyList() else unique.filter {
            it.id !in previous.seenIds && it.createdAtSeconds >= previous.newestAtSeconds
        }.sortedWith(compareBy<CommunityPost> { it.createdAtSeconds }.thenBy { it.id })
        val gap = previous.initialized && unique.size >= 15 &&
            unique.none { it.id in previous.seenIds } &&
            unique.all { it.createdAtSeconds > previous.newestAtSeconds }
        // Recent response IDs first; persistence keeps restarts and edited posts quiet.
        val ids = (unique.map { it.id } + previous.seenIds).distinct().take(512).toSet()
        return CommunityChanges(
            CommunityCheckpoint(true, maxOf(previous.newestAtSeconds, unique.maxOf { it.createdAtSeconds }), ids),
            fresh,
            gap
        )
    }

    fun retrySeconds(interval: Int, failures: Int): Long =
        (interval.toLong() * (1L shl failures.coerceIn(0, 5))).coerceAtMost(900)
}
