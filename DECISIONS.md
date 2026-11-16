# Architecture & Tooling Decision Log

## ADR-001: Kotlin as Primary Language
- **Date**: 2026-11-15
- **Status**: Accepted
- **Decision**: Write the entire Android application, widget, and Gradle build scripts (`*.gradle.kts`) in Kotlin.
- **Rationale**: First-class Android support, Jetpack Compose & Glance compatibility, coroutines for async feed fetching, and concise null-safe data modeling.

## ADR-002: Jetpack Glance for the Scrollable Widget
- **Date**: 2026-11-15
- **Status**: Accepted
- **Decision**: Use `androidx.glance:glance-appwidget` (with `glance-material3`) instead of legacy XML `RemoteViewsService` + `RemoteViewsFactory`.
- **Rationale**: Glance provides a declarative Compose-style `LazyColumn` for scrollable widgets, cleaner per-widget state & theming (including Material You dynamic colors and custom color palettes), and significantly less boilerplate.

## ADR-003: Open Article Links in Default Browser
- **Date**: 2026-11-15
- **Status**: Accepted
- **Decision**: Tapping an article in the widget launches `Intent.ACTION_VIEW` with the article URL rather than an in-app WebView or Custom Tab.
- **Rationale**: Matches user preference for reading in their configured default browser with existing extensions, ad blockers, and history.

## ADR-004: Local-First Storage & Background Sync Stack
- **Date**: 2026-11-15
- **Status**: Accepted
- **Decision**: Use Room for local caching of feeds, categories, and articles; WorkManager for periodic background sync; OkHttp + `XmlPullParser` for RSS/Atom/OPML parsing; and Coil for thumbnail/favicon caching.
- **Rationale**: Lightweight, zero-backend architecture with reliable offline widget rendering and standard Android background scheduling.

## ADR-005: User-Space JDK 21 & Android SDK Bootstrap
- **Date**: 2026-11-15
- **Status**: Accepted
- **Decision**: Install Eclipse Temurin JDK 21 (`~/.local/jdks/jdk-21`) and Android SDK (`~/Android/Sdk`) in user-space rather than system-wide via `dnf`.
- **Rationale**: Avoids needing root/sudo for toolchain setup, isolates Android builds from the system JRE 25, and provides a reproducible `JAVA_HOME` / `ANDROID_HOME` setup compatible with Android Gradle Plugin.

## ADR-006: Standard DOM XML Parser (`javax.xml.parsers`) for OPML & RSS/Atom
- **Date**: 2026-11-15
- **Status**: Accepted
- **Decision**: Implement `OpmlCodec` and `FeedParser` using `javax.xml.parsers.DocumentBuilderFactory` instead of `android.util.Xml`.
- **Rationale**: `javax.xml.parsers` is available on both Android (API 26+) and standard JVM, allowing OPML and RSS/Atom parsing and round-trip export to be unit-tested directly on the JVM without Robolectric.

## ADR-007: Preset Widget Color Themes with SharedPreferences Storage
- **Date**: 2026-11-15
- **Status**: Accepted
- **Decision**: Provide curated `WidgetThemePreset` palettes (Classic Blue, AMOLED Dark, Slate Indigo, Modern Light, Forest, Warm Sepia) stored in `SharedPreferences` and applied directly in `KlaatuWidget`.
- **Rationale**: Gives immediate, reliable visual customization across all Android launchers while keeping widget rendering fast and predictable.

## ADR-008: Per-Widget Configuration (`appWidgetId`) for Multi-Widget Support
- **Date**: 2026-11-16
- **Status**: Accepted
- **Decision**: Key `WidgetPreferences` by `appWidgetId` and provide `WidgetConfigActivity` (`ACTION_APPWIDGET_CONFIGURE` + `reconfigurable` widget feature) so each widget instance independently selects its OPML folders, individual feeds, custom label, and theme.
- **Rationale**: Allows users with large, grouped OPML files to place multiple specialized widgets on their home screen (e.g., *Android + Linux* on one widget, *Finance* on another) and reconfigure each directly from its gear icon.

## ADR-009: Two-Tab In-App Reader & OS Dynamic Theming
- **Date**: 2026-11-16
- **Status**: Accepted
- **Decision**: Structure `MainActivity` with **Feed** and **Manage** bottom-navigation tabs wrapped in `KlaatuTheme` (OS Light/Dark + Material You dynamic colors on Android 12+).
- **Rationale**: Lets users browse stories, filter by OPML folder, and open articles directly inside the app while keeping OPML and widget management cleanly separated.

## ADR-010: GitHub Actions CI & Signed APK Releases
- **Date**: 2026-11-16
- **Status**: Accepted
- **Decision**: Use `.github/workflows/release.yml` to run `testDebugUnitTest` and `assembleRelease` on pushes/PRs, signing APKs from repository secrets and publishing GitHub Releases on `v*` tags.
- **Rationale**: Automates verification and APK distribution without storing release keystores in the repository.
