# Repository Guidelines

## Project Structure & Module Organization
- `app/` — Android phone app (Kotlin, Jetpack Compose, WebRTC).
- `wear/` — Wear OS companion app (remote control + status).
- Root Gradle build (`build.gradle.kts`, `settings.gradle.kts`).

## Build, Test, and Development Commands
- Open in Android Studio and let Gradle sync.
- If needed, generate wrapper:
  - `./gradlew wrapper`
- Run configurations:
  - `app` for phone
  - `wear` for watch

## Coding Style & Naming Conventions
- Language: Kotlin.
- Indentation: 4 spaces; max line length 100–120 chars.
- Naming: classes `CamelCase`, functions `lowerCamelCase`, packages lowercase.
- Keep UI in Compose; keep business logic in dedicated classes.

## Testing Guidelines
- No test framework is set up yet.
- If adding tests, prefer AndroidX + JUnit with `androidTest` for UI and `test` for JVM logic.

## Commit & Pull Request Guidelines
- Commits: concise, imperative subject.
- Scope one logical change per commit; include brief body when relevant.
- Update `README.md` when build steps, dependencies, or app behavior change.

## Security & Configuration
- OpenAI API key is user-provided and stored locally with encrypted storage.
- Do not log or hardcode API keys.
- Prefer least-privilege permissions and avoid background mic usage unless active.
