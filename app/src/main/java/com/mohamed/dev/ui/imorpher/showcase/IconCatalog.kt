package com.mohamed.dev.ui.imorpher.showcase

import androidx.compose.ui.graphics.vector.ImageVector
import com.mohamed.dev.ui.imorpher.icons.AddCircleOutline
import com.mohamed.dev.ui.imorpher.icons.AddCircleSolid
import com.mohamed.dev.ui.imorpher.icons.AdditemOutline
import com.mohamed.dev.ui.imorpher.icons.AdditemSolid
import com.mohamed.dev.ui.imorpher.icons.AlgorithmOutline
import com.mohamed.dev.ui.imorpher.icons.AlgorithmSolid
import com.mohamed.dev.ui.imorpher.icons.AnonymousOutline
import com.mohamed.dev.ui.imorpher.icons.AnonymousSolid
import com.mohamed.dev.ui.imorpher.icons.ArchiveOutline
import com.mohamed.dev.ui.imorpher.icons.ArchiveSolid
import com.mohamed.dev.ui.imorpher.icons.ArrangeCircle2Outline
import com.mohamed.dev.ui.imorpher.icons.ArrangeCircle2Solid
import com.mohamed.dev.ui.imorpher.icons.ArrangeCircleOutline
import com.mohamed.dev.ui.imorpher.icons.ArrangeCircleSolid
import com.mohamed.dev.ui.imorpher.icons.ArrowBackUp
import com.mohamed.dev.ui.imorpher.icons.ArrowDown
import com.mohamed.dev.ui.imorpher.icons.ArrowDown1
import com.mohamed.dev.ui.imorpher.icons.ArrowDown2Outline
import com.mohamed.dev.ui.imorpher.icons.ArrowDown2Solid
import com.mohamed.dev.ui.imorpher.icons.ArrowLeft1
import com.mohamed.dev.ui.imorpher.icons.ArrowLeft2Outline
import com.mohamed.dev.ui.imorpher.icons.ArrowLeft2Solid
import com.mohamed.dev.ui.imorpher.icons.ArrowRight1
import com.mohamed.dev.ui.imorpher.icons.ArrowRight2Outline
import com.mohamed.dev.ui.imorpher.icons.ArrowRight2Solid
import com.mohamed.dev.ui.imorpher.icons.ArrowUp1
import com.mohamed.dev.ui.imorpher.icons.ArrowUp2Outline
import com.mohamed.dev.ui.imorpher.icons.ArrowUp2Solid
import com.mohamed.dev.ui.imorpher.icons.IMorpherIcons

/** Visual kind of a catalog icon, derived from its name suffix. */
enum class IconKind { OUTLINE, SOLID, PLAIN }

/** One selectable icon in the playground picker. */
data class IconEntry(val name: String, val kind: IconKind)

/**
 * Registry over the generated `IMorpherIcons` set: playground pickers list these entries and
 * resolve them back to [ImageVector]s by name, so icon choices can live in plain state.
 */
object IconCatalog {

    private val entriesByName: Map<String, ImageVector> = buildMap {
        // outline/solid family pairs
        put("AddCircleOutline", IMorpherIcons.AddCircleOutline)
        put("AddCircleSolid", IMorpherIcons.AddCircleSolid)
        put("AdditemOutline", IMorpherIcons.AdditemOutline)
        put("AdditemSolid", IMorpherIcons.AdditemSolid)
        put("AlgorithmOutline", IMorpherIcons.AlgorithmOutline)
        put("AlgorithmSolid", IMorpherIcons.AlgorithmSolid)
        put("AnonymousOutline", IMorpherIcons.AnonymousOutline)
        put("AnonymousSolid", IMorpherIcons.AnonymousSolid)
        put("ArchiveOutline", IMorpherIcons.ArchiveOutline)
        put("ArchiveSolid", IMorpherIcons.ArchiveSolid)
        put("ArrangeCircle2Outline", IMorpherIcons.ArrangeCircle2Outline)
        put("ArrangeCircle2Solid", IMorpherIcons.ArrangeCircle2Solid)
        put("ArrangeCircleOutline", IMorpherIcons.ArrangeCircleOutline)
        put("ArrangeCircleSolid", IMorpherIcons.ArrangeCircleSolid)
        // stroke-only arrows
        put("ArrowBackUp", IMorpherIcons.ArrowBackUp)
        put("ArrowDown", IMorpherIcons.ArrowDown)
        put("ArrowDown1", IMorpherIcons.ArrowDown1)
        put("ArrowLeft1", IMorpherIcons.ArrowLeft1)
        put("ArrowRight1", IMorpherIcons.ArrowRight1)
        put("ArrowUp1", IMorpherIcons.ArrowUp1)
        // outline/solid arrow pairs
        put("ArrowDown2Outline", IMorpherIcons.ArrowDown2Outline)
        put("ArrowDown2Solid", IMorpherIcons.ArrowDown2Solid)
        put("ArrowLeft2Outline", IMorpherIcons.ArrowLeft2Outline)
        put("ArrowLeft2Solid", IMorpherIcons.ArrowLeft2Solid)
        put("ArrowRight2Outline", IMorpherIcons.ArrowRight2Outline)
        put("ArrowRight2Solid", IMorpherIcons.ArrowRight2Solid)
        put("ArrowUp2Outline", IMorpherIcons.ArrowUp2Outline)
        put("ArrowUp2Solid", IMorpherIcons.ArrowUp2Solid)
    }

    val entries: List<IconEntry> = entriesByName.keys.map { name ->
        IconEntry(
            name = name,
            kind = when {
                name.endsWith("Outline") -> IconKind.OUTLINE
                name.endsWith("Solid") -> IconKind.SOLID
                else -> IconKind.PLAIN
            },
        )
    }

    /** Curated morphable pairs: same-family outline→solid, plus a few arrow combinations. */
    val pairs: List<Pair<String, String>> = listOf(
        "ArrowUp1" to "ArrowUp2Solid",
        "AddCircleOutline" to "AddCircleSolid",
        "AdditemOutline" to "AdditemSolid",
        "AlgorithmOutline" to "AlgorithmSolid",
        "AnonymousOutline" to "AnonymousSolid",
        "ArchiveOutline" to "ArchiveSolid",
        "ArrangeCircle2Outline" to "ArrangeCircle2Solid",
        "ArrangeCircleOutline" to "ArrangeCircleSolid",
        "ArrowDown2Outline" to "ArrowDown2Solid",
        "ArrowLeft2Outline" to "ArrowLeft2Solid",
        "ArrowRight2Outline" to "ArrowRight2Solid",
        "ArrowUp2Outline" to "ArrowUp2Solid",
        "ArrowBackUp" to "ArrowDown",
        "ArrowDown1" to "ArrowUp1",
        "ArrowLeft1" to "ArrowRight1",
    )

    fun icon(name: String): ImageVector =
        entriesByName[name] ?: error("Unknown icon: $name")

    /** Whether [name] resolves in this catalog; used to validate restored state. */
    fun hasIcon(name: String): Boolean = entriesByName.containsKey(name)

    fun pair(firstName: String, secondName: String): Pair<String, String> = firstName to secondName

    fun byKind(kind: IconKind?): List<IconEntry> =
        if (kind == null) entries else entries.filter { it.kind == kind }
}
