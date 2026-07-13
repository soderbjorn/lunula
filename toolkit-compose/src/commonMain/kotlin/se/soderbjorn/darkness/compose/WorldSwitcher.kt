/**
 * Compose world switcher — the mobile counterpart to toolkit-web's globe
 * switcher.
 *
 * Greenfield: toolkit-compose previously shipped only [DarknessPalette], so
 * this is the first interactive component here. It renders a globe
 * [IconButton] that opens a dropdown listing the app's *worlds* (the
 * workspace container one level above tabs), with a checkmark on the active
 * world; tapping a row switches worlds and long-pressing one reveals Rename /
 * Close(+confirm). It is deliberately model-light: it takes a flat
 * [WorldMenuEntry] list and callbacks rather than depending on toolkit-store's
 * `WorldsState`, so mobile hosts (Lunamux Android) can feed it straight from
 * their server-driven world model.
 *
 * @see WorldSwitcher
 * @see DarknessPalette
 */
package se.soderbjorn.darkness.compose

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * One world in the switcher's menu.
 *
 * @property id   stable world identifier reported back through the
 *   switcher's callbacks.
 * @property name visible world name.
 */
data class WorldMenuEntry(
    val id: String,
    val name: String,
)

/**
 * A globe icon button that opens a dropdown for switching and managing
 * worlds. Drop this into a top-bar actions row.
 *
 * The dropdown lists [worlds] (a leading checkmark marks [activeWorldId]);
 * tapping a row fires [onSelect]. **Long-pressing** a row opens a small manage
 * menu whose Rename fires [onRename] (after a name prompt) and whose Close
 * fires [onClose] (after a confirmation, disabled for the last remaining
 * world) — there is no per-row "⋯" button. A "New world" row fires [onAdd]
 * (after a name prompt) when supplied; hosts that create worlds from their own
 * "+" affordance simply leave [onAdd] null. Passing `null` for any callback
 * hides its action.
 *
 * @param worlds        ordered world list (first = default world).
 * @param activeWorldId id of the active world, or null.
 * @param onSelect      invoked with a world id when the user picks a row.
 * @param onAdd         invoked with a new name, or null to hide "New world"
 *   (e.g. when the host offers world creation elsewhere).
 * @param onRename      invoked with (id, newName), or null to hide Rename.
 * @param onClose       invoked with an id, or null to hide Close.
 * @param tint          globe icon colour; defaults to the ambient content
 *   colour so it matches the surrounding top-bar icons.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun WorldSwitcher(
    worlds: List<WorldMenuEntry>,
    activeWorldId: String?,
    onSelect: (String) -> Unit,
    onAdd: ((String) -> Unit)? = null,
    onRename: ((String, String) -> Unit)? = null,
    onClose: ((String) -> Unit)? = null,
    tint: Color = LocalContentColor.current,
) {
    var menuOpen by remember { mutableStateOf(false) }
    // The world whose long-press manage menu (Rename / Close) is open, if any.
    var manageWorld by remember { mutableStateOf<WorldMenuEntry?>(null) }
    // A row shows a manage menu on long-press only if there is something to
    // manage; otherwise long-press is a no-op.
    val canManage = onRename != null || onClose != null
    // Pending dialogs: a name prompt (title + initial + commit) or a close
    // confirmation. Modelled as nullable state so only one shows at a time.
    var namePrompt by remember { mutableStateOf<NamePromptRequest?>(null) }
    var closeConfirm by remember { mutableStateOf<WorldMenuEntry?>(null) }

    IconButton(onClick = { menuOpen = true }) {
        GlobeIcon(tint = tint)
    }

    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
        worlds.forEach { world ->
            val isActive = world.id == activeWorldId
            // A hand-rolled row (rather than DropdownMenuItem) so it can carry a
            // long-press gesture: tap switches worlds; long-press opens the
            // Rename / Close manage menu — replacing the old per-row "⋯" button.
            // The enclosing DropdownMenu sizes its column to IntrinsicSize.Max,
            // so fillMaxWidth here resolves to the widest row (menu-item-like).
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .combinedClickable(
                        onClick = {
                            menuOpen = false
                            onSelect(world.id)
                        },
                        onLongClick = if (canManage) {
                            {
                                menuOpen = false
                                manageWorld = world
                            }
                        } else {
                            null
                        },
                    )
                    .padding(horizontal = 12.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(if (isActive) "✓" else " ", modifier = Modifier.width(20.dp))
                Text(
                    world.name,
                    fontWeight = if (isActive) FontWeight.SemiBold else FontWeight.Normal,
                )
            }
        }
        if (onAdd != null) {
            HorizontalDivider()
            DropdownMenuItem(
                text = { Text("New workspace") },
                onClick = {
                    menuOpen = false
                    namePrompt = NamePromptRequest(
                        title = "New workspace",
                        initial = "",
                        onCommit = { onAdd(it) },
                    )
                },
            )
        }
    }

    // Per-world manage menu (Rename / Close), opened by long-pressing a row and
    // anchored near the globe button.
    manageWorld?.let { world ->
        DropdownMenu(expanded = true, onDismissRequest = { manageWorld = null }) {
            if (onRename != null) {
                DropdownMenuItem(
                    text = { Text("Rename") },
                    onClick = {
                        manageWorld = null
                        namePrompt = NamePromptRequest(
                            title = "Rename workspace",
                            initial = world.name,
                            onCommit = { onRename(world.id, it) },
                        )
                    },
                )
            }
            if (onClose != null) {
                val isLast = worlds.size <= 1
                DropdownMenuItem(
                    text = { Text("Close", color = if (isLast) tint.copy(alpha = 0.4f) else Color.Unspecified) },
                    enabled = !isLast,
                    onClick = {
                        manageWorld = null
                        closeConfirm = world
                    },
                )
            }
        }
    }

    namePrompt?.let { req ->
        WorldNameDialog(
            title = req.title,
            initial = req.initial,
            onDismiss = { namePrompt = null },
            onCommit = { name ->
                namePrompt = null
                req.onCommit(name)
            },
        )
    }

    closeConfirm?.let { world ->
        AlertDialog(
            onDismissRequest = { closeConfirm = null },
            title = { Text("Close workspace") },
            text = {
                Text("Close “${world.name}”? This deletes every tab and session inside it.")
            },
            confirmButton = {
                TextButton(onClick = {
                    closeConfirm = null
                    onClose?.invoke(world.id)
                }) { Text("Close workspace") }
            },
            dismissButton = {
                TextButton(onClick = { closeConfirm = null }) { Text("Cancel") }
            },
        )
    }
}

/** A name-prompt request: dialog title, pre-filled text, and its commit. */
private data class NamePromptRequest(
    val title: String,
    val initial: String,
    val onCommit: (String) -> Unit,
)

