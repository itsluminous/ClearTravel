# core:notifications

The single owner of every notification the app posts: channel definitions (trains,
flights, reminders) and `NotificationChannelRegistrar`, the builders (`FlightNotifier`,
the app-lock "Unlock Clear Travel to sync" nudge), the `DeepLinkContract` extras every
content intent carries (`TARGET` train/flight + `ENTITY_ID`, resolved by
`MainActivity`), and the Android 13+ `NotificationPermissions` gate (`canPost`,
`needsRequest`) — every post is a silent no-op without permission. Feature modules and
workers request notifications through this API; no feature creates a channel or builds
a `Notification` itself, so ids, grouping and deep-link formats stay consistent.
