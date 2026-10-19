package com.retrohome.launcher.ui.phone

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.retrohome.launcher.data.AppInfo
import com.retrohome.launcher.data.HomeItem
import com.retrohome.launcher.i18n.LocalStrings
import com.retrohome.launcher.ui.AppIcon
import com.retrohome.launcher.ui.Palette

fun filterApps(apps: List<AppInfo>, query: String): List<AppInfo> =
    com.retrohome.launcher.ui.filterByLabel(apps, query) { it.label }

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun AppDrawer(
    apps: List<AppInfo>,
    columns: Int,
    onClose: () -> Unit,
    onSettings: () -> Unit,
    onLaunch: (String) -> Unit,
    onLongPress: (AppInfo) -> Unit,
) {
    val s = LocalStrings.current
    var query by remember { mutableStateOf("") }
    val shown = remember(apps, query) { filterApps(apps, query) }
    Column(
        Modifier
            .fillMaxSize()
            .background(Palette.bgBottom.copy(alpha = 0.94f))
            .statusBarsPadding()
            .navigationBarsPadding()
    ) {
        // Шапка: свайп вниз закрывает список
        Column(
            Modifier
                .fillMaxWidth()
                .pointerInput(Unit) {
                    var acc = 0f
                    detectVerticalDragGestures(
                        onDragStart = { acc = 0f },
                        onVerticalDrag = { _, dy -> acc += dy },
                        onDragEnd = { if (acc > 120f) onClose() },
                    )
                }
                .padding(horizontal = 16.dp, vertical = 10.dp)
        ) {
            Box(Modifier.fillMaxWidth()) {
                Box(
                    Modifier
                        .align(Alignment.Center)
                        .clickable(remember { MutableInteractionSource() }, null, onClick = onClose)
                        .padding(4.dp)
                ) { Text("﹀", color = Palette.dim, fontSize = 16.sp) }
                Text(
                    "⚙  " + s.settingsShort,
                    color = Palette.text,
                    fontSize = 14.sp,
                    modifier = Modifier
                        .align(Alignment.CenterEnd)
                        .clip(RoundedCornerShape(50))
                        .clickable(onClick = onSettings)
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                )
            }
            Spacer(Modifier.height(6.dp))
            Box(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(50))
                    .background(Palette.surface)
                    .padding(horizontal = 18.dp, vertical = 12.dp)
            ) {
                if (query.isEmpty()) Text(s.searchApps, color = Palette.dim, fontSize = 15.sp)
                BasicTextField(
                    value = query,
                    onValueChange = { query = it },
                    singleLine = true,
                    textStyle = TextStyle(color = Palette.text, fontSize = 15.sp),
                    cursorBrush = SolidColor(Palette.accent),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
        if (shown.isEmpty()) {
            Text(
                s.noResults,
                color = Palette.dim,
                modifier = Modifier.fillMaxWidth().padding(32.dp),
                textAlign = TextAlign.Center,
            )
        }
        LazyVerticalGrid(
            columns = GridCells.Fixed(columns),
            modifier = Modifier.fillMaxSize().padding(horizontal = 8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            items(shown, key = { it.key }) { app ->
                Column(
                    Modifier
                        .clip(RoundedCornerShape(14.dp))
                        .combinedClickable(onClick = { onLaunch(app.key) }, onLongClick = { onLongPress(app) })
                        .padding(vertical = 8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    AppIcon(app.key, 50.dp)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        app.label,
                        color = Palette.text,
                        fontSize = 12.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(horizontal = 2.dp),
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun FolderPanel(
    folder: HomeItem,
    apps: Map<String, AppInfo>,
    onClose: () -> Unit,
    onLaunch: (String) -> Unit,
    onRename: (String) -> Unit,
    onLongPress: (String) -> Unit,
) {
    val s = LocalStrings.current
    var name by remember(folder.id) { mutableStateOf(folder.folderName) }
    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.55f))
            .clickable(remember { MutableInteractionSource() }, null) {
                if (name != folder.folderName) onRename(name)
                onClose()
            },
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .clip(RoundedCornerShape(28.dp))
                .background(Palette.surface)
                .clickable(remember { MutableInteractionSource() }, null) {}
                .padding(16.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                BasicTextField(
                    value = name,
                    onValueChange = { name = it.take(40) },
                    singleLine = true,
                    textStyle = TextStyle(color = Palette.text, fontSize = 20.sp, fontWeight = FontWeight.Bold),
                    cursorBrush = SolidColor(Palette.accent),
                    modifier = Modifier.weight(1f),
                )
                Text(
                    s.ok,
                    color = Palette.accent,
                    fontSize = 15.sp,
                    modifier = Modifier
                        .clip(RoundedCornerShape(50))
                        .clickable {
                            if (name != folder.folderName) onRename(name)
                            onClose()
                        }
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                )
            }
            Spacer(Modifier.height(12.dp))
            val rows = folder.folderApps.chunked(4)
            for (row in rows) {
                Row(Modifier.fillMaxWidth()) {
                    for (key in row) {
                        Column(
                            Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(14.dp))
                                .combinedClickable(onClick = { onLaunch(key) }, onLongClick = { onLongPress(key) })
                                .padding(vertical = 8.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            AppIcon(key, 52.dp)
                            Spacer(Modifier.height(4.dp))
                            Text(
                                apps[key]?.label ?: "",
                                color = Palette.text,
                                fontSize = 12.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                    repeat(4 - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
    }
}
