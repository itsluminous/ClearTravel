package com.itsluminous.cleartravel.feature.menu

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Menu
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import com.itsluminous.cleartravel.core.designsystem.component.EmptyState

/** Route of the Menu tab root (Settings/About land here in the Checklist milestone). */
const val MENU_ROUTE = "menu"

/** Menu tab graph. Skeleton: the empty state until Settings/About screens land. */
fun NavGraphBuilder.menuGraph() {
    composable(MENU_ROUTE) {
        MenuScreen()
    }
}

@Composable
internal fun MenuScreen(modifier: Modifier = Modifier) {
    EmptyState(
        icon = Icons.Filled.Menu,
        title = stringResource(R.string.menu_empty_title),
        message = stringResource(R.string.menu_empty_message),
        modifier = modifier,
    )
}
