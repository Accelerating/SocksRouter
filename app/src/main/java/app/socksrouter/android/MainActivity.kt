package app.socksrouter.android

import android.Manifest
import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.toBitmap
import app.socksrouter.android.config.AppPreferences
import app.socksrouter.android.config.RoutingMode
import app.socksrouter.android.config.SavedConfig
import app.socksrouter.android.config.ServerProfile
import app.socksrouter.android.diagnostics.DiagnosticLogEntry
import app.socksrouter.android.diagnostics.DiagnosticLevel
import app.socksrouter.android.diagnostics.DiagnosticLogStore
import app.socksrouter.android.vpn.HevTunnel
import app.socksrouter.android.vpn.PrototypeConfig
import app.socksrouter.android.vpn.SocksVpnService
import app.socksrouter.android.ui.theme.SocksRouterTheme
import app.socksrouter.android.dns.DnsFallbackPolicy
import app.socksrouter.android.dns.DnsSettings
import app.socksrouter.android.dns.BuiltInDohServices
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.UUID

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { SocksRouterTheme { RouterScreen(this) } }
    }
}

private data class AppRow(
    val label: String,
    val packageNames: List<String>,
    val isSystem: Boolean,
    val icon: Drawable?,
) {
    val isSharedUid: Boolean get() = packageNames.size > 1
}

