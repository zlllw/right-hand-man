package com.termux.app.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.dp

/**
 * 手信线框图标集（24 网格、1.6 描边、圆头），路径取自 site/rhm-mobile-demo.html。
 * 用 stroke 构建，所以 tint 时整条线跟随颜色，与设计稿一致。
 */
object RhmIcons {

    private fun strokeIcon(name: String, vararg paths: String): ImageVector {
        val builder = ImageVector.Builder(
            name = name,
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f
        )
        paths.forEach { d ->
            builder.addPath(
                pathData = PathParser().parsePathString(d).toNodes(),
                fill = null,
                stroke = SolidColor(Color.Black),
                strokeLineWidth = 1.6f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round
            )
        }
        return builder.build()
    }

    val Sidebar = strokeIcon(
        "Sidebar",
        "M5.5 4.6h13a2.3 2.3 0 0 1 2.3 2.3v10.2a2.3 2.3 0 0 1-2.3 2.3h-13a2.3 2.3 0 0 1-2.3-2.3V6.9a2.3 2.3 0 0 1 2.3-2.3Z",
        "M9.4 4.6v14.8"
    )

    val Folder = strokeIcon(
        "Folder",
        "M3.5 7.8V6.3a1.6 1.6 0 0 1 1.6-1.6h4.3l2.1 2.6h7.4a1.6 1.6 0 0 1 1.6 1.6v8.8a1.6 1.6 0 0 1-1.6 1.6H5.1a1.6 1.6 0 0 1-1.6-1.6V7.8Z"
    )

    val Edit = strokeIcon(
        "Edit",
        "M13.6 5.2H6.4a1.9 1.9 0 0 0-1.9 1.9v10.5a1.9 1.9 0 0 0 1.9 1.9h10.5a1.9 1.9 0 0 0 1.9-1.9v-7.2",
        "M17.3 3.6a2 2 0 0 1 2.9 2.9l-8.1 8.1-3.7 1 1-3.7 7.9-8.3Z"
    )

    val Chat = strokeIcon(
        "Chat",
        "M12 20.3c4.8 0 8.7-3.5 8.7-7.9S16.8 4.5 12 4.5 3.3 8 3.3 12.4c0 1.6.6 3.1 1.6 4.4l-.9 3.5 3.9-1c1.2.6 2.7 1 4.1 1Z"
    )

    val Terminal = strokeIcon(
        "Terminal",
        "m5.5 7.2 4.6 4.8-4.6 4.8M12.7 16.8H19"
    )

    val Sun = strokeIcon(
        "Sun",
        "M12 8a4 4 0 1 0 0 8 4 4 0 0 0 0-8Z",
        "M12 2.8v2.4M12 18.8v2.4M4.3 4.3l1.7 1.7M18 18l1.7 1.7M2.8 12h2.4M18.8 12h2.4M4.3 19.7 6 18M18 6l1.7-1.7"
    )

    val Moon = strokeIcon(
        "Moon",
        "M20.2 13.6A8.6 8.6 0 1 1 10.4 3.8a7.1 7.1 0 0 0 9.8 9.8Z"
    )

    val Monitor = strokeIcon(
        "Monitor",
        "M5.5 4.6h13a2 2 0 0 1 2 2v8.4a2 2 0 0 1-2 2h-13a2 2 0 0 1-2-2V6.6a2 2 0 0 1 2-2Z",
        "M12 17v2.6M8.6 19.6h6.8"
    )

    val Settings = strokeIcon(
        "Settings",
        "M12.22 2h-.44a2 2 0 0 0-2 2v.18a2 2 0 0 1-1 1.73l-.43.25a2 2 0 0 1-2 0l-.15-.08a2 2 0 0 0-2.73.73l-.22.38a2 2 0 0 0 .73 2.73l.15.1a2 2 0 0 1 1 1.72v.51a2 2 0 0 1-1 1.74l-.15.09a2 2 0 0 0-.73 2.73l.22.38a2 2 0 0 0 2.73.73l.15-.08a2 2 0 0 1 2 0l.43.25a2 2 0 0 1 1 1.73V20a2 2 0 0 0 2 2h.44a2 2 0 0 0 2-2v-.18a2 2 0 0 1 1-1.73l.43-.25a2 2 0 0 1 2 0l.15.08a2 2 0 0 0 2.73-.73l.22-.39a2 2 0 0 0-.73-2.73l-.15-.08a2 2 0 0 1-1-1.74v-.5a2 2 0 0 1 1-1.74l.15-.09a2 2 0 0 0 .73-2.73l-.22-.38a2 2 0 0 0-2.73-.73l-.15.08a2 2 0 0 1-2 0l-.43-.25a2 2 0 0 1-1-1.73V4a2 2 0 0 0-2-2z",
        "M12 9a3 3 0 1 0 0 6 3 3 0 0 0 0-6Z"
    )

    val Plus = strokeIcon(
        "Plus",
        "M12 5.5v13M5.5 12h13"
    )

    val Send = strokeIcon(
        "Send",
        "M12 19V6.2M6.4 11.8 12 6.2l5.6 5.6"
    )

    val Copy = strokeIcon(
        "Copy",
        "M9.8 8.8h8.4a1.8 1.8 0 0 1 1.8 1.8v9.6a1.8 1.8 0 0 1-1.8 1.8H9.8a1.8 1.8 0 0 1-1.8-1.8v-9.6a1.8 1.8 0 0 1 1.8-1.8Z",
        "M5.2 15.4h-.4a1.9 1.9 0 0 1-1.9-1.9V5.7a1.9 1.9 0 0 1 1.9-1.9h7.9a1.9 1.9 0 0 1 1.9 1.9v.5"
    )

    val Search = strokeIcon(
        "Search",
        "M11 4.5a6.5 6.5 0 1 0 0 13 6.5 6.5 0 0 0 0-13Z",
        "m16 16 4.5 4.5"
    )

    val Pin = strokeIcon(
        "Pin",
        "M12 17v5M9 10.76a2 2 0 0 1-1.11 1.79l-1.78.9A2 2 0 0 0 5 15.24V16a1 1 0 0 0 1 1h12a1 1 0 0 0 1-1v-.76a2 2 0 0 0-1.11-1.79l-1.78-.9A2 2 0 0 1 15 10.76V7a1 1 0 0 1 1-1 2 2 0 0 0 0-4H8a2 2 0 0 0 0 4 1 1 0 0 1 1 1z"
    )

    val Trash = strokeIcon(
        "Trash",
        "M4.5 6.6h15M9.6 6.6V4h4.8v2.6M6.6 6.6l.9 13.4h9l.9-13.4M10.4 10.4v6.2M13.6 10.4v6.2"
    )

    val Download = strokeIcon(
        "Download",
        "M12 4.6V16M6.8 10.8 12 16l5.2-5.2M4.5 15.2v4.2h15v-4.2"
    )

    val Stop = strokeIcon(
        "Stop",
        "M9.2 8.4h5.6a.8.8 0 0 1 .8.8v5.6a.8.8 0 0 1-.8.8H9.2a.8.8 0 0 1-.8-.8V9.2a.8.8 0 0 1 .8-.8Z"
    )

    val Paperclip = strokeIcon(
        "Paperclip",
        "M15.6 6.4v9.8a3.6 3.6 0 0 1-7.2 0V6.6a2.4 2.4 0 0 1 4.8 0v9.2a1.2 1.2 0 0 1-2.4 0V7.4"
    )

    val Close = strokeIcon(
        "Close",
        "M6.5 6.5l11 11M17.5 6.5l-11 11"
    )
}