/**
 * Small modal name prompt used by New / Rename. Commits the trimmed,
 * non-blank value; Cancel / dismiss discards.
 */
@Composable
private fun WorldNameDialog(
    title: String,
    initial: String,
    onDismiss: () -> Unit,
    onCommit: (String) -> Unit,
) {
    var text by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            TextButton(
                enabled = text.trim().isNotEmpty(),
                onClick = { onCommit(text.trim()) },
            ) { Text("OK") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}

/**
 * A globe glyph drawn with [Canvas] — an outer circle, an equator, and one
 * meridian ellipse — matching the toolkit-web globe mark. Drawn rather than
 * loaded from an icon font so toolkit-compose needs no extended-icons
 * dependency.
 *
 * @param tint stroke colour.
 */
@Composable
private fun GlobeIcon(tint: Color) {
    Canvas(modifier = Modifier.size(20.dp).padding(1.dp)) {
        val stroke = Stroke(width = size.minDimension * 0.08f)
        val r = size.minDimension / 2f
        val c = Offset(size.width / 2f, size.height / 2f)
        // Outer circle.
        drawCircle(color = tint, radius = r, center = c, style = stroke)
        // Equator.
        drawLine(
            color = tint,
            start = Offset(c.x - r, c.y),
            end = Offset(c.x + r, c.y),
            strokeWidth = stroke.width,
        )
        // Meridian ellipse (narrow).
        drawOval(
            color = tint,
            topLeft = Offset(c.x - r * 0.45f, c.y - r),
            size = androidx.compose.ui.geometry.Size(r * 0.9f, r * 2f),
            style = stroke,
        )
    }
}

/** Circle-shaped export retained for callers that want the shape token. */
@Suppress("unused")
internal val WorldSwitcherIconShape = CircleShape
