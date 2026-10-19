package haruka.selection

import androidx.compose.foundation.combinedClickable
import androidx.compose.ui.Modifier

/** Tap plus an optional long press, for upstream rows that only take a tap. */
fun itemClickModifier(onClick: () -> Unit, onLongClick: (() -> Unit)?): Modifier =
    Modifier.combinedClickable(onClick = onClick, onLongClick = onLongClick)
