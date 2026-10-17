package com.byd.extend.ui.motion

import java.io.Serializable
import java.util.UUID

internal data class MotionText(
    val id: String = UUID.randomUUID().toString(),
    // A default caption stays localized until the owner edits its text.
    val defaultCaption: Int = 0,
    val text: String = "",
    val x: Float = .08f,
    val y: Float = .1f,
    val width: Float = .84f,
    val size: Int = 32,
    val color: Int = -1,
    val font: Int = 0,
    val bold: Boolean = true,
    val italic: Boolean = false,
    val alignment: Int = 1,
    val effect: Int = 1,
) : Serializable {
    fun placed(left: Float, top: Float, height: Float = 0f): MotionText = copy(
        x = left.coerceIn(0f, 1f - width.coerceIn(.2f, 1f)),
        y = top.coerceIn(0f, 1f - height.coerceIn(0f, 1f)))

    fun resized(leftEdge: Boolean, delta: Float): MotionText {
        val right = (x + width).coerceIn(.2f, 1f)
        return if (leftEdge) {
            val left = (x + delta).coerceIn(0f, right - .2f)
            copy(x = left, width = right - left)
        } else copy(width = (width + delta).coerceIn(.2f, 1f - x))
    }
}

internal data class MotionTemplate(
    val id: String,
    val name: String = "",
    val background: String,
    val fill: Boolean = false,
    val texts: List<MotionText> = listOf(
        MotionText(defaultCaption = 1, y = .06f),
        MotionText(defaultCaption = 2, y = .82f, size = 38)),
) : Serializable {
    val isBase: Boolean get() = id.startsWith("base:")
    fun withoutBlankTexts(keepId: String? = null): MotionTemplate = copy(texts = texts.filter {
        it.id == keepId || it.defaultCaption != 0 || it.text.isNotBlank()
    })
    fun forSave(): MotionTemplate = withoutBlankTexts().let {
        if (isBase || id.isEmpty()) it.copy(id = UUID.randomUUID().toString()) else it
    }
    fun replace(block: MotionText): MotionTemplate = copy(texts = texts.map { if (it.id == block.id) block else it })

    companion object {
        fun bases() = listOf(
            MotionTemplate("base:lens", background = "lens", texts = listOf(
                MotionText(defaultCaption = 1, x = .047716923f, y = .07207679f, width = .33256823f,
                    size = 24, alignment = 0),
                MotionText(defaultCaption = 2, x = .7089975f, y = .7736747f, width = .23657632f,
                    size = 30, italic = true, alignment = 2))),
            MotionTemplate("base:iris", background = "iris", texts = listOf(
                MotionText(defaultCaption = 1, x = .07364574f, y = .08744979f, width = .42f, size = 24,
                    color = 0xFF18212B.toInt(), alignment = 3, effect = 0),
                MotionText(defaultCaption = 2, x = .08200293f, y = .78235793f, width = .48884743f, size = 30,
                    color = 0xFF18212B.toInt(), italic = true, alignment = 0, effect = 0))),
            MotionTemplate("base:owl", background = "owl", fill = true, texts = listOf(
                MotionText(defaultCaption = 1, x = .6203457f, y = .08779754f, width = .36409742f, size = 26,
                    color = 0xFF888888.toInt(), alignment = 2),
                MotionText(defaultCaption = 2, x = .00789316f, y = .8601621f, width = .24607787f, size = 30,
                    color = 0xFF888888.toInt(), italic = true, alignment = 0))))
    }
}

internal data class MotionTemplates(
    val saved: List<MotionTemplate> = emptyList(),
    val activeId: String = "base:lens",
) {
    fun active(): MotionTemplate = (MotionTemplate.bases() + saved).firstOrNull { it.id == activeId }
        ?: MotionTemplate.bases().first()
    fun initialChoice(choices: List<MotionTemplate>): MotionTemplate? =
        choices.firstOrNull { it.id == activeId } ?: choices.firstOrNull()
    fun save(template: MotionTemplate): MotionTemplates {
        require(!template.isBase && template.id.isNotEmpty())
        return copy(saved = saved.filterNot { it.id == template.id } + template, activeId = template.id)
    }
    fun delete(id: String): MotionTemplates = copy(saved = saved.filterNot { it.id == id },
        activeId = if (activeId == id) "base:lens" else activeId)
}
