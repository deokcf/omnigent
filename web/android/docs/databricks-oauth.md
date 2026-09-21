# Databricks OAuth on Android

The Android app uses a public-client Databricks OAuth flow for workspace-hosted
Omnigent. It authenticates, creates the platform web session, and installs that
session into an isolated persistent WebView profile before loading the page.

Databricks Apps are a different server type and retain inline platform SSO.
Generic Omnigent servers retain the existing ticket/poll OIDC flow.

## Build configuration

Set these Gradle project properties when building the app:

| Property                     | Default                                        | Purpose                                          |
| ---------------------------- | ---------------------------------------------- | ------------------------------------------------ |
| `databricksOAuthClientId`    | Empty                                          | Registered Databricks public OAuth client ID.    |
| `databricksOAuthRedirectUrl` | `https://login.databricks.com/mobile-redirect` | Exact HTTPS callback registered for that client. |

For example:

```sh
cd web/android
./gradlew :app:assembleDebug \
  -PdatabricksOAuthClientId=your-public-client-id \
  -PdatabricksOAuthRedirectUrl=https://login.databricks.com/mobile-redirect
```

The values are public configuration, not secrets. Do not add a client secret to
the app and do not borrow a Databricks CLI client ID. An empty client ID is
accepted by Gradle so ordinary builds continue to work, but native workspace
sign-in rejects it when configuration is loaded.

The redirect must use HTTPS on the default port, contain a nonempty path, and
have no credentials, query, or fragment. Gradle projects its scheme, host, and
path into the exported, `autoVerify` callback activity's exact App Link filter.
The callback host must publish an `assetlinks.json` statement for the shipped
application ID and signing certificate, and the exact redirect must be
registered on the Databricks OAuth client. Repository configuration alone
cannot provision either external dependency.

## Browser handoff and callback

Android opens the authorization URL with an `ACTION_VIEW` browsable intent so
normal system-browser SSO and password-manager/passkey support remain available.
The app does not place tokens, authorization codes, or PKCE material in an
Android intent of its own.

Unlike iOS's authentication session, Android may destroy Omnigent while the
browser is in front. Before opening the browser, the app encrypts the short-lived
attempt—workspace scope, client configuration, state, verifier, and creation
time—with an app-owned Android Keystore key. The HTTPS callback activity restores
that attempt after process recreation, validates the callback, consumes the
record once, exchanges the code, and saves the resulting grant. Attempts expire
after ten minutes. Cancellation deletes the pending record, and wrong-state or
stale callbacks do not consume a newer attempt.

The callback activity returns only a success/error signal to `MainActivity`.
Credentials never travel through intent extras.

## Protocol boundaries

For every authorization attempt the app creates cryptographically random state
and an S256 PKCE verifier. It starts at the entered workspace origin's
`/oidc/v1/authorize` endpoint with `all-apis offline_access`. Only a single,
ASCII-decimal `o` workspace hint is forwarded; unrelated page query parameters
are not copied into the authorization request.

The callback parser requires:

- the exact configured HTTPS callback scheme, host, effective port, and path;
- exactly one matching state value;
- exactly one nonempty code, or one sanitized provider error;
- at most one supported issuer.

Provider error descriptions are never surfaced. A missing issuer falls back to
the entered workspace's `/oidc` authority. A supplied issuer must be an HTTPS
Databricks workspace `/oidc` authority or an account
`/oidc/accounts/<account-id>` authority with no userinfo, custom port, query, or
fragment.

Before exchanging a code, the client fetches
`<issuer>/.well-known/openid-configuration` and requires exact `issuer` and
`token_endpoint` values. Code and refresh requests are form encoded, use no
client secret, and do not follow redirects. Token values are treated as opaque;
issuer metadata is retained so account-issued grants refresh against the same
verified authority.

OAuth requests use a native `HttpURLConnection` transport with redirects and
caching disabled. They do not use Android WebView's cookie APIs. The callback
activity and token coordinator run them off the main thread. Tokens must never
be logged or sent to JavaScript.

## Workspace identity

