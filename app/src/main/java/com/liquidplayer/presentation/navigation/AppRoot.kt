package com.liquidplayer.presentation.navigation

import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.core.tween
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.liquidplayer.domain.model.FolderPlaylistMode
import com.liquidplayer.domain.model.ScanResult
import com.liquidplayer.presentation.components.*
import com.liquidplayer.presentation.screens.*
import com.liquidplayer.presentation.theme.GlassDialog
import com.liquidplayer.presentation.theme.LiquidPlayerTheme
import com.liquidplayer.presentation.theme.LocalHazeState
import com.liquidplayer.presentation.theme.isDarkTheme
import com.liquidplayer.presentation.viewmodel.AppViewModel
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.hazeSource
import kotlinx.coroutines.launch

private val tabs = listOf(
    BottomTab(Routes.HOME, "Início", Icons.Rounded.Home),
    BottomTab(Routes.LIBRARY, "Biblioteca", Icons.Rounded.LibraryMusic),
    BottomTab(Routes.PLAYLISTS, "Playlists", Icons.Rounded.QueueMusic),
    BottomTab(Routes.FAVORITES, "Favoritos", Icons.Rounded.Favorite),
    BottomTab(Routes.SETTINGS, "Ajustes", Icons.Rounded.Settings),
)

@Composable
fun AppRoot(vm: AppViewModel = hiltViewModel()) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    val dark = isDarkTheme(settings)
    val activity = LocalContext.current as ComponentActivity
    SideEffect {
        val transparent = android.graphics.Color.TRANSPARENT
        val style = if (dark) SystemBarStyle.dark(transparent) else SystemBarStyle.light(transparent, transparent)
        activity.enableEdgeToEdge(statusBarStyle = style, navigationBarStyle = style)
    }
    LiquidPlayerTheme(settings) { AppContent(vm) }
}

@Composable
private fun AppContent(vm: AppViewModel) {
    val player by vm.player.collectAsStateWithLifecycle()
    val favoriteIds by vm.favoriteIds.collectAsStateWithLifecycle()
    val playlists by vm.allPlaylists.collectAsStateWithLifecycle()
    val pendingAdd by vm.pendingAdd.collectAsStateWithLifecycle()
    val scanDialog by vm.scanDialog.collectAsStateWithLifecycle()
    val updateState by vm.updateState.collectAsStateWithLifecycle()
    val updatePrompt by vm.updatePrompt.collectAsStateWithLifecycle()
    val appContext = LocalContext.current

    val navController = rememberNavController()
    val backStack by navController.currentBackStackEntryAsState()
    val route = backStack?.destination?.route
    val showChrome = route != Routes.NOW_PLAYING
    val hasPlayer = player.currentSong != null

    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val showMessage: (String) -> Unit = remember {
        { msg -> scope.launch { snackbar.currentSnackbarData?.dismiss(); snackbar.showSnackbar(msg) } }
    }
    LaunchedEffect(Unit) { vm.messages.collect { showMessage(it) } }

    val hazeState = remember { HazeState() }
    val navInset = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val bottomPadding = if (showChrome) 96.dp + (if (hasPlayer) 76.dp else 0.dp) + navInset else 0.dp

    fun navigate(target: String) {
        when {
            target == Routes.HOME -> {
                // Início é a raiz: desfaz tudo que está acima dele na pilha.
                if (!navController.popBackStack(Routes.HOME, inclusive = false)) navController.navigate(Routes.HOME)
            }
            tabs.any { it.route == target } -> navController.navigate(target) {
                popUpTo(Routes.HOME) { inclusive = false }
                launchSingleTop = true
            }
            else -> navController.navigate(target) { launchSingleTop = true }
        }
    }

    val selectedTab = when {
        route == Routes.FOLDERS -> Routes.SETTINGS
        route?.startsWith("playlist/") == true -> Routes.PLAYLISTS
        route?.startsWith("collection/") == true -> Routes.LIBRARY
        else -> route
    }

    CompositionLocalProvider(
        LocalSongActions provides vm.actions,
        LocalFavoriteIds provides favoriteIds,
        LocalCurrentSongId provides player.currentSong?.id,
        LocalShowMessage provides showMessage,
        LocalContentBottomPadding provides bottomPadding,
    ) {
        Box(Modifier.fillMaxSize()) {
            // Tudo que está aqui é "fonte" do desfoque das barras flutuantes.
            Box(Modifier.fillMaxSize().hazeSource(hazeState)) {
                LiquidBackground()
                NavHost(
                    navController, startDestination = Routes.HOME, modifier = Modifier.fillMaxSize(),
                    enterTransition = { fadeIn(tween(220)) }, exitTransition = { fadeOut(tween(160)) },
                    popEnterTransition = { fadeIn(tween(220)) }, popExitTransition = { fadeOut(tween(160)) },
                ) {
                    composable(Routes.HOME) { HomeScreen(::navigate) }
                    composable(Routes.LIBRARY) { LibraryScreen(::navigate) }
                    composable(Routes.PLAYLISTS) { PlaylistsScreen { navigate(Routes.playlist(it)) } }
                    composable(Routes.FAVORITES) { FavoritesScreen() }
                    composable(Routes.SETTINGS) { SettingsScreen({ navigate(Routes.FOLDERS) }) }
                    composable(Routes.FOLDERS) { FoldersScreen { navController.popBackStack() } }
                    composable(Routes.PLAYLIST, listOf(navArgument("playlistId") { type = NavType.LongType })) {
                        PlaylistDetailScreen(onBack = { navController.popBackStack() })
                    }
                    composable(
                        Routes.COLLECTION,
                        listOf(navArgument("type") { type = NavType.StringType }, navArgument("key") { type = NavType.StringType; defaultValue = "" }),
                    ) { CollectionScreen(onBack = { navController.popBackStack() }) }
                    composable(
                        Routes.NOW_PLAYING,
                        enterTransition = { slideInVertically(tween(380)) { it } + fadeIn(tween(200)) },
                        exitTransition = { fadeOut(tween(200)) },
                        popEnterTransition = { fadeIn(tween(200)) },
                        popExitTransition = { slideOutVertically(tween(320)) { it } + fadeOut(tween(300)) },
                    ) { NowPlayingScreen(onBack = { navController.popBackStack() }) }
                }
            }

            // Camada flutuante: mini player + barra de navegação com desfoque real do conteúdo atrás.
            CompositionLocalProvider(LocalHazeState provides hazeState) {
                Column(Modifier.align(Alignment.BottomCenter).navigationBarsPadding()) {
                    AnimatedVisibility(showChrome && hasPlayer, enter = fadeIn() + slideInVertically { it }, exit = fadeOut() + slideOutVertically { it }) {
                        MiniPlayer(
                            player, vm.positionFlow,
                            onOpen = { navController.navigate(Routes.NOW_PLAYING) { launchSingleTop = true } },
                            onPlayPause = vm::togglePlayPause, onNext = vm::next,
                        )
                    }
                    AnimatedVisibility(showChrome, enter = fadeIn() + slideInVertically { it }, exit = fadeOut() + slideOutVertically { it }) {
                        GlassBottomBar(tabs, selectedTab, { navigate(it.route) })
                    }
                }
            }
            SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).padding(bottom = bottomPadding + 8.dp, start = 16.dp, end = 16.dp))
        }

        pendingAdd?.let {
            AddToPlaylistDialog(playlists, vm::addPendingTo, vm::createPlaylistAndAdd, vm::dismissAdd)
        }
        scanDialog?.let { r -> ScanResultDialog(r, vm::dismissScan) { mode -> vm.createFromScan(r, mode) } }
        if (updatePrompt) {
            UpdateDialog(
                state = updateState, currentVersion = vm.currentVersion,
                onDownload = vm::downloadUpdate, onInstall = { vm.installUpdate(appContext) }, onCancel = vm::cancelDownload,
                onLater = vm::dismissUpdatePrompt, onSkip = vm::skipUpdate, onOpenPage = { vm.openReleasePage(appContext) },
            )
        }
    }
}

