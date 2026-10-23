package haruka.reader

/** What the automatic reading mode does for a series when the reader opens it. */
enum class AutoModeStep {
    /** Leave the series' mode alone. */
    Keep,

    /** Its tags say long strip: save Long strip as its mode before the first viewer is made. */
    LongStripFromTags,

    /** Nothing to go on yet: measure pages of the opened chapter. */
    MeasurePages,
}

/**
 * Only a series still on the Default reading mode is ever touched, and only once: [decided] is true after a past
 * detection, so a mode the user picks afterwards, Default included, is never overridden.
 */
fun autoModeStep(
    enabled: Boolean,
    storedModeIsDefault: Boolean,
    decided: Boolean,
    genres: List<String>?,
    sourceName: String?,
): AutoModeStep = when {
    !enabled || !storedModeIsDefault || decided -> AutoModeStep.Keep
    LongStripTags.matches(genres, sourceName) -> AutoModeStep.LongStripFromTags
    else -> AutoModeStep.MeasurePages
}