@Composable
private fun RouterScreen(activity: Activity) {
    val preferences = remember { AppPreferences(activity) }
    val saved = remember { preferences.load() }
    var server by remember { mutableStateOf(saved.serverAddress) }
    var port by remember { mutableStateOf(saved.serverPort) }
    var routingMode by remember { mutableStateOf(saved.routingMode) }
    var status by remember { mutableStateOf(if (HevTunnel.isRunning()) "代理运行中" else "已停止") }
    var tunnelStats by remember { mutableStateOf(LongArray(4)) }
    var apps by remember { mutableStateOf(emptyList<AppRow>()) }
    var query by remember { mutableStateOf("") }
    var showSystem by remember { mutableStateOf(false) }
    var profiles by remember { mutableStateOf(saved.serverProfiles) }
    var activeProfileId by remember { mutableStateOf(saved.activeProfileId) }
    var showAddProfile by remember { mutableStateOf(false) }
    var showDnsSettings by remember { mutableStateOf(false) }
    var showDiagnostics by remember { mutableStateOf(false) }
    var dnsSettings by remember { mutableStateOf(saved.dnsSettings) }
    val selected = remember { mutableStateListOf<String>().apply { addAll(saved.selectedPackages) } }

    fun persist() {
        profiles = profiles.map {
            if (it.id == activeProfileId) it.copy(address = server.trim(), port = port.toIntOrNull() ?: it.port) else it
        }
        preferences.save(
            SavedConfig(
                serverAddress = server.trim(),
                serverPort = port,
                routingMode = routingMode,
                selectedPackages = selected.toSet(),
                serverProfiles = profiles,
                activeProfileId = activeProfileId,
                dnsSettings = dnsSettings,
            )
        )
    }

    val vpnPermission = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            startRouter(activity, server, port, selected.toSet(), routingMode, dnsSettings)
            status = "正在启动 VPN"
        } else status = "VPN 授权被拒绝"
    }
    val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }

    DisposableEffect(Unit) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                status = intent?.getStringExtra(SocksVpnService.EXTRA_STATE_MESSAGE) ?: status
            }
        }
        ContextCompat.registerReceiver(
            activity, receiver, IntentFilter(SocksVpnService.ACTION_STATE_CHANGED),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        onDispose { activity.unregisterReceiver(receiver) }
    }

    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= 33) notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        apps = withContext(Dispatchers.IO) {
            activity.packageManager.getInstalledApplications(PackageManager.GET_META_DATA)
                .asSequence()
                .filter { it.packageName != activity.packageName }
                .groupBy { it.uid }
                .values
                .mapNotNull { group ->
                    val routable = group.filter {
                        activity.packageManager.checkPermission(Manifest.permission.INTERNET, it.packageName) ==
                            PackageManager.PERMISSION_GRANTED
                    }
                    if (routable.isEmpty()) return@mapNotNull null
                    val packageNames = group.map { it.packageName }.sorted()
                    val labels = routable.map {
                        activity.packageManager.getApplicationLabel(it).toString()
                    }.distinct().sorted()
                    AppRow(
                        label = labels.joinToString("、"),
                        packageNames = packageNames,
                        isSystem = group.any { it.flags and ApplicationInfo.FLAG_SYSTEM != 0 },
                        icon = runCatching {
                            activity.packageManager.getApplicationIcon(routable.first().packageName)
                        }.getOrNull(),
                    )
                }
                .sortedBy { it.label.lowercase() }
                .toList()
        }
    }

    LaunchedEffect(Unit) {
        while (isActive) {
            tunnelStats = withContext(Dispatchers.IO) {
                if (HevTunnel.isRunning()) {
                    runCatching { HevTunnel.stats() }.getOrDefault(LongArray(4))
                } else {
                    LongArray(4)
                }
            }
            delay(1_000)
        }
    }

    val visibleApps = apps.filter {
            (showSystem || !it.isSystem) &&
            (query.isBlank() || it.label.contains(query, true) ||
                it.packageNames.any { packageName -> packageName.contains(query, true) })
    }
    val selectedMeaning = if (routingMode == RoutingMode.SELECTED_PROXY) "走 SOCKS5" else "走直连"
    val selectedGroupCount = apps.count { app -> app.packageNames.any { it in selected } }

    val requestStart = {
        persist()
        val config = PrototypeConfig(server.trim(), port.toIntOrNull() ?: 0, selected.toSet(), routingMode)
        val validation = config.validationMessage()
        if (validation != null) {
            status = validation
        } else {
            val permissionIntent = VpnService.prepare(activity)
            if (permissionIntent == null) {
                startRouter(activity, server, port, selected.toSet(), routingMode, dnsSettings)
                status = "正在启动 VPN"
            } else {
                vpnPermission.launch(permissionIntent)
            }
        }
    }
    val requestStop = {
        activity.startService(Intent(activity, SocksVpnService::class.java).apply {
            action = SocksVpnService.ACTION_STOP
        })
        status = "正在停止"
    }
    val isTunnelActive = HevTunnel.isRunning()
    val isTransitioning = status.startsWith("正在启动") || status.startsWith("正在停止") || status.contains("重连")

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 16.dp, bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            item { AppHeader() }
            item {
                StatusCard(
                    status = status,
                    isActive = isTunnelActive,
                    isTransitioning = isTransitioning,
                    sentBytes = tunnelStats.getOrElse(1) { 0L },
                    receivedBytes = tunnelStats.getOrElse(3) { 0L },
                    onToggle = if (isTunnelActive) requestStop else requestStart,
                )
            }
            item {
                ServerCard(
                    profiles = profiles,
                    activeProfileId = activeProfileId,
                    server = server,
                    port = port,
                    onServerChange = { server = it; persist() },
                    onPortChange = { port = it.filter(Char::isDigit); persist() },
                    onSelectProfile = { profile ->
                        persist()
                        activeProfileId = profile.id
                        server = profile.address
                        port = profile.port.toString()
                        persist()
                    },
                    onAddProfile = { showAddProfile = true },
                    onDeleteProfile = if (profiles.size > 1) {
                        {
                            profiles = profiles.filterNot { it.id == activeProfileId }
                            val next = profiles.first()
                            activeProfileId = next.id
                            server = next.address
                            port = next.port.toString()
                            persist()
                        }
                    } else null,
                )
            }
            item {
                RoutingCard(
                    routingMode = routingMode,
                    onModeChange = { routingMode = it; persist() },
                )
            }
            item {
                val source = when {
                    dnsSettings.dohUrl.isNotBlank() -> "DoH 优先"
                    dnsSettings.conventionalDns.isNotBlank() -> "自定义 DNS"
                    else -> "系统 DNS"
                }
                SettingsCard(
                    dnsSummary = "$source · 缓存 ${dnsSettings.cacheMinutes} 分钟",
                    logSummary = "查看运行状态和错误类别",
                    onDnsClick = { showDnsSettings = true },
                    onLogClick = { showDiagnostics = true },
                )
            }
            if (routingMode != RoutingMode.GLOBAL_PROXY) {
                item {
                    SectionHeader(
                        title = "应用路由",
                        summary = "已选择 $selectedGroupCount 个应用组 · $selectedMeaning",
                    )
                }
                item {
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(
                            query,
                            { query = it },
                            label = { Text("搜索应用或包名") },
                            modifier = Modifier.weight(1f),
                            singleLine = true,
                            shape = RoundedCornerShape(14.dp),
                            leadingIcon = { Icon(Icons.Default.Tune, contentDescription = null) },
                        )
                        FilterChip(showSystem, { showSystem = !showSystem }, label = { Text("系统") })
                    }
                }
                items(visibleApps, key = { it.packageNames.joinToString("\u0000") }) { app ->
                    val selectedInGroup = app.packageNames.any { it in selected }
                    AppRouteRow(
                        app = app,
                        selected = app.packageNames.all { it in selected },
                        onClick = {
                            if (selectedInGroup) {
                                app.packageNames.forEach { selected.remove(it) }
                            } else {
                                app.packageNames.forEach { if (it !in selected) selected.add(it) }
                            }
                            persist()
                        },
                    )
                }
            }
        }
    }

    if (showAddProfile) {
        AddProfileDialog(
            onDismiss = { showAddProfile = false },
            onAdd = { name, address, newPort ->
                val profile = ServerProfile(UUID.randomUUID().toString(), name, address, newPort)
                profiles = profiles + profile
                activeProfileId = profile.id
                server = profile.address
                port = profile.port.toString()
                showAddProfile = false
                persist()
            },
        )
    }
    if (showDnsSettings) {
        DnsSettingsDialog(
            initial = dnsSettings,
            onDismiss = { showDnsSettings = false },
            onSave = {
                dnsSettings = it
                showDnsSettings = false
                persist()
            },
        )
    }
    if (showDiagnostics) {
        DiagnosticLogDialog(
            entries = DiagnosticLogStore.snapshot(),
            onClear = { DiagnosticLogStore.clear(); showDiagnostics = false },
            onDismiss = { showDiagnostics = false },
        )
    }
}

