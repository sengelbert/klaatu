package com.klaatu.rss

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.text.format.DateUtils
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.RssFeed
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Upload
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.glance.appwidget.updateAll
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import coil.compose.AsyncImage
import com.klaatu.rss.data.FeedRepository
import com.klaatu.rss.data.db.ArticleWithFeed
import com.klaatu.rss.data.db.FeedEntity
import com.klaatu.rss.data.opml.OpmlFeed
import com.klaatu.rss.sync.FeedSyncWorker
import com.klaatu.rss.ui.theme.KlaatuTheme
import com.klaatu.rss.widget.KlaatuWidget
import com.klaatu.rss.widget.KlaatuWidgetReceiver
import com.klaatu.rss.widget.WidgetConfig
import com.klaatu.rss.widget.WidgetConfigActivity
import com.klaatu.rss.widget.WidgetPreferences
import com.klaatu.rss.widget.WidgetThemeMode
import kotlinx.coroutines.launch

private enum class AppTab {
    FEED,
    MANAGE,
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        FeedSyncWorker.schedulePeriodic(this)
        setContent {
            KlaatuTheme {
                KlaatuCompanionScreen()
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun KlaatuCompanionScreen() {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val repo = remember { FeedRepository(context) }
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }

    val feeds by repo.observeFeeds().collectAsState(initial = emptyList())
    val categories by repo.observeCategories().collectAsState(initial = emptyList())
    val articleCount by repo.observeTotalArticleCount().collectAsState(initial = 0)

    var currentTab by remember { mutableStateOf(AppTab.FEED) }
    var selectedFeedCategory by remember { mutableStateOf<String?>(null) }
    val articles by remember(selectedFeedCategory) {
        repo.observeArticles(selectedFeedCategory)
    }.collectAsState(initial = emptyList())

    var activeWidgets by remember { mutableStateOf(loadActiveWidgets(context)) }
    var refreshMinutes by remember { mutableStateOf(WidgetPreferences.getRefreshIntervalMinutes(context)) }
    var isSubscriptionsExpanded by remember { mutableStateOf(false) }
    var showAddFeedDialog by remember { mutableStateOf(false) }
    var isSyncing by remember { mutableStateOf(false) }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                activeWidgets = loadActiveWidgets(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val prefetchAndRefreshWidget: () -> Unit = {
        scope.launch {
            if (repo.prefetchRecentThumbnails() > 0) {
                KlaatuWidget().updateAll(context)
            }
        }
    }

    val triggerSync: () -> Unit = {
        if (!isSyncing) {
            scope.launch {
                isSyncing = true
                val synced = repo.syncAllFeeds()
                KlaatuWidget().updateAll(context)
                isSyncing = false
                prefetchAndRefreshWidget()
                snackbarHostState.showSnackbar("Synced $synced feeds")
            }
        }
    }

    val importOpmlLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val imported = context.contentResolver.openInputStream(uri)?.use { stream ->
                repo.importOpml(stream)
            } ?: 0
            isSyncing = true
            repo.syncAllFeeds()
            KlaatuWidget().updateAll(context)
            isSyncing = false
            prefetchAndRefreshWidget()
            snackbarHostState.showSnackbar("Imported $imported feeds")
        }
    }

    val exportOpmlLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("text/x-opml")
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val xml = repo.exportOpml()
            context.contentResolver.openOutputStream(uri)?.use { out ->
                out.write(xml.toByteArray(Charsets.UTF_8))
            }
            snackbarHostState.showSnackbar("Exported OPML")
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Klaatu", fontWeight = FontWeight.Bold)
                        Text(
                            "${feeds.size} feeds · $articleCount articles",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                actions = {
                    IconButton(
                        enabled = !isSyncing,
                        onClick = triggerSync,
                    ) {
                        Icon(Icons.Default.Refresh, contentDescription = "Sync feeds")
                    }
                },
            )
        },
        bottomBar = {
            NavigationBar {
                NavigationBarItem(
                    selected = currentTab == AppTab.FEED,
                    onClick = { currentTab = AppTab.FEED },
                    icon = { Icon(Icons.Default.RssFeed, contentDescription = "Feed") },
                    label = { Text("Feed") },
                )
                NavigationBarItem(
                    selected = currentTab == AppTab.MANAGE,
                    onClick = { currentTab = AppTab.MANAGE },
                    icon = { Icon(Icons.Default.Tune, contentDescription = "Manage") },
                    label = { Text("Manage") },
                )
            }
        },
        floatingActionButton = {
            if (currentTab == AppTab.MANAGE) {
                FloatingActionButton(onClick = { showAddFeedDialog = true }) {
                    Icon(Icons.Default.Add, contentDescription = "Add feed")
                }
            }
        },
    ) { innerPadding ->
        when (currentTab) {
            AppTab.FEED -> {
                FeedReaderTab(
                    modifier = Modifier.padding(innerPadding),
                    categories = categories,
                    selectedCategory = selectedFeedCategory,
                    onSelectCategory = { selectedFeedCategory = it },
                    articles = articles,
                    isRefreshing = isSyncing,
                    onRefresh = triggerSync,
                    onOpenArticle = { article ->
                        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(article.link))
                        context.startActivity(intent)
                    },
                    onGoToManage = { currentTab = AppTab.MANAGE },
                )
            }

            AppTab.MANAGE -> {
                ManageTab(
                    modifier = Modifier.padding(innerPadding),
                    feeds = feeds,
                    activeWidgets = activeWidgets,
                    refreshMinutes = refreshMinutes,
                    isSubscriptionsExpanded = isSubscriptionsExpanded,
                    onToggleSubscriptions = { isSubscriptionsExpanded = !isSubscriptionsExpanded },
                    onImportOpml = { importOpmlLauncher.launch(arrayOf("*/*")) },
                    onExportOpml = { exportOpmlLauncher.launch("klaatu-feeds.opml") },
                    onSelectRefreshMinutes = { minutes ->
                        refreshMinutes = minutes
                        WidgetPreferences.setRefreshIntervalMinutes(context, minutes)
                        FeedSyncWorker.schedulePeriodic(context, minutes)
                    },
                    onOpenWidgetConfig = { appWidgetId ->
                        val intent = Intent(context, WidgetConfigActivity::class.java).apply {
                            putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
                        }
                        context.startActivity(intent)
                    },
                    onDeleteFeed = { feedId ->
                        scope.launch {
                            repo.deleteFeed(feedId)
                            KlaatuWidget().updateAll(context)
                        }
                    },
                    onDeleteCategory = { category ->
                        scope.launch {
                            repo.deleteFeedsByCategory(category)
                            KlaatuWidget().updateAll(context)
                            snackbarHostState.showSnackbar("Removed feeds in $category")
                        }
                    },
                    onDeleteBroken = {
                        scope.launch {
                            repo.deleteBrokenFeeds()
                            KlaatuWidget().updateAll(context)
                            snackbarHostState.showSnackbar("Removed broken feeds")
                        }
                    },
                )
            }
        }
    }

    if (showAddFeedDialog) {
        AddFeedDialog(
            existingCategories = categories,
            onDismiss = { showAddFeedDialog = false },
            onAdd = { url, category ->
                showAddFeedDialog = false
                scope.launch {
                    val added = repo.addFeed(url, category)
                    if (added) {
                        isSyncing = true
                        repo.syncAllFeeds()
                        KlaatuWidget().updateAll(context)
                        isSyncing = false
                        prefetchAndRefreshWidget()
                        snackbarHostState.showSnackbar("Feed added")
                    } else {
                        snackbarHostState.showSnackbar("Feed already exists or URL is invalid")
                    }
                }
            },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FeedReaderTab(
    modifier: Modifier = Modifier,
    categories: List<String>,
    selectedCategory: String?,
    onSelectCategory: (String?) -> Unit,
    articles: List<ArticleWithFeed>,
    isRefreshing: Boolean,
    onRefresh: () -> Unit,
    onOpenArticle: (ArticleWithFeed) -> Unit,
    onGoToManage: () -> Unit,
) {
    Column(modifier = modifier.fillMaxSize()) {
        if (categories.isNotEmpty()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                FilterChip(
                    selected = selectedCategory == null,
                    onClick = { onSelectCategory(null) },
                    label = { Text("All Feeds") },
                )
                categories.forEach { category ->
                    FilterChip(
                        selected = selectedCategory == category,
                        onClick = {
                            onSelectCategory(if (selectedCategory == category) null else category)
                        },
                        label = { Text(category) },
                    )
                }
            }
        }

        PullToRefreshBox(
            isRefreshing = isRefreshing,
            onRefresh = onRefresh,
            modifier = Modifier.fillMaxSize(),
        ) {
            if (articles.isEmpty()) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(32.dp),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        text = "No stories yet",
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = "Import an OPML file or add RSS feeds in the Manage tab.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(16.dp))
                    OutlinedButton(onClick = onGoToManage) {
                        Text("Open Manage")
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(bottom = 16.dp),
                ) {
                    items(articles, key = { it.id }) { article ->
                        ArticleListRow(
                            article = article,
                            onClick = { onOpenArticle(article) },
                        )
                        HorizontalDivider(
                            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ArticleListRow(
    article: ArticleWithFeed,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (!article.imageUrl.isNullOrBlank()) {
            AsyncImage(
                model = article.imageUrl,
                contentDescription = article.title,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .size(width = 96.dp, height = 68.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant),
            )
        } else {
            Box(
                modifier = Modifier
                    .size(width = 96.dp, height = 68.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Default.RssFeed,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(24.dp),
                )
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = article.title,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = article.feedTitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = formatRelativeTime(article.publishedAt),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
        }
    }
}

private fun formatRelativeTime(publishedAt: Long): String {
    if (publishedAt <= 0L) return ""
    return DateUtils.getRelativeTimeSpanString(
        publishedAt,
        System.currentTimeMillis(),
        DateUtils.MINUTE_IN_MILLIS,
        DateUtils.FORMAT_ABBREV_RELATIVE,
    ).toString()
}

@Composable
private fun ManageTab(
    modifier: Modifier = Modifier,
    feeds: List<FeedEntity>,
    activeWidgets: List<WidgetConfig>,
    refreshMinutes: Long,
    isSubscriptionsExpanded: Boolean,
    onToggleSubscriptions: () -> Unit,
    onImportOpml: () -> Unit,
    onExportOpml: () -> Unit,
    onSelectRefreshMinutes: (Long) -> Unit,
    onOpenWidgetConfig: (Int) -> Unit,
    onDeleteFeed: (Long) -> Unit,
    onDeleteCategory: (String) -> Unit,
    onDeleteBroken: () -> Unit,
) {
    var selectedCategory by remember { mutableStateOf<String?>(null) }
    var showBrokenOnly by remember { mutableStateOf(false) }
    var categoryPendingDelete by remember { mutableStateOf<String?>(null) }
    var confirmDeleteBroken by remember { mutableStateOf(false) }

    val categoryCounts = remember(feeds) {
        feeds.groupingBy { it.category }.eachCount()
    }
    val brokenCount = remember(feeds) {
        feeds.count { it.lastError.isNotEmpty() }
    }
    val sortedCategories = remember(categoryCounts) {
        categoryCounts.keys.sorted()
    }
    val activeBrokenFilter = showBrokenOnly && brokenCount > 0
    val activeCategory = if (activeBrokenFilter) null else selectedCategory?.takeIf { it in categoryCounts }
    val filteredFeeds = remember(feeds, activeCategory, activeBrokenFilter) {
        when {
            activeBrokenFilter -> feeds.filter { it.lastError.isNotEmpty() }
            activeCategory != null -> feeds.filter { it.category == activeCategory }
            else -> feeds
        }
    }

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // OPML Actions
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                OutlinedButton(
                    modifier = Modifier.weight(1f),
                    onClick = onImportOpml,
                ) {
                    Icon(Icons.Default.Upload, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Import OPML")
                }
                OutlinedButton(
                    modifier = Modifier.weight(1f),
                    onClick = onExportOpml,
                ) {
                    Icon(Icons.Default.Download, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Export OPML")
                }
            }
        }

        // Background Refresh Frequency
        item {
            RefreshFrequencySection(
                selectedMinutes = refreshMinutes,
                onSelect = onSelectRefreshMinutes,
            )
        }

        // Active Widgets Section
        item {
            Text("Home Screen Widgets", style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(4.dp))
            if (activeWidgets.isEmpty()) {
                Text(
                    "No widgets placed yet. Long-press your home screen → Widgets → Klaatu Feed to add one or more widgets.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        if (activeWidgets.isNotEmpty()) {
            items(activeWidgets, key = { it.appWidgetId }) { widgetConfig ->
                WidgetConfigRow(
                    config = widgetConfig,
                    onClick = { onOpenWidgetConfig(widgetConfig.appWidgetId) },
                )
            }
        }

        item {
            HorizontalDivider()
            Spacer(Modifier.height(4.dp))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(enabled = feeds.isNotEmpty(), onClick = onToggleSubscriptions)
                    .padding(vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "Individual Feeds (${feeds.size})",
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.weight(1f),
                )
                if (feeds.isNotEmpty()) {
                    Icon(
                        imageVector = if (isSubscriptionsExpanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                        contentDescription = if (isSubscriptionsExpanded) "Collapse feeds" else "Expand feeds",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        if (feeds.isEmpty()) {
            item {
                Text(
                    "No feeds yet. Import an OPML file or tap + to add an RSS/Atom feed.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 16.dp),
                )
            }
        } else if (isSubscriptionsExpanded) {
            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    FilterChip(
                        selected = !activeBrokenFilter && activeCategory == null,
                        onClick = {
                            showBrokenOnly = false
                            selectedCategory = null
                        },
                        label = { Text("All (${feeds.size})") },
                    )
                    if (brokenCount > 0) {
                        FilterChip(
                            selected = activeBrokenFilter,
                            onClick = {
                                showBrokenOnly = !activeBrokenFilter
                                if (showBrokenOnly) selectedCategory = null
                            },
                            label = { Text("Broken ($brokenCount)") },
                        )
                    }
                    sortedCategories.forEach { category ->
                        FilterChip(
                            selected = !activeBrokenFilter && activeCategory == category,
                            onClick = {
                                showBrokenOnly = false
                                selectedCategory = if (activeCategory == category) null else category
                            },
                            label = { Text("$category (${categoryCounts[category] ?: 0})") },
                        )
                    }
                }
            }

            if (activeBrokenFilter) {
                item {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = "$brokenCount broken feed(s)",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(onClick = { confirmDeleteBroken = true }) {
                            Text(
                                text = "Delete all broken",
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                    }
                }
            } else if (activeCategory != null) {
                item {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = "${filteredFeeds.size} feed(s) in $activeCategory",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(onClick = { categoryPendingDelete = activeCategory }) {
                            Text(
                                text = "Delete folder",
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                    }
                }
            }

            items(filteredFeeds, key = { it.id }) { feed ->
                FeedRow(
                    feed = feed,
                    onDelete = { onDeleteFeed(feed.id) },
                )
            }
        }

        item { Spacer(Modifier.height(72.dp)) }
    }

    val pendingDelete = categoryPendingDelete
    if (pendingDelete != null) {
        val count = categoryCounts[pendingDelete] ?: 0
        AlertDialog(
            onDismissRequest = { categoryPendingDelete = null },
            title = { Text("Delete folder feeds?") },
            text = {
                Text("Remove all $count feed(s) in \"$pendingDelete\" and their cached articles?")
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        categoryPendingDelete = null
                        selectedCategory = null
                        onDeleteCategory(pendingDelete)
                    },
                ) {
                    Text("Delete", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { categoryPendingDelete = null }) {
                    Text("Cancel")
                }
            },
        )
    }

    if (confirmDeleteBroken) {
        AlertDialog(
            onDismissRequest = { confirmDeleteBroken = false },
            title = { Text("Delete broken feeds?") },
            text = {
                Text("Remove all $brokenCount feed(s) that failed their last sync?")
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmDeleteBroken = false
                        showBrokenOnly = false
                        onDeleteBroken()
                    },
                ) {
                    Text("Delete", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmDeleteBroken = false }) {
                    Text("Cancel")
                }
            },
        )
    }
}

private fun loadActiveWidgets(context: android.content.Context): List<WidgetConfig> {
    val manager = AppWidgetManager.getInstance(context)
    val ids = manager.getAppWidgetIds(ComponentName(context, KlaatuWidgetReceiver::class.java))
    return ids.map { WidgetPreferences.getConfig(context, it) }
}

@Composable
private fun RefreshFrequencySection(
    selectedMinutes: Long,
    onSelect: (Long) -> Unit,
) {
    val options = remember {
        listOf(
            15L to "15m",
            30L to "30m",
            60L to "1h",
            180L to "3h",
            360L to "6h",
            0L to "Manual",
        )
    }
    Column {
        Text("Background Refresh", style = MaterialTheme.typography.titleSmall)
        Spacer(Modifier.height(4.dp))
        Row(
            modifier = Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            options.forEach { (minutes, label) ->
                FilterChip(
                    selected = selectedMinutes == minutes,
                    onClick = { onSelect(minutes) },
                    label = { Text(label) },
                )
            }
        }
    }
}

@Composable
private fun WidgetConfigRow(
    config: WidgetConfig,
    onClick: () -> Unit,
) {
    val label = config.customLabel.ifBlank {
        when {
            config.categories.isEmpty() && config.feedIds.isEmpty() -> "All Feeds"
            config.categories.isNotEmpty() -> config.categories.sorted().joinToString(" + ")
            else -> "${config.feedIds.size} Feed(s)"
        }
    }
    val isDark = when (config.themeMode) {
        WidgetThemeMode.SYSTEM -> isSystemInDarkTheme()
        WidgetThemeMode.LIGHT -> false
        WidgetThemeMode.DARK -> true
    }
    val palette = config.theme.paletteFor(isDark, config.headerStyle)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(
            modifier = Modifier
                .size(20.dp)
                .clip(CircleShape)
                .border(1.dp, MaterialTheme.colorScheme.outlineVariant, CircleShape),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .background(palette.headerBg),
            )
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .background(palette.cardBg),
            )
        }
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = "Widget #${config.appWidgetId} · ${config.theme.displayName} (${config.themeMode.displayName})",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Icon(
            Icons.Default.Settings,
            contentDescription = "Configure widget",
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun FeedRow(
    feed: FeedEntity,
    onDelete: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = feed.title,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = "${feed.category} · ${feed.xmlUrl}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (feed.lastError.isNotEmpty()) {
                Text(
                    text = "Failed: ${feed.lastError}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        IconButton(onClick = onDelete) {
            Icon(
                Icons.Default.Delete,
                contentDescription = "Remove feed",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AddFeedDialog(
    existingCategories: List<String>,
    onDismiss: () -> Unit,
    onAdd: (url: String, category: String) -> Unit,
) {
    val folderOptions = remember(existingCategories) {
        (existingCategories + OpmlFeed.DEFAULT_CATEGORY)
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()
    }

    var url by remember { mutableStateOf("") }
    var dropdownExpanded by remember { mutableStateOf(false) }
    var isCreatingNewFolder by remember { mutableStateOf(false) }
    var selectedFolder by remember(folderOptions) { mutableStateOf(folderOptions.first()) }
    var newFolderName by remember { mutableStateOf("") }

    val resolvedCategory = if (isCreatingNewFolder) newFolderName.trim() else selectedFolder
    val canSubmit = url.isNotBlank() && (!isCreatingNewFolder || newFolderName.isNotBlank())

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add RSS / Atom Feed") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it },
                    label = { Text("Feed URL") },
                    placeholder = { Text("https://9to5linux.com/feed") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )

                ExposedDropdownMenuBox(
                    expanded = dropdownExpanded,
                    onExpandedChange = { dropdownExpanded = it },
                ) {
                    OutlinedTextField(
                        value = if (isCreatingNewFolder) "New folder…" else selectedFolder,
                        onValueChange = {},
                        readOnly = true,
                        label = { Text("Folder") },
                        trailingIcon = {
                            ExposedDropdownMenuDefaults.TrailingIcon(expanded = dropdownExpanded)
                        },
                        colors = ExposedDropdownMenuDefaults.outlinedTextFieldColors(),
                        modifier = Modifier
                            .fillMaxWidth()
                            .menuAnchor(MenuAnchorType.PrimaryNotEditable),
                    )

                    ExposedDropdownMenu(
                        expanded = dropdownExpanded,
                        onDismissRequest = { dropdownExpanded = false },
                    ) {
                        folderOptions.forEach { folder ->
                            DropdownMenuItem(
                                text = { Text(folder) },
                                onClick = {
                                    selectedFolder = folder
                                    isCreatingNewFolder = false
                                    dropdownExpanded = false
                                },
                            )
                        }
                        HorizontalDivider()
                        DropdownMenuItem(
                            leadingIcon = {
                                Icon(
                                    imageVector = Icons.Default.CreateNewFolder,
                                    contentDescription = null,
                                )
                            },
                            text = { Text("Create new folder…") },
                            onClick = {
                                isCreatingNewFolder = true
                                dropdownExpanded = false
                            },
                        )
                    }
                }

                if (isCreatingNewFolder) {
                    OutlinedTextField(
                        value = newFolderName,
                        onValueChange = { newFolderName = it },
                        label = { Text("New folder name") },
                        placeholder = { Text("Android + Linux") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = canSubmit,
                onClick = { onAdd(url, resolvedCategory) },
            ) {
                Text("Add")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        },
    )
}
