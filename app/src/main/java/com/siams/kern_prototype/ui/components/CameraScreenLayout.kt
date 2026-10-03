package com.siams.kern_prototype.ui.components

import android.util.Size
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import com.siams.kern_prototype.ui.theme.KernTokens

/**
 * FIXES §8: каркас экранов камеры (съёмка, редактор профилей).
 *
 *  - Две панели ([useTwoPane]): кадр слева во всю высоту, справа прокручиваемая панель [panelWidth]
 *    с [panel]. Только если кадру остаётся не меньше [MIN_PREVIEW_WIDTH_DP] — иначе одна колонка.
 *  - Одна колонка: кадр на всю область, поверх — [portraitOverlay] (верхний/нижний блоки экрана).
 *  - [preview] — переносимое превью ([rememberMovableCameraPreview]): `TextureView` не пересоздаётся
 *    при смене компоновки. [previewOverlay] — плашки поверх кадра в обеих компоновках.
 */
@Composable
fun CameraScreenLayout(
    previewSize: Size?,
    preview: @Composable (Size?) -> Unit,
    panelWidth: Dp,
    previewOverlay: @Composable BoxScope.() -> Unit,
    portraitOverlay: @Composable BoxScope.() -> Unit,
    panelArrangement: Arrangement.Vertical,
    panelAlignment: Alignment.Horizontal,
    panelPadding: Dp,
    panel: @Composable ColumnScope.() -> Unit,
    modifier: Modifier = Modifier,
) {
    BoxWithConstraints(modifier.fillMaxSize()) {
        if (useTwoPane(maxWidth.value, maxHeight.value, panelWidth.value)) {
            Row(Modifier.fillMaxSize()) {
                Box(Modifier.weight(1f).fillMaxHeight()) {
                    preview(previewSize)
                    previewOverlay()
                }
                Column(
                    modifier = Modifier
                        .width(panelWidth)
                        .fillMaxHeight()
                        .systemBarsPadding()
                        .verticalScroll(rememberScrollState())
                        .padding(panelPadding),
                    verticalArrangement = panelArrangement,
                    horizontalAlignment = panelAlignment,
                    content = panel,
                )
            }
        } else {
            Box(Modifier.fillMaxSize()) {
                preview(previewSize)
                previewOverlay()
                portraitOverlay()
            }
        }
    }
}

/** Минимальная ширина кадра в двух панелях; меньше — одна колонка (кадр не должен исчезать). */
const val MIN_PREVIEW_WIDTH_DP = 240f

/**
 * FIXES §8: две панели — в альбомной ориентации или на широком экране (≥ [TWO_PANE_MIN_WIDTH_DP] dp),
 * и только если после боковой панели [panelWidthDp] кадру остаётся ≥ [MIN_PREVIEW_WIDTH_DP] dp.
 */
fun useTwoPane(widthDp: Float, heightDp: Float, panelWidthDp: Float = KernTokens.sidePanelWidth.value): Boolean =
    (widthDp > heightDp || widthDp >= TWO_PANE_MIN_WIDTH_DP) && widthDp - panelWidthDp >= MIN_PREVIEW_WIDTH_DP

const val TWO_PANE_MIN_WIDTH_DP = 600f