A credential scope is the normalized HTTPS workspace origin, optional `o`, and
exact client ID. Host casing, paths, fragments, and explicit port 443 do not
create a second scope. Different `o` values on a shared host remain separate.
Databricks Apps, unrelated domains, duplicate `o` values, non-decimal IDs, and
lookalike hosts are rejected.

## Credentials and refresh

Versioned token records are AES-GCM encrypted in app-private preferences. The
non-exportable AES key lives in Android Keystore and is marked usable only while
the device is unlocked. Ciphertext is bound to its logical scope through GCM
associated data, preferences keys are scope hashes, and Android backup remains
disabled in the manifest. There is no plaintext preferences or file fallback.

A process-wide token manager coordinates each scope:

- grants with more than 60 seconds remaining are reused;
- callers share one refresh operation per scope;
- cancelling one caller does not cancel an issued rotation;
- a replacement refresh token is persisted before success is returned;
- only HTTP 400 `invalid_grant` clears the scope;
- transient/network/malformed responses retain the prior record;
- clear or replacement fences late refresh results;
- a failed rotated-token write is retained in memory and retried before the old,
  consumed refresh token can be loaded again.

As on iOS, an app process loss between remote refresh rotation and local durable
write can still require a fresh sign-in because those operations cannot be
atomic.

## Session bootstrap and WebView isolation

A workspace connection does not load its URL first and infer authentication from
redirects. The native coordinator obtains a current grant, then issues
`GET /auth/session/create` with the bearer on the first request only. Redirects
are followed manually for at most eight hops and must remain on supported HTTPS
Databricks workspace domains without changing an explicit workspace ID.

The operation maintains its own cookie jar. It validates cookie domain/path scope
and requires the final page to have a nonempty `DBAUTH` cookie marked Secure and
HttpOnly. Login-page landings, unsafe redirects, missing cookies, and unexpected
HTTP responses fail before a WebView loads. A cached access token rejected with
HTTP 401 is refreshed and retried once; other failures do not trigger that retry.
The intended Omnigent path, query, fragment, and workspace hint survive the
exchange.

Each credential scope maps to a stable AndroidX WebKit profile name. The WebView
is bound to that profile before its first use. Cookie installation is serialized
per profile, clears stale profile cookies, writes the validated session cookies,
flushes them, and reads `DBAUTH` back before navigation. Other server types keep
the default profile. If the installed Android System WebView does not expose the
multi-profile feature, workspace login fails with an update prompt rather than
sharing the default cookie jar.

Android's `CookieManager` cannot enumerate every stored cookie attribute after
installation. Secure/HttpOnly/domain/path properties are therefore validated on
the native `Set-Cookie` response before install; readback verifies the resulting
name/value at the final page URL.

On relaunch, a saved grant bootstraps silently. Missing credentials show an
explicit Sign In action instead of opening the browser without user input. An
explicit connection may start browser sign-in immediately. Cancellation returns
to setup with the entered workspace preserved.

## Session recovery

Authentication navigation and main-frame HTTP 401 trigger native bootstrap again
in the same profile while preserving the last accepted app route. Foregrounding
the activity checks for a missing `DBAUTH` cookie and takes the same path. HTTP
403 is a permission error and returns to setup instead of repeatedly signing in.

Recovery can silently reuse or refresh a saved grant. If the grant is missing or
revoked, the app presents an explicit Sign In action before opening the browser.
A subsequent automatic recovery requires both a successfully ready page and a
60-second cooldown; repeated failure returns to setup rather than looping.

## Local sign-out

A successfully bootstrapped workspace exposes **Sign Out of Workspace** in the
native recovery menu and `window.omnigentNative.signOut()` to the trusted main
frame. Other server types receive neither the JavaScript method nor a native
sign-out callback.

Sign-out records a non-secret pending-cleanup marker, invalidates browser login
and refresh work, clears the scoped encrypted grant, then clears that profile's
cookies and web storage. The current/default server is removed while recents are
retained for an explicit future reconnect. A later connection must finish an
interrupted cleanup before loading credentials or starting a new browser login.
Other workspace profiles are not touched.

This is local app sign-out. It does not revoke the provider grant globally or
clear system-browser/IdP SSO, so a later explicit sign-in may reuse browser SSO.
