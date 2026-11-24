# Metadata.Dummy: real logins (B-001, ported to 2026.10)

Upstream `Metadata.Dummy` treats every login as valid and every user as ADMIN.
On OpenEMS 2026.10 that shows up in three places: `getUserByExternalId()`
creates an ADMIN user for any login it is given, `getUserRole()` returns ADMIN,
and edge metadata reports ADMIN. On top of that, 2026.10 moved password checks
into a separate `AuthUserPasswordAuthenticationService`, which only the Odoo
and Keycloak integrations provide. A Dummy-only Backend has no password
service at all.

This bundle closes both gaps for single-site deployments without Odoo or
Keycloak.

## What changed

- `MetadataDummy` also provides `AuthUserPasswordAuthenticationService`, the
  same way `Metadata.Odoo` does. Passwords are checked against a JSON users
  file (`usersPath`) with PBKDF2-HMAC-SHA256 (`PasswordHash`, JDK only, at
  least 100,000 iterations; new entries use 210,000).
- Unknown user, wrong password, missing or malformed file: all return
  `COMMON_AUTHENTICATION_FAILED`. An unknown username costs the same PBKDF2
  time as a known one, so neither the error nor the timing reveals which
  usernames exist.
- Successful logins get a random 256-bit token (`TokenStore`), valid until
  logout, `tokenLifetimeMinutes` (default 1440), or a Backend restart.
- `getUserByExternalId()` only knows users in the file. Roles come from the
  file in `getUserByExternalId()`, `getUserRole()` and edge metadata.
  `MetadataUtils.getPageDevice()` now reports the user's role instead of
  ADMIN.
- The file is re-read when it changes. Removing a user revokes their tokens
  and drops open sessions to GUEST. Changing a role applies to open sessions.
  No restart needed.
- Edge registration and API keys are untouched.

## Deploying

1. Create one entry per user with the bundled tool. Never write a hash by hand:
   ```
   cd io.openems.backend.metadata.dummy/src/io/openems/backend/metadata/dummy
   javac -d /tmp/genhash PasswordHash.java GenerateUserHash.java
   java -cp /tmp/genhash io.openems.backend.metadata.dummy.GenerateUserHash \
       alice "Alice Example" owner
   ```
   It asks for the password twice (at least 12 characters) and prints a
   block to paste under `"users"`.
2. Save the file (see `users.example.json`; its placeholder entries never
   verify) where the Backend can read it, e.g. `/etc/openems/users.json`, and
   make it readable only by the Backend user.
3. Set `usersPath` in the `Metadata.Dummy` config, next to `edgeIdTemplate`
   and `edgeIdMax`. Without it, every login is rejected.

## Verification

`deploy/openems-auth-livetest/` in EMS-ADDONS builds this bundle with its
JUnit tests, starts a real 2026.10 Backend, and logs in over the UI websocket:
wrong password, unknown user, correct password with the file's role, token
re-use, logout, and removal of a user without restart.
