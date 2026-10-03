package com.siams.kern_prototype.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import com.siams.kern_prototype.ui.theme.KernTokens

/**
 * FIXES §8: содержимое списков и форм — не шире [max] и по центру (на планшете поля не растягиваются
 * на всю ширину). На телефоне — во всю ширину.
 */
fun Modifier.readableWidth(max: Dp = KernTokens.contentMaxWidth): Modifier =
    // Последний fillMaxWidth — занять всю ширину в пределах [max], независимо от содержимого.
    this.fillMaxWidth().wrapContentWidth(Alignment.CenterHorizontally).widthIn(max = max).fillMaxWidth()

/**
 * FIXES §8: основное содержимое (изображение) и панель [side] шириной [sideWidth].
 *  - широкое окно ([useTwoPane] с учётом [sideWidth]): основное слева во всю высоту, панель справа с прокруткой;
 *  - иначе — одна прокручиваемая колонка: основное (пропорции [mainAspect]) и под ним панель.
 */
@Composable
fun AdaptiveSplit(
    sideWidth: Dp,
    mainAspect: Float,
    main: @Composable (Modifier) -> Unit,
    side: @Composable ColumnScope.() -> Unit,
    modifier: Modifier = Modifier,
) {
    BoxWithConstraints(modifier.fillMaxSize()) {
        if (useTwoPane(maxWidth.value, maxHeight.value, sideWidth.value)) {
            Row(Modifier.fillMaxSize()) {
                Box(Modifier.weight(1f).fillMaxHeight().padding(KernTokens.spaceLg), contentAlignment = Alignment.Center) {
                    main(Modifier.fillMaxSize())
                }
                Column(
                    modifier = Modifier
                        .width(sideWidth)
                        .fillMaxHeight()
                        .verticalScroll(rememberScrollState())
                        .padding(KernTokens.spaceLg),
                    verticalArrangement = Arrangement.spacedBy(KernTokens.spaceMd),
                    content = side,
                )
            }
        } else {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(KernTokens.spaceLg),
                verticalArrangement = Arrangement.spacedBy(KernTokens.spaceMd),
            ) {
                main(Modifier.fillMaxWidth().aspectRatio(mainAspect))
                side()
            }
        }
    }
}
