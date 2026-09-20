# core:google

Optional Google account integration, OFF by default — the app is fully functional
signed out. This module owns account linking (Credential Manager / Google Identity
with the **Web application** OAuth client id from `local.properties`; an Android
client id fails linking with developer-console error `[28444]`), the dedicated
"ClearTravel" Google Calendar sync (one-way, app → calendar, never the primary
calendar), and Drive upload of boarding passes/attachments plus backup files. All
Google work runs in WorkManager jobs after the local Room write commits — never on the
UI path. Tests run against fake Google clients, never live APIs. The skeleton ships a
link-state stub; real wiring is the Google milestone.
