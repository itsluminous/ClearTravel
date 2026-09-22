package com.itsluminous.cleartravel.feature.menu

import androidx.annotation.StringRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.itsluminous.cleartravel.core.designsystem.component.ClearTravelCard
import com.itsluminous.cleartravel.core.designsystem.component.ExplainableIcon
import com.itsluminous.cleartravel.core.model.ThemeMode

/**
 * One row of the Menu root list. The list is data-driven so later milestones
 * (Backup/Restore, Google account) add entries without restructuring the screen.
 */
private data class MenuEntry(
    @StringRes val titleRes: Int,
    @StringRes val subtitleRes: Int,
    val onClick: () -> Unit,
)

/** Menu tab root: entry points into Settings, Manage presets and About. */
@Composable
internal fun MenuRootScreen(
    onOpenSettings: () -> Unit,
    onOpenPresets: () -> Unit,
    onOpenBackup: () -> Unit,
    onOpenAbout: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val entries =
        listOf(
            MenuEntry(R.string.menu_settings, R.string.menu_settings_subtitle, onOpenSettings),
            MenuEntry(R.string.menu_manage_presets, R.string.menu_manage_presets_subtitle, onOpenPresets),
            MenuEntry(R.string.menu_backup_restore, R.string.menu_backup_restore_subtitle, onOpenBackup),
            MenuEntry(R.string.menu_about, R.string.menu_about_subtitle, onOpenAbout),
        )
    Column(
        modifier =
            modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = stringResource(R.string.menu_title),
            style = MaterialTheme.typography.headlineSmall,
            modifier = Modifier.padding(bottom = 4.dp),
        )
        entries.forEach { entry ->
            ClearTravelCard(modifier = Modifier.clickable(onClick = entry.onClick)) {
                Text(text = stringResource(entry.titleRes), style = MaterialTheme.typography.titleMedium)
                Text(
                    text = stringResource(entry.subtitleRes),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
        }
    }
}

/** Settings: theme picker, the Security section (ADR-031) and the Google account section (spec feature 5). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SettingsScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val themeMode by viewModel.themeMode.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.menu_settings)) },
                navigationIcon = {
                    ExplainableIcon(
                        icon = Icons.AutoMirrored.Filled.ArrowBack,
                        explanationRes = R.string.menu_back,
                        onClick = onBack,
                    )
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
        ) {
            Text(
                text = stringResource(R.string.menu_theme_title),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(bottom = 8.dp),
            )
            ThemeOptionRow(
                labelRes = R.string.menu_theme_system,
                selected = themeMode == ThemeMode.SYSTEM,
                onSelect = { viewModel.setThemeMode(ThemeMode.SYSTEM) },
            )
            ThemeOptionRow(
                labelRes = R.string.menu_theme_light,
                selected = themeMode == ThemeMode.LIGHT,
                onSelect = { viewModel.setThemeMode(ThemeMode.LIGHT) },
            )
            ThemeOptionRow(
                labelRes = R.string.menu_theme_dark,
                selected = themeMode == ThemeMode.DARK,
                onSelect = { viewModel.setThemeMode(ThemeMode.DARK) },
            )
            SecuritySection(snackbarHostState = snackbarHostState)
            GoogleAccountSection(snackbarHostState = snackbarHostState)
        }
    }
}

@Composable
private fun ThemeOptionRow(
    @StringRes labelRes: Int,
    selected: Boolean,
    onSelect: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier =
            modifier
                .fillMaxWidth()
                .semantics { role = Role.RadioButton }
                .clickable(onClick = onSelect)
                .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = onSelect)
        Text(text = stringResource(labelRes), style = MaterialTheme.typography.bodyLarge)
    }
}

/** About: app version (from the installed package), description and source link. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AboutScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    // feature:menu is a library module — the app's BuildConfig.VERSION_NAME is not
    // visible here, so the installed package version is read via PackageManager.
    val versionName =
        remember(context) {
            runCatching {
                context.packageManager.getPackageInfo(context.packageName, 0).versionName
            }.getOrNull().orEmpty()
        }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.menu_about_title)) },
                navigationIcon = {
                    ExplainableIcon(
                        icon = Icons.AutoMirrored.Filled.ArrowBack,
                        explanationRes = R.string.menu_back,
                        onClick = onBack,
                    )
                },
            )
        },
    ) { padding ->
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = stringResource(R.string.menu_about_app_name),
                style = MaterialTheme.typography.headlineSmall,
            )
            Text(
                text = stringResource(R.string.menu_about_version, versionName),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = stringResource(R.string.menu_about_description),
                style = MaterialTheme.typography.bodyLarge,
            )
            Text(
                text = stringResource(R.string.menu_about_source),
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.padding(top = 8.dp),
            )
            Text(
                text = stringResource(R.string.menu_about_source_url),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }
}
