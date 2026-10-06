package com.example.viewer.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Animation
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ClearAll
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.FileOpen
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.ViewInAr
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.ui.theme.StatusPartial
import com.example.ui.theme.StatusSupported
import com.example.ui.theme.StatusUnsupported
import com.example.viewer.manager.FileManager
import com.example.viewer.manager.HomeTab
import com.example.viewer.manager.ViewerUiState
import com.example.viewer.model.LodLevel
import com.example.viewer.model.RecentItem
import com.example.viewer.model.SupportStatus
import com.example.viewer.model.ViewerSettings
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun HomeTabsScreen(
    uiState: ViewerUiState,
    fileManager: FileManager,
    onSelectTab: (HomeTab) -> Unit,
    onPickFileClick: () -> Unit,
    onPickFolderClick: () -> Unit,
    onOpenSampleAsset: (String) -> Unit,
    onOpenRecentItem: (RecentItem) -> Unit,
    onClearRecents: () -> Unit,
    onUpdateSettings: (ViewerSettings) -> Unit
) {
    Scaffold(
        bottomBar = {
            NavigationBar(
                containerColor = MaterialTheme.colorScheme.surface,
                tonalElevation = 8.dp
            ) {
                NavigationBarItem(
                    selected = uiState.selectedHomeTab == HomeTab.OPEN_FILE,
                    onClick = { onSelectTab(HomeTab.OPEN_FILE) },
                    icon = { Icon(Icons.Default.FileOpen, contentDescription = "Open File") },
                    label = { Text("OPEN FILE", style = MaterialTheme.typography.labelSmall) },
                    modifier = Modifier.testTag("nav_open_file")
                )
                NavigationBarItem(
                    selected = uiState.selectedHomeTab == HomeTab.OPEN_FOLDER,
                    onClick = { onSelectTab(HomeTab.OPEN_FOLDER) },
                    icon = { Icon(Icons.Default.FolderOpen, contentDescription = "Open Folder") },
                    label = { Text("OPEN FOLDER", style = MaterialTheme.typography.labelSmall) },
                    modifier = Modifier.testTag("nav_open_folder")
                )
                NavigationBarItem(
                    selected = uiState.selectedHomeTab == HomeTab.RECENT,
                    onClick = { onSelectTab(HomeTab.RECENT) },
                    icon = { Icon(Icons.Default.History, contentDescription = "Recent") },
                    label = { Text("RECENT", style = MaterialTheme.typography.labelSmall) },
                    modifier = Modifier.testTag("nav_recent")
                )
                NavigationBarItem(
                    selected = uiState.selectedHomeTab == HomeTab.SETTINGS,
                    onClick = { onSelectTab(HomeTab.SETTINGS) },
                    icon = { Icon(Icons.Default.Settings, contentDescription = "Settings") },
                    label = { Text("SETTINGS", style = MaterialTheme.typography.labelSmall) },
                    modifier = Modifier.testTag("nav_settings")
                )
            }
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            StudioTopHeader()

            when (uiState.selectedHomeTab) {
                HomeTab.OPEN_FILE -> OpenFileTabContent(
                    onPickFileClick = onPickFileClick,
                    onOpenSampleAsset = onOpenSampleAsset
                )
                HomeTab.OPEN_FOLDER -> OpenFolderTabContent(
                    onPickFolderClick = onPickFolderClick,
                    onOpenSamplePackage = { onOpenSampleAsset("starter_pkg") }
                )
                HomeTab.RECENT -> RecentTabContent(
                    recentItems = uiState.recentItems,
                    fileManager = fileManager,
                    onOpenRecentItem = onOpenRecentItem,
                    onClearRecents = onClearRecents,
                    onOpenSampleAsset = onOpenSampleAsset
                )
                HomeTab.SETTINGS -> SettingsTabContent(
                    settings = uiState.settings,
                    onUpdateSettings = onUpdateSettings
                )
            }
        }
    }
}

