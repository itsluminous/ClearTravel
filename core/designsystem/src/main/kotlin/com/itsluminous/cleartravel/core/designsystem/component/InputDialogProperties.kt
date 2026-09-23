package com.itsluminous.cleartravel.core.designsystem.component

import androidx.compose.ui.window.DialogProperties

/**
 * Window properties for every dialog that holds typed input (ADR-041): a tap outside
 * does NOT dismiss it, back does.
 *
 * With gesture navigation an edge swipe starts as an ordinary touch DOWN at the
 * screen edge — outside the dialog — and the platform dialog closes on that DOWN
 * before the system has even recognised the swipe as "back". The rest of the gesture
 * then lands as back on whatever is underneath (with a tab root, that exits the app).
 * User report 2026-09-23: a half-typed trip or checklist name vanished on an
 * accidental edge swipe. Data-entry dialogs therefore close only through their
 * explicit Cancel/Save buttons or the back action; pure confirmations, pickers and
 * choice lists keep the default tap-outside dismissal — nothing is lost there.
 */
val InputDialogProperties: DialogProperties = DialogProperties(dismissOnClickOutside = false)
