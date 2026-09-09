package io.yosemitekids.app.data

/**
 * Which videos land on which shelf — the rules, not the drawing.
 *
 * **The companion to [io.yosemitekids.app.ui.HomeSections], one level down.**
 * That file answers *which shelves, in what order*; this one answers *what is
 * in them*. Both had to leave `:app` for the same reason: the browser is a
 * third face, and a shelf whose contents are decided in a ViewModel is a shelf
 * the web home has to re-derive — which is not a shared rule, it is two rules
 * that agree until one of them is edited.
 *
 * In `:crawl` rather than `:core` only because [Video] is here. Nothing in this
 * file touches the network, the disk or a clock; it is the same pure-rules
 * shape as `ConfigMerge`, and it is tested by the same suites that tested it
 * when it lived in the app — `SuggestionsTest` and `HistoryAndLayoutTest` still
 * call `:app`'s wrappers, which is the point: they prove the move changed no
 * behaviour rather than merely that the new copy compiles.
 *
 * **Generic over the item type on purpose.** The phone draws `VideoItem`, the
 * hub serializes JSON, and neither should have to adopt the other's class to
 * share a rule. Each caller passes the constructor for its own row type and
 * gets its own type back.
 */
object KidHome {

    /**
     * One point on a kid's watch history — the three things every shelf rule
     * here asks of it.
     *
     * A deliberate re-statement of `:app`'s `WatchProgress` rather than a move
     * of it: that class is a *store* format (`positionMs|durationMs|at`, in
     * SharedPreferences), and the hub's history is a different store with the
     * same meaning. What the rules need is the meaning. [isFinished] is carried
     * rather than recomputed from [fraction] so the 2%-credits grace stays
     * defined by whichever store the point came from, in one place.
     */
    data class WatchPoint(
        val fraction: Float,
        val lastWatchedAt: Long,
        val isFinished: Boolean
    )

    /** "Watched lately" on the home. The History *screen* uses its own, larger cap. */
    const val HISTORY_ROW_MAX = 12

    /** "More like what you watch". */
    const val SUGGEST_ROW_MAX = 12

    /** How far back suggestions look. Beyond this the recency weight is noise anyway. */
    const val SUGGEST_HISTORY_DEPTH = 40

    const val KEEP_WATCHING_MAX = 10

    /**
     * Below this a video counts as never really started, and Keep watching does
     * not offer it back. A kid who opened something, saw four seconds and left
     * did not mean to start it, and a row full of those is a row of mistakes.
     */
    const val KEEP_WATCHING_MIN_FRACTION = 0.02f

    /**
     * Words that carry no signal about what a video is *about*. Kept small and
     * hand-picked rather than a real stoplist: the titles here are kids'
     * YouTube, where "for kids", "episode" and "full" appear on everything and
     * would otherwise dominate every overlap score.
     */
    private val SUGGEST_STOPWORDS = setOf(
        "the", "and", "for", "you", "your", "with", "from", "this", "that", "what",
        "how", "why", "who", "all", "new", "not", "but", "can", "his", "her", "its",
        "our", "out", "one", "two", "get", "got", "are", "was", "were", "has", "had",
        "kids", "kid", "children", "child", "video", "videos", "episode", "episodes",
        "full", "part", "official", "watch", "more", "best", "top", "compilation",
        "season", "vs", "ft", "feat", "live", "let", "lets", "make", "made", "day"
    )

    /**
     * Anything that isn't a letter or a digit splits words — Unicode-aware, so
     * a title in any script the family watches still tokenizes.
     */
    private val NON_WORD = Regex("""[^\p{L}\p{N}]+""")

    /**
     * Title words worth matching on: letters and digits only, lowercased, three
     * characters or more, stopwords dropped. A set, so a title that says
     * "dinosaur" four times counts once.
     */
    fun titleKeywords(title: String): Set<String> =
        title.lowercase()
            .split(NON_WORD)
            .filter { it.length >= 3 && it !in SUGGEST_STOPWORDS }
            .toSet()

    /**
     * Round-robin across the lists, first of each, then second of each, until
     * [max] — the home feed's mixer.
     *
     * Deduplicated by [key], because the same video legitimately appears in two
     * channels' caches (a collaboration, a re-upload) and a feed that showed it
     * twice would look like a bug in the crawler.
     */
    fun <T> interleave(lists: List<List<T>>, max: Int, key: (T) -> Any): List<T> {
        val out = ArrayList<T>()
        val seen = HashSet<Any>()
        var depth = 0
        while (out.size < max && lists.any { it.size > depth }) {
            for (list in lists) {
                if (out.size >= max) break
                list.getOrNull(depth)?.let { if (seen.add(key(it))) out += it }
            }
            depth++
        }
        return out
    }