@Composable
private fun StudioTopHeader() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                Brush.horizontalGradient(
                    colors = listOf(
                        MaterialTheme.colorScheme.surface,
                        MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f)
                    )
                )
            )
            .padding(horizontal = 20.dp, vertical = 16.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.16f))
                        .border(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.5f), RoundedCornerShape(12.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.ViewInAr,
                        contentDescription = "Unity 3D Viewer Icon",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(26.dp)
                    )
                }
                Spacer(modifier = Modifier.width(12.dp))
                Column {
                    Text(
                        text = "Unity 3D Viewer",
                        style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = "OPEN → IMPORT → PARSE → VIEW 3D",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .padding(horizontal = 10.dp, vertical = 5.dp)
            ) {
                Text(
                    text = "GLES 2.0",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun OpenFileTabContent(
    onPickFileClick: () -> Unit,
    onOpenSampleAsset: (String) -> Unit
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                shape = RoundedCornerShape(18.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .border(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.35f), RoundedCornerShape(18.dp))
            ) {
                Column(modifier = Modifier.padding(20.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.FileOpen,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Text(
                            text = "Pilih File Aset 3D / UnityPackage",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "Buka dan inspeksi file 3D atau .unitypackage secara lokal melalui Android Storage Access Framework tanpa memerlukan PC.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(14.dp))
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        listOf(".unitypackage", ".glb", ".gltf", ".obj", ".fbx", ".dae", ".3ds", ".zip", ".blend").forEach { ext ->
                            FormatPill(ext)
                        }
                    }
                    Spacer(modifier = Modifier.height(16.dp))
                    Button(
                        onClick = onPickFileClick,
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(50.dp)
                            .testTag("open_file_picker_button")
                    ) {
                        Icon(Icons.Default.FileOpen, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("PILIH FILE DARI PERANGKAT (SAF)", fontWeight = FontWeight.Bold)
                    }
                }
            }
        }

        item {
            Text(
                text = "CONTOH ASET & UNITYPACKAGE SIAP UJI (LOKAL)",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary
            )
        }

        item {
            SampleAssetCard(
                title = "CyberStation_StarterKit.unitypackage",
                subtitle = "Ekstraksi .unitypackage (.tar.gz) lokal: berisi model GLB, OBJ, Texture TGA, Material .mat, Scene .unity & .prefab",
                badge = ".UNITYPACKAGE",
                badgeColor = StatusSupported,
                icon = Icons.Default.Archive,
                testTag = "sample_unitypackage_card",
                onClick = { onOpenSampleAsset("starter_pkg") }
            )
        }

        item {
            SampleAssetCard(
                title = "UnityRover_Animated.glb",
                subtitle = "Model 3D Binary glTF 2.0 dengan PBR Material & 4 Animation Clip (Idle, Walk, Run, Drive)",
                badge = "GLB + ANIMATION",
                badgeColor = StatusSupported,
                icon = Icons.Default.Animation,
                testTag = "sample_glb_card",
                onClick = { onOpenSampleAsset("rover_glb") }
            )
        }

        item {
            SampleAssetCard(
                title = "MechaDrone_PBR.obj",
                subtitle = "Mesh Wavefront OBJ lengkap dengan file .mtl dan tekstur Truevision .tga (64x64)",
                badge = "OBJ + MTL + TGA",
                badgeColor = StatusSupported,
                icon = Icons.Default.ViewInAr,
                testTag = "sample_obj_card",
                onClick = { onOpenSampleAsset("drone_obj") }
            )
        }

        item {
            SampleAssetCard(
                title = "SciFiReactor_Core.fbx",
                subtitle = "Model Autodesk FBX 7.4 (Toroidal Reactor Core) dengan Phong/PBR Material & AnimStack",
                badge = "FBX MESH",
                badgeColor = StatusSupported,
                icon = Icons.Default.Description,
                testTag = "sample_fbx_card",
                onClick = { onOpenSampleAsset("reactor_fbx") }
            )
        }

        item {
            FormatSupportTransparencyCard()
        }
    }
}

@Composable
private fun OpenFolderTabContent(
    onPickFolderClick: () -> Unit,
    onOpenSamplePackage: () -> Unit
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                shape = RoundedCornerShape(18.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .border(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.35f), RoundedCornerShape(18.dp))
            ) {
                Column(modifier = Modifier.padding(20.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.CreateNewFolder,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Text(
                            text = "Unity Project Folder Scanner",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "Pilih direktori root Unity Project Anda melalui Android Storage Access Framework. Aplikasi akan mendeteksi struktur folder:",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FormatPill("Assets/")
                        FormatPill("ProjectSettings/")
                        FormatPill("Packages/")
                    }
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = "Scanner memprioritaskan FBX, OBJ, GLTF, GLB, textures, dan materials, serta menampilkan status SUPPORTED, PARTIALLY SUPPORTED, dan NOT SUPPORTED secara jujur.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Button(
                        onClick = onPickFolderClick,
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(50.dp)
                            .testTag("open_folder_picker_button")
                    ) {
                        Icon(Icons.Default.FolderOpen, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("PILIH FOLDER PROJECT (SAF)", fontWeight = FontWeight.Bold)
                    }
                    Spacer(modifier = Modifier.height(10.dp))
                    OutlinedButton(
                        onClick = onOpenSamplePackage,
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("inspect_sample_project_button")
                    ) {
                        Icon(Icons.Default.Archive, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("INSPEKSI STRUKTUR CONTOH ASSETS/")
                    }
                }
            }
        }

        item {
            FormatSupportTransparencyCard()
        }
    }
}

@Composable
private fun RecentTabContent(
    recentItems: List<RecentItem>,
    fileManager: FileManager,
    onOpenRecentItem: (RecentItem) -> Unit,
    onClearRecents: () -> Unit,
    onOpenSampleAsset: (String) -> Unit
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "RIWAYAT FILE & PACKAGE (${recentItems.size})",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary
                )
                if (recentItems.isNotEmpty()) {
                    IconButton(
                        onClick = onClearRecents,
                        modifier = Modifier.testTag("clear_recents_button")
                    ) {
                        Icon(Icons.Default.ClearAll, contentDescription = "Bersihkan Riwayat")
                    }
                }
            }
        }

        if (recentItems.isEmpty()) {
            item {
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Icon(
                            imageVector = Icons.Default.History,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(40.dp)
                        )
                        Spacer(modifier = Modifier.height(10.dp))
                        Text(
                            text = "Belum ada aset 3D yang dibuka",
                            style = MaterialTheme.typography.titleMedium
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "Pilih file 3D dari perangkat atau coba aset sample Unity Rover di bawah ini.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(14.dp))
                        OutlinedButton(onClick = { onOpenSampleAsset("rover_glb") }) {
                            Text("Buka Sample UnityRover.glb")
                        }
                    }
                }
            }
        } else {
            items(recentItems, key = { it.id }) { item ->
                val dateStr = SimpleDateFormat("dd MMM HH:mm", Locale.getDefault()).format(Date(item.timestampMillis))
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onOpenRecentItem(item) }
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = item.name,
                                style = MaterialTheme.typography.titleMedium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = "${item.format} • ${fileManager.formatFileSize(item.sizeBytes)} • $dateStr",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        FormatPill(if (item.isSample) "SAMPLE" else "OPEN")
                    }
                }
            }
        }
    }
}