@Composable
private fun AppHeader() {
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Surface(
            color = MaterialTheme.colorScheme.primaryContainer,
            shape = RoundedCornerShape(16.dp),
        ) {
            Icon(
                Icons.Default.Security,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(12.dp).size(28.dp),
            )
        }
        Spacer(Modifier.width(12.dp))
        Column {
            Text("SOCKS 路由器", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Text("个人 SOCKS5 网络路由器", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun StatusCard(
    status: String,
    isActive: Boolean,
    isTransitioning: Boolean,
    sentBytes: Long,
    receivedBytes: Long,
    onToggle: () -> Unit,
) {
    val title = when {
        status.contains("运行") || isActive -> "代理运行中"
        status.contains("等待") -> "等待网络"
        status.contains("重连") -> "正在重连"
        status.startsWith("正在启动") -> "正在启动"
        status.startsWith("正在停止") -> "正在停止"
        status.contains("失败") -> "代理启动失败"
        else -> "代理已停止"
    }
    val icon = if (isActive) Icons.Default.Wifi else Icons.Default.Security
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(22.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
    ) {
        Column(modifier = Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(
                    color = if (isActive) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surface,
                    shape = RoundedCornerShape(14.dp),
                ) {
                    Icon(
                        icon,
                        contentDescription = null,
                        tint = if (isActive) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(10.dp).size(24.dp),
                    )
                }
                Spacer(Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Text(
                        if (isActive) "应用网络流量将按当前路由规则转发" else "点击按钮启动 SOCKS5 代理服务",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (isActive) {
                    Surface(color = MaterialTheme.colorScheme.primary, shape = RoundedCornerShape(50)) {
                        Text("运行中", color = MaterialTheme.colorScheme.onPrimary, style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp))
                    }
                }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.55f))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                SummaryCell("发送", formatBytes(sentBytes), Icons.Default.Language, Modifier.weight(1f))
                SummaryCell("接收", formatBytes(receivedBytes), Icons.Default.Download, Modifier.weight(1f))
            }
            Button(
                onClick = onToggle,
                enabled = !isTransitioning,
                modifier = Modifier.fillMaxWidth().height(52.dp),
                shape = RoundedCornerShape(16.dp),
            ) {
                Icon(if (isActive) Icons.Default.Stop else Icons.Default.PlayArrow, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text(if (isActive) "停止代理" else "启动代理")
            }
        }
    }
}

@Composable
private fun SummaryCell(label: String, value: String, icon: ImageVector, modifier: Modifier = Modifier) {
    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(7.dp))
        Column {
            Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(value, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
private fun ServerCard(
    profiles: List<ServerProfile>,
    activeProfileId: String,
    server: String,
    port: String,
    onServerChange: (String) -> Unit,
    onPortChange: (String) -> Unit,
    onSelectProfile: (ServerProfile) -> Unit,
    onAddProfile: () -> Unit,
    onDeleteProfile: (() -> Unit)?,
) {
    Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp)) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Storage, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(8.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text("当前服务器", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Text("${server.ifBlank { "未配置" }}:${port.ifBlank { "—" }}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (onDeleteProfile != null) TextButton(onClick = onDeleteProfile) { Text("删除") }
            }
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(vertical = 2.dp)) {
                items(profiles, key = { it.id }) { profile ->
                    FilterChip(
                        selected = profile.id == activeProfileId,
                        onClick = { onSelectProfile(profile) },
                        label = { Text(profile.name) },
                    )
                }
                item {
                    OutlinedButton(onClick = onAddProfile, shape = RoundedCornerShape(12.dp)) { Text("＋ 新建") }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = server,
                    onValueChange = onServerChange,
                    label = { Text("服务器 IPv4") },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(14.dp),
                )
                OutlinedTextField(
                    value = port,
                    onValueChange = onPortChange,
                    label = { Text("端口") },
                    singleLine = true,
                    modifier = Modifier.width(112.dp),
                    shape = RoundedCornerShape(14.dp),
                )
            }
        }
    }
}

@Composable
private fun RoutingCard(routingMode: RoutingMode, onModeChange: (RoutingMode) -> Unit) {
    Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp)) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Apps, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(8.dp))
                Column {
                    Text("路由模式", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Text(
                        if (routingMode == RoutingMode.GLOBAL_PROXY) "所有应用都走 SOCKS5，无需选择应用" else "决定已选应用的网络出口",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Spacer(Modifier.height(4.dp))
            RoutingChoice("仅代理已选择的应用", routingMode == RoutingMode.SELECTED_PROXY) { onModeChange(RoutingMode.SELECTED_PROXY) }
            RoutingChoice("仅直连已选择的应用", routingMode == RoutingMode.SELECTED_DIRECT) { onModeChange(RoutingMode.SELECTED_DIRECT) }
            RoutingChoice("全局代理", routingMode == RoutingMode.GLOBAL_PROXY) { onModeChange(RoutingMode.GLOBAL_PROXY) }
        }
    }
}

