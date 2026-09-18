package com.ogesture.data

enum class GestureAction { NONE, BACK, HOME, RECENTS }

enum class ZoneId { BOTTOM, LEFT_EDGE, RIGHT_EDGE }

enum class SwipeDirection { UP, RIGHT, LEFT }

data class ZoneConfig(
    val id: ZoneId,
    val action: GestureAction,
    val longAction: GestureAction,
    val lengthPercent: Int,
    val thicknessDp: Int,
) {
    val swipeDirection: SwipeDirection get() = when (id) {
        ZoneId.BOTTOM -> SwipeDirection.UP
        ZoneId.LEFT_EDGE -> SwipeDirection.RIGHT
        ZoneId.RIGHT_EDGE -> SwipeDirection.LEFT
    }
}