@Composable
private fun SettingsTabContent(
    settings: ViewerSettings,
    onUpdateSettings: (ViewerSettings) -> Unit
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item {
            Text(
                text = "KONFIGURASI VIEWER & PERFORMA",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary
            )
        }

        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    SettingToggleRow(
                        title = "Tema Studio Gelap (Dark Mode)",
                        subtitle = "Kontras optimal untuk inspeksi mesh, wireframe, dan material PBR",
                        checked = settings.darkTheme,
                        onCheckedChange = { onUpdateSettings(settings.copy(darkTheme = it)) }
                    )
                    HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.4f))
                    SettingToggleRow(
                        title = "Tampilkan Grid 3D secara Default",
                        subtitle = "Menampilkan grid XZ dan sumbu koordinat XYZ saat model dibuka",
                        checked = settings.showGridByDefault,
                        onCheckedChange = { onUpdateSettings(settings.copy(showGridByDefault = it)) }
                    )
                    HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.4f))
                    SettingToggleRow(
                        title = "Aktifkan Pencahayaan PBR Default",
                        subtitle = "Directional + Hemisphere Ambient + Metallic/Roughness shading",
                        checked = settings.enableLightingByDefault,
                        onCheckedChange = { onUpdateSettings(settings.copy(enableLightingByDefault = it)) }
                    )
                }
            }
        }

        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Memory, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Manajemen Memori & Optimasi Tekstur",
                            style = MaterialTheme.typography.titleMedium
                        )
                    }
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = "Batas resolusi tekstur maksimum untuk mencegah OutOfMemory pada perangkat HP:",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(512, 1024, 2048).forEach { res ->
                            FilterChip(
                                selected = settings.maxTextureResolution == res,
                                onClick = { onUpdateSettings(settings.copy(maxTextureResolution = res)) },
                                label = { Text("${res}px") }
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = "Default Level of Detail (LOD):",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        LodLevel.entries.forEach { lod ->
                            FilterChip(
                                selected = settings.defaultLod == lod,
                                onClick = { onUpdateSettings(settings.copy(defaultLod = lod)) },
                                label = { Text(lod.label) }
                            )
                        }
                    }
                }
            }
        }

        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Info, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Batasan & Arsitektur Aplikasi", style = MaterialTheme.typography.titleMedium)
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "• Alur kerja: OPEN → IMPORT → CONVERT/PARSE → VIEW 3D.\n" +
                            "• Aplikasi ini adalah 3D Asset Viewer/Importer lokal dan TIDAK mengklaim dapat mengeksekusi file .exe, game executable Unity, atau menggantikan Unity Editor secara penuh.\n" +
                            "• Semua pemrosesan file dilakukan 100% secara lokal melalui Android Storage Access Framework tanpa mengunggah data ke server.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@Composable