    /** Videos of each channel taken into the feed before the mixer runs. */
    const val FEED_PER_CHANNEL = 12

    /** The whole home feed's ceiling. */
    const val FEED_MAX = 80

    /**
     * The History shelf: every video with a watch timestamp, newest first,
     * joined to whatever metadata the caches hold (first match wins — the same
     * video can sit in two sources' caches). Anything the caches have since
     * forgotten drops out: without a title or poster there is nothing to show.
     */
    fun <T> history(
        watched: Map<String, WatchPoint>,
        known: List<Video>,
        limit: Int,
        item: (Video, Float) -> T
    ): List<T> {
        val byUrl = HashMap<String, Video>(known.size)
        for (v in known) byUrl.putIfAbsent(v.url, v)
        return watched.entries.asSequence()
            .filter { (url, p) -> p.lastWatchedAt > 0 && url in byUrl }
            .sortedByDescending { it.value.lastWatchedAt }
            .take(limit)
            .map { (url, p) -> item(byUrl.getValue(url), p.fraction) }
            .toList()
    }

    /**
     * Keep watching: partially-watched, unfinished videos, most recently
     * watched first.
     *
     * [known] is expected to arrive **already filtered** — blocked videos gone,
     * screening-held videos gone, too-short videos gone. That is not vagueness
     * about where the filter belongs: those three questions need a config, a
     * verdict store and a limits object this module deliberately does not take,
     * and every caller has already had to answer them to draw any other shelf.
     * What this owns is *unfinished, recent, capped*.
     */
    fun <T> keepWatching(
        known: List<Video>,
        point: (String) -> WatchPoint?,
        limit: Int = KEEP_WATCHING_MAX,
        item: (Video, Float) -> T
    ): List<T> =
        known.asSequence()
            .distinctBy { it.url }
            .mapNotNull { video ->
                point(video.url)
                    ?.takeIf { !it.isFinished && it.fraction > KEEP_WATCHING_MIN_FRACTION }
                    ?.let { item(video, it.fraction) to it.lastWatchedAt }
            }
            .sortedByDescending { it.second }
            .take(limit)
            .map { it.first }
            .toList()

    /**
     * "More like what you watch": unwatched videos scored by how much their
     * titles overlap the titles the kid actually watched, most recent watches
     * weighted highest, plus a nudge for channels they come back to.
     *
     * Deliberately local and explainable — no model, no network, no view
     * counts. Everything it knows comes from this kid's own history, which is
     * also why it is pure: the interesting cases (a kid with one watch, a kid
     * whose whole history is one channel) are unit tests rather than a guess.
     *
     * [watchedTitles] must be newest-first. [perChannelCap] stops a single
     * prolific channel from owning the row — the point is to widen what they
     * see, not to rebuild the channel page they already have.
     */
    fun <T> suggestions(
        watchedTitles: List<String>,
        candidates: List<T>,
        video: (T) -> Video,
        channelAffinity: Map<String, Int>,
        limit: Int,
        perChannelCap: Int = 2
    ): List<T> {
        if (watchedTitles.isEmpty() || candidates.isEmpty()) return emptyList()

        // Recency weight: the last thing they watched says more about what they
        // want next than the fiftieth thing back, but old watches still count.
        val watchedSets = watchedTitles.take(SUGGEST_HISTORY_DEPTH)
            .mapIndexed { i, t -> titleKeywords(t) to 1.0 / (1.0 + i * 0.2) }
            .filter { it.first.isNotEmpty() }
        if (watchedSets.isEmpty()) return emptyList()

        val maxAffinity = channelAffinity.values.maxOrNull()?.takeIf { it > 0 } ?: 1

        val scored = candidates.mapNotNull { candidate ->
            val v = video(candidate)
            val words = titleKeywords(v.title)
            if (words.isEmpty()) return@mapNotNull null
            var score = 0.0
            for ((watched, weight) in watchedSets) {
                val shared = words.count { it in watched }
                if (shared > 0) score += shared * weight
            }
            if (score <= 0.0) return@mapNotNull null
            // A channel they return to is worth a thumb on the scale, but never
            // enough to promote an unrelated video over a genuine title match.
            val affinity = (channelAffinity[v.channelName] ?: 0).toDouble() / maxAffinity
            candidate to score + affinity * 0.5
        }.sortedByDescending { it.second }

        val perChannel = HashMap<String, Int>()
        val out = ArrayList<T>(limit)
        for ((candidate, _) in scored) {
            if (out.size >= limit) break
            val channel = video(candidate).channelName
            val seen = perChannel.getOrDefault(channel, 0)
            if (seen >= perChannelCap) continue
            perChannel[channel] = seen + 1
            out += candidate
        }
        return out
    }
}
