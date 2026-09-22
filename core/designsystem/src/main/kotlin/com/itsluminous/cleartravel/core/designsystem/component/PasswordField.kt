package com.itsluminous.cleartravel.core.designsystem.component

import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.autofill.ContentType
import androidx.compose.ui.semantics.contentType
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import com.itsluminous.cleartravel.core.designsystem.R

/** Which autofill role a [PasswordField] plays — drives what password managers offer. */
enum class PasswordFieldRole {
    /** Entering an existing password (unlock, confirm current): managers offer to FILL. */
    EXISTING,

    /** Creating a new password (setup, change): managers offer to SAVE the typed value. */
    NEW,
}

/**
 * The one password input used across the app (ADR-031): masked by default with a
 * show/hide [ExplainableIcon], password keyboard, and the Compose autofill
 * `ContentType` matching [role] so system/third-party password managers integrate
 * without any `androidx.autofill` view plumbing (Compose ≥ 1.8 autofills natively).
 * [label] and [supportingText] are already resolved — callers own their strings.
 */
@Composable
fun PasswordField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    role: PasswordFieldRole = PasswordFieldRole.EXISTING,
    isError: Boolean = false,
    supportingText: String? = null,
    enabled: Boolean = true,
    imeAction: ImeAction = ImeAction.Done,
    onImeAction: () -> Unit = {},
) {
    var visible by rememberSaveable { mutableStateOf(false) }
    val autofillType =
        when (role) {
            PasswordFieldRole.EXISTING -> ContentType.Password
            PasswordFieldRole.NEW -> ContentType.NewPassword
        }
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        singleLine = true,
        enabled = enabled,
        isError = isError,
        supportingText = supportingText?.let { { Text(it) } },
        visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = imeAction, autoCorrectEnabled = false),
        keyboardActions = KeyboardActions(onDone = { onImeAction() }, onNext = { onImeAction() }, onGo = { onImeAction() }),
        trailingIcon = {
            ExplainableIcon(
                icon = if (visible) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                explanationRes = if (visible) R.string.designsystem_password_hide else R.string.designsystem_password_show,
                onClick = { visible = !visible },
            )
        },
        modifier = modifier.semantics { contentType = autofillType },
    )
}