/** Fundo "aurora": manchas suaves com a cor de destaque, que dão o que refratar ao vidro. */
@Composable
private fun LiquidBackground() {
    val scheme = MaterialTheme.colorScheme
    val a = scheme.primary
    val b = scheme.tertiary
    Box(
        Modifier.fillMaxSize().background(scheme.background).drawBehind {
            val r = size.width * 0.95f
            drawCircle(Brush.radialGradient(listOf(a.copy(alpha = 0.30f), Color.Transparent), Offset(size.width * 0.1f, size.height * 0.05f), r), r, Offset(size.width * 0.1f, size.height * 0.05f))
            drawCircle(Brush.radialGradient(listOf(b.copy(alpha = 0.26f), Color.Transparent), Offset(size.width * 0.95f, size.height * 0.55f), r), r, Offset(size.width * 0.95f, size.height * 0.55f))
            drawCircle(Brush.radialGradient(listOf(a.copy(alpha = 0.18f), Color.Transparent), Offset(size.width * 0.2f, size.height * 0.98f), r), r, Offset(size.width * 0.2f, size.height * 0.98f))
        },
    )
}

@Composable
private fun ScanResultDialog(r: ScanResult, onDismiss: () -> Unit, onCreate: (FolderPlaylistMode) -> Unit) {
    GlassDialog(onDismiss) {
        Text(
            when {
                r.error != null -> "Não foi possível ler a pasta"
                r.isNewFolder -> "Pasta adicionada"
                else -> "Biblioteca atualizada"
            },
            style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold,
        )
        Text(r.folderName, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(8.dp))
        if (r.error != null) {
            Text(r.error, color = MaterialTheme.colorScheme.error)
        } else {
            Text("${r.totalFiles} arquivo(s) de áudio • ${r.added} novo(s) • ${r.updated} atualizado(s)")
            if (r.relinked > 0) Text("${r.relinked} reconectado(s) (movidos/renomeados)")
            if (r.markedUnavailable > 0) Text("${r.markedUnavailable} indisponível(is) (arquivo removido ou movido)")
            if (r.duplicates > 0) Text("${r.duplicates} duplicado(s) ignorado(s)")
        }
        val bad = r.unsupported + r.failed
        if (bad.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            Text("${bad.size} arquivo(s) incompatível(is) ou ilegível(is):", fontWeight = FontWeight.SemiBold)
            LazyColumn(Modifier.heightIn(max = 110.dp)) {
                items(bad.take(40)) { Text("• $it", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
        }
        Spacer(Modifier.height(12.dp))
        if (r.error == null && r.isNewFolder && r.totalFiles > 0) {
            Text("Quer organizar essas músicas em uma playlist? (opcional)", style = MaterialTheme.typography.bodySmall)
            Spacer(Modifier.height(6.dp))
            Button({ onCreate(FolderPlaylistMode.WHOLE_FOLDER) }, Modifier.fillMaxWidth()) { Text("Criar playlist com as músicas desta pasta") }
            if (r.hasSubfolders) OutlinedButton({ onCreate(FolderPlaylistMode.PER_SUBFOLDER) }, Modifier.fillMaxWidth()) { Text("Criar uma playlist para cada subpasta") }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            TextButton(onDismiss) { Text(if (r.isNewFolder && r.error == null) "Agora não" else "Fechar") }
        }
    }
}
