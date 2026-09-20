# core:notifications

The single owner of every notification the app posts: channel definitions (trains,
flights, reminders), channel registration, notification builders, and deep links into
the relevant detail sheet. Also home to the Android 13+ notification-permission
handling helpers. Feature modules and WorkManager jobs request notifications through
this module's API — no feature ever creates a channel or builds a `Notification`
itself, so channel ids, grouping, and deep-link formats stay consistent. The skeleton
ships channel-id constants; builders and permission gates land with the Flights
milestone (the first notification producer).
