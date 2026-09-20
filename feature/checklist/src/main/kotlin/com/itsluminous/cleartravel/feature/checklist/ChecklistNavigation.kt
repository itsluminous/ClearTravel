package com.itsluminous.cleartravel.feature.checklist

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Checklist
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import com.itsluminous.cleartravel.core.designsystem.component.EmptyState

/** Route of the Checklist tab root. */
const val CHECKLIST_ROUTE = "checklist"

/** Checklist tab graph. Skeleton: the empty state until the Checklist milestone. */
fun NavGraphBuilder.checklistGraph() {
    composable(CHECKLIST_ROUTE) {
        ChecklistScreen()
    }
}

@Composable
internal fun ChecklistScreen(modifier: Modifier = Modifier) {
    EmptyState(
        icon = Icons.Filled.Checklist,
        title = stringResource(R.string.checklist_empty_title),
        message = stringResource(R.string.checklist_empty_message),
        modifier = modifier,
    )
}
