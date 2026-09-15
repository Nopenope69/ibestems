# Metadata.Dummy real authentication (B-001)

Fixes the audit finding that `MetadataDummy.authenticate(username, password)` granted
`Role.ADMIN` to any username/password pair, unconditionally (see
`CRITIQUE-11-SEPT-2026-AND-WAY-FORWARD.md` S1/S2 in the EMS-ADDONS repo). This branch is
based on `61eb66ff9` (`2024.11.0`) - the commit that is actually deployed on the GCP VM
today - not on `develop`, so it can be built and rolled out as a standalone jar swap
without pulling in the (larger, separate) backend version upgrade.

## What changed

- `authenticate(String username, String password)` now looks the username up in a JSON
  file (`Config.usersPath()`), verifies the password with PBKDF2WithHmacSHA256
  (`PasswordHash.java`, JDK-only, no new dependency), and throws
  `OpenemsError.COMMON_AUTHENTICATION_FAILED` on any mismatch - same error for "no such
  user" and "wrong password", so a caller can't enumerate valid usernames.
- The Role granted on success is whatever `users.json` says for that user (admin /
  installer / owner / guest), not a hardcoded ADMIN.
- `getEdgeMetadataForUser()` also stopped hardcoding `Role.ADMIN` - it now uses the
  authenticated User's real global Role. This was the second half of the same hole: even
  a correctly-checked login was silently upgraded to ADMIN here.
- Everything about how Edges register and get API keys (`getEdgeIdForApikey`,
  `getEdgeBySetupPassword`, the edge prefill in the constructor) is untouched. That's a
  separate channel from user login and was never part of this vulnerability.
- `users.json` is re-read on every login attempt, so adding/removing a user is a file
  edit, not a Backend restart.

## Deploying

1. Copy `users.example.json` to a real path (e.g. next to the existing OpenEMS Backend
   config on the VM), rename it `users.json`, and delete the two placeholder entries.
2. Generate a real entry for each user - **do not hand-write a hash**:
   ```
   cd io.openems.backend.metadata.dummy/src
   javac io/openems/backend/metadata/dummy/PasswordHash.java \
         io/openems/backend/metadata/dummy/GenerateUserHash.java -d /tmp/genhash-out
   java -cp /tmp/genhash-out io.openems.backend.metadata.dummy.GenerateUserHash \
         <username> "<Display Name>" <admin|installer|owner|guest>
   ```
   It prompts for the password (not an argv, so it never lands in shell history or `ps`)
   and prints a ready-to-paste JSON block. Repeat per user, paste each block into
   `users.json` under `"users"`.
3. Build the bundle: `./gradlew :io.openems.backend.metadata.dummy:build`
4. Add `usersPath` to the Backend's Felix config for `Metadata.Dummy` alongside its
   existing `edgeIdTemplate`/`edgeIdMax` properties (same config mechanism, same
   `felix.cm.dir` the Edge launch already uses - see
   `feedback_edge_launch_commands` in project memory for why the full flag set matters).
5. Restart the Backend with the new jar.

## Verifying before it goes anywhere near the real VM

This was written and the crypto (`PasswordHash`) was round-trip tested standalone
(correct password verifies, wrong password rejected) in a sandbox that had a JDK but no
network and no `javac` on the machine building this - so **the OSGi bundle itself has not
been compiled or run against a live Backend yet.** Before deploying:

- `./gradlew :io.openems.backend.metadata.dummy:build` must succeed.
- Start the Backend locally with `usersPath` pointed at a test `users.json`, then:
  - `AuthenticateWithPasswordRequest` with a wrong password -> must get
    `COMMON_AUTHENTICATION_FAILED`, not a session.
  - Same request with the right password -> must get a session with the Role that
    `users.json` says, not ADMIN by default.
  - Confirm `bucket4-ui` can still log in end to end once its own credentials are in
    `users.json` (it currently ships `admin`/`admin` baked into the browser bundle per
    `siteConfig.ts` - that's B-002/B-003, separate from this fix, but this fix is what
    makes those baked-in creds start actually mattering).
- Edge registration/telemetry must keep working unchanged (this fix touches nothing on
  that path, but it's worth one real check).
