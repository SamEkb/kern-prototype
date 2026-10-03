package com.siams.kern_prototype.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.siams.kern_prototype.ui.theme.KernTokens

/**
 * FIXES §8: сетка карточек столов — колонки, поля и промежутки в одном месте.
 * [KernTokens.tableCardMinWidth] подобран с учётом полей 2×[KernTokens.spaceLg] и промежутка
 * [KernTokens.spaceMd]: две колонки на телефоне шириной от 320 dp, больше — на планшете и в альбомной ориентации.
 */
@Composable
fun TablesGrid(modifier: Modifier = Modifier, content: LazyGridScope.() -> Unit) {
    LazyVerticalGrid(
        columns = GridCells.Adaptive(minSize = KernTokens.tableCardMinWidth),
        modifier = modifier,
        contentPadding = PaddingValues(KernTokens.spaceLg),
        horizontalArrangement = Arrangement.spacedBy(KernTokens.spaceMd),
        verticalArrangement = Arrangement.spacedBy(KernTokens.spaceMd),
        content = content,
    )
}