@Composable
private fun SettingsCard(
    dnsSummary: String,
    logSummary: String,
    onDnsClick: () -> Unit,
    onLogClick: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp)) {
        Column(modifier = Modifier.padding(vertical = 4.dp)) {
            SettingsRow(Icons.Default.Dns, "DNS 设置", dnsSummary, onDnsClick)
            HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.outline.copy(alpha = 0.45f))
            SettingsRow(Icons.Default.Description, "诊断日志", logSummary, onLogClick)
        }
    }
}

@Composable
private fun SettingsRow(icon: ImageVector, title: String, summary: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
            Text(summary, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Icon(Icons.Default.ChevronRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun SectionHeader(title: String, summary: String) {
    Column(modifier = Modifier.padding(top = 4.dp)) {
        Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        Text(summary, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun AppRouteRow(app: AppRow, selected: Boolean, onClick: () -> Unit) {
    val appIcon = remember(app.packageNames) {
        app.icon?.let { drawable ->
            runCatching {
                val copy = drawable.constantState?.newDrawable()?.mutate() ?: drawable
                copy.toBitmap(width = 72, height = 72).asImageBitmap()
            }.getOrNull()
        }
    }
    Surface(
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).clickable(onClick = onClick),
        color = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface,
        tonalElevation = if (selected) 1.dp else 0.dp,
    ) {
        Row(modifier = Modifier.padding(horizontal = 14.dp, vertical = 11.dp), verticalAlignment = Alignment.CenterVertically) {
            Surface(color = MaterialTheme.colorScheme.secondaryContainer, shape = RoundedCornerShape(12.dp)) {
                if (appIcon != null) {
                    Image(
                        bitmap = appIcon,
                        contentDescription = "${app.label} 图标",
                        modifier = Modifier.padding(6.dp).size(28.dp).clip(RoundedCornerShape(7.dp)),
                    )
                } else {
                    Icon(Icons.Default.Apps, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(9.dp).size(22.dp))
                }
            }
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(app.label, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
                Text(
                    if (app.isSharedUid) "共享 UID · ${app.packageNames.size} 个应用" else app.packageNames.firstOrNull().orEmpty(),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }
            Checkbox(checked = selected, onCheckedChange = { onClick() })
        }
    }
}

@Composable
private fun DiagnosticLogDialog(
    entries: List<DiagnosticLogEntry>,
    onClear: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("诊断日志") },
        text = {
            if (entries.isEmpty()) {
                Text("暂无诊断记录")
            } else {
                LazyColumn(modifier = Modifier.heightIn(max = 420.dp)) {
                    items(entries.reversed()) { entry ->
                        val level = when (entry.level) {
                            DiagnosticLevel.INFO -> "信息"
                            DiagnosticLevel.WARNING -> "警告"
                            DiagnosticLevel.ERROR -> "错误"
                        }
                        Text(
                            "${formatDiagnosticTime(entry.timestampMillis)}  [$level] ${entry.message}",
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(vertical = 3.dp),
                        )
                    }
                }
            }
        },
        confirmButton = { Button(onClick = onDismiss) { Text("关闭") } },
        dismissButton = {
            TextButton(onClick = onClear, enabled = entries.isNotEmpty()) { Text("清空") }
        },
    )
}

@Composable
private fun DnsSettingsDialog(initial: DnsSettings, onDismiss: () -> Unit, onSave: (DnsSettings) -> Unit) {
    var dohUrl by remember { mutableStateOf(initial.dohUrl) }
    var dnsServer by remember { mutableStateOf(initial.conventionalDns) }
    var cacheMinutes by remember { mutableStateOf(initial.cacheMinutes.toString()) }
    var fallback by remember { mutableStateOf(initial.fallbackPolicy) }
    val settings = DnsSettings(dohUrl.trim(), dnsServer.trim(), fallback, cacheMinutes.toIntOrNull() ?: 0)
    val error = settings.validationMessage()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("DNS 设置") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("解析顺序：DoH → 自定义 DNS → 系统 DNS")
                OutlinedTextField(dohUrl, { dohUrl = it }, label = { Text("DoH HTTPS 地址（可选）") }, singleLine = true)
                Text("内置 DoH 服务")
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(BuiltInDohServices.services) { service ->
                        FilterChip(
                            selected = dohUrl.trim() == service.url,
                            onClick = { dohUrl = service.url },
                            label = { Text(service.name) },
                        )
                    }
                }
                OutlinedTextField(dnsServer, { dnsServer = it }, label = { Text("DNS IPv4（可选）") }, singleLine = true)
                OutlinedTextField(
                    cacheMinutes, { cacheMinutes = it.filter(Char::isDigit) },
                    label = { Text("缓存分钟数") }, singleLine = true,
                )
                RoutingChoice("严格：最高优先级失败后停止", fallback == DnsFallbackPolicy.STRICT) {
                    fallback = DnsFallbackPolicy.STRICT
                }
                RoutingChoice("自动：失败后尝试下一项", fallback == DnsFallbackPolicy.AUTOMATIC) {
                    fallback = DnsFallbackPolicy.AUTOMATIC
                }
                if (error != null) Text(error, color = MaterialTheme.colorScheme.error)
            }
        },
        confirmButton = { Button(onClick = { onSave(settings) }, enabled = error == null) { Text("保存") } },
        dismissButton = { Button(onClick = onDismiss) { Text("取消") } },
    )
}

