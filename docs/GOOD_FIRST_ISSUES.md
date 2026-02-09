# Good First Issues

Ready-to-publish GitHub Issues for attracting contributors. Copy each section into a new GitHub Issue.

---

## 1. Add translation (your language)

**Labels:** `good first issue`, `i18n`

**Description:**

Translate the 25 strings in `apps/android/app/src/main/res/values/strings.xml` to your language.

**Steps:**
1. Create a new folder: `apps/android/app/src/main/res/values-XX/` (where XX is your language code, e.g. `pl`, `es`, `de`)
2. Copy `values/strings.xml` into it
3. Translate all `<string>` values (keep the `name` attributes unchanged)
4. Open a PR

**No Kotlin knowledge required.** Just XML and your language skills.

---

## 2. Implement alarm sound picker

**Labels:** `good first issue`, `enhancement`, `android`

**Description:**

The Settings screen has a placeholder row for "Alarm Sound" (`SettingsScreen.kt` line ~268, `onClick = { /* Pick sound */ }`). Wire it up to Android's `RingtoneManager` so users can choose their alarm tone.

**Steps:**
1. Launch `RingtoneManager.ACTION_RINGTONE_PICKER` intent from Settings
2. Save the selected ringtone URI to `SettingsDataStore`
3. Use it in `AlarmReceiver` when triggering the alarm

**References:**
- [RingtoneManager docs](https://developer.android.com/reference/android/media/RingtoneManager)
- Existing `SettingsScreen.kt` → look for `/* Pick sound */`

---

## 3. Add ViewModel unit tests

**Labels:** `good first issue`, `testing`

**Description:**

`CreateAlarmViewModel` and `HomeViewModel` have zero unit tests. Add test coverage for core state transitions.

**Suggested tests for `CreateAlarmViewModel`:**
- Setting alarm time updates state correctly
- Toggling deposit mode enables/disables deposit fields
- `createAlarm()` with valid state creates an `AlarmEntity`
- `createAlarm()` with `hasDeposit=true` and `depositAmount=0` shows error

**Suggested tests for `HomeViewModel`:**
- Alarms load from database on init
- Toggle alarm updates `isEnabled` in database
- Delete alarm removes from database

**Setup:** Use `mockk` for mocking `AlarmDao` and `WalletManager`. See existing tests in `src/test/` for patterns.

---

## 4. Add alarm label editing in Details screen

**Labels:** `good first issue`, `enhancement`, `ui`

**Description:**

When viewing alarm details (`AlarmDetailsScreen.kt`), users cannot edit the alarm label. Add an inline edit capability.

**Steps:**
1. Make the label text clickable (or add an edit icon)
2. Show a `TextField` or `AlertDialog` with the current label
3. Save the updated label to Room database via `AlarmDao.update()`
4. Show a snackbar on success

**Keep it simple** — a dialog with a text field and Save/Cancel is sufficient.

---

## 5. Export alarm history to CSV

**Labels:** `good first issue`, `enhancement`

**Description:**

Add an export button to the History screen that saves alarm history as a CSV file.

**CSV columns:**
- Date, Time, Action (created/claimed/snoozed/slashed), Amount (SOL), Status

**Steps:**
1. Add a share/export icon button in `HistoryScreen.kt` top bar
2. Query alarm history from Room
3. Format as CSV string
4. Use `Intent.ACTION_SEND` or `ContentResolver` to share/save the file

**References:**
- `AlarmDao` for data queries
- Android [FileProvider](https://developer.android.com/reference/androidx/core/content/FileProvider) for sharing files
