# Entry Flow and Backend Check

Checked: 2026-09-12. This is a focused implementation and regression check, not a production security certification.

## User Flow

- New guest at `/`: welcome screen with login, signup, and browse choices. Public search, map, category, and shared place URLs remain accessible directly.
- `/signup`: Google sign-in, Naver sign-in (connected on 2026-09-13; see `NAVER_LOGIN.md`), and email signup at `/signupform`.
- Email signup: email, password, confirmation, nickname, and consent. No invented phone number or full name is stored. Email login works; the internal account ID is generated server-side.
- `/onboarding`: interests, crowd preference, and regions. Each Next saves a draft to the DB; reloading resumes the saved step. Regions can be skipped.
- Completion: a 1.2-second paper-plane animation, with reduced-motion support, then the original destination or home.
- Guest favorite: login/signup/cancel prompt. After authentication and onboarding, save the intended favorite and return to its detail page.
- Existing accounts remain completed without compulsory onboarding. Their settings page links to editable preferences; cancelling that edit returns to the interests page.

## Data and API

`PUT /api/users/{userId}/preferences` requires the account owner's session and CSRF protection. Body:

```json
{
  "interests": ["sea", "cafe"],
  "crowdPreference": "quiet",
  "preferredRegions": ["강원", "제주"],
  "onboardingStep": 3,
  "onboardingCompleted": true
}
```

- Interests remain in `user_settings.interests_json`, normalized to canonical IDs. Old Korean labels are accepted and remain selectable in the settings UI.
- New `user_preferences` table stores crowd preference, region JSON, completion, and saved step by user ID. New accounts receive an incomplete row; a missing row identifies an existing account and is treated as completed.
- The frontend and backend share the choices in `travel-preferences.json` and `regions.json`; Gradle includes them as backend resources.
- `user_accounts.phone` becomes nullable for email-only signup. Startup migration supports H2 and MariaDB syntax; only H2 was exercised in this check.
- Preferences are stored for future recommendation use. This change does not claim that all recommendation ranking already uses them.
- Browser session storage contains only the guest-browsing choice and a safe internal return destination/pending favorite, not credentials or the authoritative profile.

## Backend Improvements

- Minimal and legacy signup contracts both work; nickname, email, consent, password confirmation, uniqueness, and password complexity are validated.
- Public signup cannot claim the configured administrator username. Newly created Google accounts receive preferences in the same transaction as the account.
- Passwords over BCrypt's 72-byte UTF-8 input limit are rejected explicitly.
- Unknown-account and incorrect-password responses use the same message.
- Preference updates validate IDs, region names, field types, and completion/step values. Other users cannot edit them.
- `/api/health` now queries the DB rather than only reporting that the web process is alive.

## Verification

- Backend: 28 passing tests using isolated H2. Includes session/CSRF/ownership checks, signup persistence, draft/completion persistence, legacy-account preference editing, malformed inputs, and existing community/search functionality.
- Frontend: 18 passing tests; production build succeeds.
- Browser QA: isolated test backend and account, separate from real user data. Checked welcome, email form, guest browsing, guest favorite prompt, login, draft reload, regions, departure animation, automatic favorite continuation, and editable preferences.
- Viewports: 320 x 740, 390 x 844, and 1440 x 1000. Checked dark mode, horizontal overflow, loaded artwork, and radio button dimensions.
- Real local H2 was copied while the backend was stopped before migration, then copied again after migration. The main backend was restarted against the same DB.

## Before Public Deployment

- Email/phone verification and account recovery remain unconnected. Do not present an email or phone as verified merely because signup succeeded. Naver OAuth was connected in the subsequent change documented in `NAVER_LOGIN.md`.
- Real Google OAuth was preserved, but not reauthorized in this QA pass. Register the deployed domain in Google's console and test it there.
- Add authentication/signup rate limiting and review abuse controls before opening registration broadly.
- Use HTTPS, secure cookies, exact allowed origins, rotated secrets, and a production-only administrator account. Review existing demo accounts before deployment.
- Exercise migrations, constraints, and backup restore against the actual MariaDB version if MariaDB is the deployment database.
- Run external-domain, concurrency, failure/recovery, and deployment smoke tests. Local passing tests do not guarantee an error-free deployment.
- Source ZIPs and Git bundles exclude `.env` and live DB files. Store secrets separately and keep DB backups private. A local bundle is not a GitHub push.

## Local Commands

From `frontend`: `npm.cmd test` and `npm.cmd run build`.

From `backend`, with Java 17 available: `gradlew.bat test bootJar --console=plain`.

The development server defaults to the backend at `http://127.0.0.1:8080`. The server-only `SOOMGIL_API_TARGET` override is for an isolated test proxy; it is not a frontend API key.