@Composable
private fun AddProfileDialog(onDismiss: () -> Unit, onAdd: (String, String, Int) -> Unit) {
    var name by remember { mutableStateOf("") }
    var address by remember { mutableStateOf("") }
    var port by remember { mutableStateOf("7897") }
    val valid = name.isNotBlank() && PrototypeConfig(address.trim(), port.toIntOrNull() ?: 0).isValid()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("新建服务器配置") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(name, { name = it }, label = { Text("名称") }, singleLine = true)
                OutlinedTextField(address, { address = it }, label = { Text("IPv4 地址") }, singleLine = true)
                OutlinedTextField(
                    port, { port = it.filter(Char::isDigit) }, label = { Text("端口") }, singleLine = true,
                )
            }
        },
        confirmButton = {
            Button(onClick = { onAdd(name.trim(), address.trim(), port.toInt()) }, enabled = valid) { Text("保存") }
        },
        dismissButton = { Button(onClick = onDismiss) { Text("取消") } },
    )
}

@Composable
private fun RoutingChoice(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(modifier = Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        RadioButton(selected = selected, onClick = onClick)
        Text(label, modifier = Modifier.padding(top = 12.dp))
    }
}

private fun startRouter(
    activity: Activity, server: String, port: String, packages: Set<String>, routingMode: RoutingMode,
    dnsSettings: DnsSettings,
) {
    val intent = Intent(activity, SocksVpnService::class.java).apply {
        action = SocksVpnService.ACTION_START
        putExtra(SocksVpnService.EXTRA_SERVER, server.trim())
        putExtra(SocksVpnService.EXTRA_PORT, port.toIntOrNull() ?: 0)
        putExtra(SocksVpnService.EXTRA_PACKAGES, packages.toTypedArray())
        putExtra(SocksVpnService.EXTRA_ROUTING_MODE, routingMode.name)
        putExtra(SocksVpnService.EXTRA_DOH_URL, dnsSettings.dohUrl)
        putExtra(SocksVpnService.EXTRA_DNS_SERVER, dnsSettings.conventionalDns)
        putExtra(SocksVpnService.EXTRA_DNS_FALLBACK, dnsSettings.fallbackPolicy.name)
        putExtra(SocksVpnService.EXTRA_DNS_CACHE_MINUTES, dnsSettings.cacheMinutes)
    }
    ContextCompat.startForegroundService(activity, intent)
}

private fun formatBytes(bytes: Long): String {
    val value = bytes.coerceAtLeast(0L).toDouble()
    return when {
        value >= 1024 * 1024 * 1024 -> String.format(Locale.ROOT, "%.1f GB", value / (1024 * 1024 * 1024))
        value >= 1024 * 1024 -> String.format(Locale.ROOT, "%.1f MB", value / (1024 * 1024))
        value >= 1024 -> String.format(Locale.ROOT, "%.1f KB", value / 1024)
        else -> "${bytes.coerceAtLeast(0L)} B"
    }
}

private fun formatDiagnosticTime(timestampMillis: Long): String =
    DateTimeFormatter.ofPattern("HH:mm:ss")
        .withZone(ZoneId.systemDefault())
        .format(Instant.ofEpochMilli(timestampMillis))