private fun SettingToggleRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = title, style = MaterialTheme.typography.titleMedium)
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Spacer(modifier = Modifier.width(12.dp))
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun SampleAssetCard(
    title: String,
    subtitle: String,
    badge: String,
    badgeColor: Color,
    icon: ImageVector,
    testTag: String,
    onClick: () -> Unit
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        shape = RoundedCornerShape(14.dp),
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .testTag(testTag)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(CircleShape)
                    .background(badgeColor.copy(alpha = 0.16f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(icon, contentDescription = null, tint = badgeColor)
            }
            Spacer(modifier = Modifier.width(14.dp))
            Column(modifier = Modifier.weight(1f)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(badgeColor.copy(alpha = 0.2f))
                            .padding(horizontal = 8.dp, vertical = 3.dp)
                    ) {
                        Text(
                            text = badge,
                            style = MaterialTheme.typography.labelSmall,
                            color = badgeColor
                        )
                    }
                }
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun FormatSupportTransparencyCard() {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.5f), RoundedCornerShape(16.dp))
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(
                text = "STATUS DUKUNGAN FORMAT REALISTIS",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary
            )
            StatusGuideRow(
                status = SupportStatus.SUPPORTED,
                formats = ".glb, .gltf, .obj, .fbx, .dae, .3ds, .unitypackage, .zip, .png, .jpg, .webp, .tga",
                desc = "Mesh 3D, UV, Tekstur (termasuk dekoder TGA), Animasi, dan ekstraksi archive lokal."
            )
            StatusGuideRow(
                status = SupportStatus.PARTIALLY_SUPPORTED,
                formats = ".mat (Unity Material), .blend",
                desc = "Aproksimasi material PBR (_Color, _Metallic, _Glossiness, _EmissionColor) & inspeksi header."
            )
            StatusGuideRow(
                status = SupportStatus.NOT_SUPPORTED,
                formats = ".unity, .prefab, .asset, .cs, .exe",
                desc = "Membutuhkan runtime/serialization Unity Editor; ditandai jelas tanpa klaim palsu."
            )
        }
    }
}

@Composable
private fun StatusGuideRow(status: SupportStatus, formats: String, desc: String) {
    val (color, icon) = when (status) {
        SupportStatus.SUPPORTED -> StatusSupported to Icons.Default.CheckCircle
        SupportStatus.PARTIALLY_SUPPORTED -> StatusPartial to Icons.Default.WarningAmber
        SupportStatus.NOT_SUPPORTED -> StatusUnsupported to Icons.Default.ErrorOutline
    }
    Row(verticalAlignment = Alignment.Top) {
        Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(18.dp))
        Spacer(modifier = Modifier.width(8.dp))
        Column {
            Text(
                text = "${status.label}: $formats",
                style = MaterialTheme.typography.labelMedium,
                color = color
            )
            Text(
                text = desc,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun FormatPill(text: String) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.14f))
            .border(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.35f), RoundedCornerShape(8.dp))
            .padding(horizontal = 9.dp, vertical = 4.dp)
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.primary
        )
    }
}
