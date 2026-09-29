package com.siams.kern_prototype.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.AddAPhoto
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.siams.kern_prototype.R
import com.siams.kern_prototype.data.db.relation.TableWithLayers
import com.siams.kern_prototype.domain.model.LampType
import com.siams.kern_prototype.ui.theme.Body3Bold
import com.siams.kern_prototype.ui.theme.Body4Bold
import com.siams.kern_prototype.ui.theme.Gray40
import com.siams.kern_prototype.ui.theme.Ink
import com.siams.kern_prototype.ui.theme.KernTokens
import com.siams.kern_prototype.ui.theme.UvYellow
import com.siams.kern_prototype.viewmodel.TablesViewModel
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TablesScreen(
    onBack: () -> Unit,
    onOpenTable: (String) -> Unit,
    onAddCapture: () -> Unit,
    onEditPassport: () -> Unit,
    vm: TablesViewModel = hiltViewModel(),
) {
    val drilling by vm.drilling.collectAsStateWithLifecycle()
    val tables by vm.tables.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(drilling?.title ?: stringResource(R.string.tables_title_fallback)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.common_back))
                    }
                },
                actions = {
                    IconButton(onClick = onEditPassport) {
                        Icon(Icons.Outlined.Edit, contentDescription = stringResource(R.string.tables_passport_cd))
                    }
                },
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = onAddCapture,
                icon = { Icon(Icons.Outlined.AddAPhoto, contentDescription = null) },
                text = { Text(stringResource(R.string.tables_capture)) },
            )
        },
    ) { padding ->
        if (tables.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                Text(stringResource(R.string.tables_empty), color = Gray40, style = Body3Bold)
            }
        } else {
            LazyVerticalGrid(
                columns = GridCells.Fixed(2),
                modifier = Modifier.fillMaxSize().padding(padding),
                contentPadding = PaddingValues(KernTokens.spaceLg),
                horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(KernTokens.spaceMd),
                verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(KernTokens.spaceMd),
            ) {
                items(tables, key = { it.table.id }) { item ->
                    TableCard(item = item, onClick = { onOpenTable(item.table.id) })
                }
            }
        }
    }
}

@Composable
private fun TableCard(item: TableWithLayers, onClick: () -> Unit) {
    val daylight = item.layers.firstOrNull { it.lampType == LampType.DAYLIGHT }
    val hasUf = item.layers.any { it.lampType == LampType.UF }

    Card(
        onClick = onClick,
        shape = KernTokens.radiusCard,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
    ) {
        Box(Modifier.fillMaxWidth().aspectRatio(1f)) {
            if (daylight != null) {
                AsyncImage(
                    model = File(daylight.thumbPath ?: daylight.filePath),
                    contentDescription = stringResource(R.string.table_number, item.table.number),
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                Box(Modifier.fillMaxSize().background(Ink.copy(alpha = 0.08f)))
            }
            // Номер стола на белой плашке (§14.5).
            Text(
                stringResource(R.string.table_number, item.table.number),
                style = Body4Bold,
                color = Ink,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(KernTokens.spaceSm)
                    .background(Color.White, KernTokens.radiusChip)
                    .padding(horizontal = KernTokens.spaceSm, vertical = KernTokens.spaceXs),
            )
            if (hasUf) {
                Text(
                    stringResource(R.string.common_uf),
                    style = Body4Bold,
                    color = Ink,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(KernTokens.spaceSm)
                        .background(UvYellow, KernTokens.radiusChip)
                        .padding(horizontal = KernTokens.spaceSm, vertical = KernTokens.spaceXs),
                )
            }
        }
    }
}
