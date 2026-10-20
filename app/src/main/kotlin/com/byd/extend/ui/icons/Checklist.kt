/*
 * Copyright 2024 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */

package com.byd.extend.ui.icons

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.materialIcon
import androidx.compose.material.icons.materialPath
import androidx.compose.ui.graphics.vector.ImageVector

public val Icons.Outlined.Checklist: ImageVector
    get() {
        if (_checklist != null) return _checklist!!
        _checklist = materialIcon(name = "Outlined.Checklist") {
            materialPath {
                moveTo(3.5f, 5.5f)
                lineTo(5.0f, 4.0f)
                lineTo(7.0f, 6.0f)
                lineTo(10.0f, 3.0f)
                lineTo(11.5f, 4.5f)
                lineTo(7.0f, 9.0f)
                close()
                moveTo(14.0f, 4.0f)
                horizontalLineTo(21.0f)
                verticalLineTo(5.5f)
                horizontalLineTo(14.0f)
                close()
                moveTo(3.5f, 12.5f)
                lineTo(5.0f, 11.0f)
                lineTo(7.0f, 13.0f)
                lineTo(10.0f, 10.0f)
                lineTo(11.5f, 11.5f)
                lineTo(7.0f, 16.0f)
                close()
                moveTo(14.0f, 11.0f)
                horizontalLineTo(21.0f)
                verticalLineTo(12.5f)
                horizontalLineTo(14.0f)
                close()
                moveTo(4.0f, 18.0f)
                horizontalLineTo(10.0f)
                verticalLineTo(19.5f)
                horizontalLineTo(4.0f)
                close()
                moveTo(4.0f, 19.5f)
                horizontalLineTo(5.5f)
                verticalLineTo(23.0f)
                horizontalLineTo(4.0f)
                close()
                moveTo(8.5f, 19.5f)
                horizontalLineTo(10.0f)
                verticalLineTo(23.0f)
                horizontalLineTo(8.5f)
                close()
                moveTo(5.5f, 21.5f)
                horizontalLineTo(10.0f)
                verticalLineTo(23.0f)
                horizontalLineTo(5.5f)
                close()
                moveTo(14.0f, 20.0f)
                horizontalLineTo(21.0f)
                verticalLineTo(21.5f)
                horizontalLineTo(14.0f)
                close()
            }
        }
        return _checklist!!
    }

private var _checklist: ImageVector? = null
