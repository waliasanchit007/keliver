# Secure HTTP recording (H2)

**Date:** 2026-07-27
**Roadmap:** item #16, final recording boundary
**Status:** implementation-ready
**Predecessor:** `2026-07-27-recorded-http-replay-design.md`

## 0. Decision

H2 is an explicit, local, candidate-only capture workflow:

1. the relay starts with `PORTAL_HTTP_RECORD=1`;
2. app config maps a short upstream ID to one reviewed HTTPS base URL;
3. the relay generates a cryptographic per-process token and writes it
   owner-readable to the portal store;
4. only loopback callers with that token may create/use a recording session;
5. the caller sends only a portable relative `HostHttpRequest`;
6. the relay injects environment-owned auth, performs one bounded request
   against the configured upstream, redacts in memory, privacy-lints, and
   atomically writes a candidate fixture;
7. the response returned to the caller is the redacted response; and
8. no endpoint promotes or overwrites a reviewed fixture.

Recording is disabled unless both the environment gate and a non-empty app
recording config are present. Replay remains available and unchanged when H2 is
disabled.

This follows the allowlist case in the OWASP SSRF guidance: callers select a
server-owned ID, never a URL; redirects are disabled; A and AAAA results are
validated; and the exact validated addresses are supplied to the network client
to close the resolve/validate/connect rebinding gap.

## 1. Non-goals

H2 v1 does not:

- proxy arbitrary URLs, schemes, hosts, or ports supplied by a browser;
- record from Android/iOS devices or any non-loopback peer;
- follow redirects;
- support HTTP, WebSockets, streaming, multipart, or binary bodies;
- persist or return injected auth;
- automatically replace a reviewed fixture;
- infer secrets from Gradle, browser storage, or app source;
- promise that regex redaction alone proves a fixture contains no private data;
  or
- turn a replay miss into a live request.

The workflow is intentionally two-step: record candidate, then human review and
promotion in git.

## 2. App configuration

`keliver.portal.json` may declare:

```json
"httpRecording": {
  "allowedOrigins": [
    "http://localhost:8096",
    "http://127.0.0.1:8096"
  ],
  "fixtureTtlDays": 30,
  "upstreams": {
    "profile-api": {
      "baseUrl": "https://api.example.com/v1/",
      "allowedMethods": ["GET", "POST"],
      "forwardHeaders": ["accept", "content-type"],
      "authEnv": "PROFILE_API_TOKEN",
      "authHeader": "Authorization",
      "authPrefix": "Bearer "
    }
  }
}
```

Rules:

- upstream IDs use the existing safe-name grammar;
- `baseUrl` must be an absolute HTTPS URL with a DNS hostname, no user-info,
  query, or fragment;
- URL ports are fixed by config, never by the request;
- IP-literal hosts are rejected in v1;
- methods and forwarded headers are explicit allowlists;
- `authorization`, proxy auth, cookies, host, forwarding headers, and
  hop-by-hop headers can never be forwarded from the caller;
- auth is optional, but if configured its environment variable must exist when
  recording starts;
- auth header names/prefixes come from reviewed config, while values come only
  from the environment; and
- allowed browser origins are exact origins. An absent `Origin` is accepted for
  token-authenticated CLI use; a present origin must match.

Normal config parsing never reads auth environment values and metadata
endpoints never serialize them.

## 3. Startup gate and token

`HttpRecordingService` is created only when:

```text
PORTAL_HTTP_RECORD=1
```

and `httpRecording.upstreams` is non-empty. Otherwise every recording endpoint
returns 404 so an attacker cannot distinguish configuration from disabled
recording.

At each enabled startup the relay generates 32 random bytes, stores the
base64url token at:

```text
<store>/http-record.token
```

and applies owner read/write permissions where the platform supports POSIX
permissions. The file is atomically replaced on every process start. The token
is never logged; the startup banner prints only the token-file path.

Recording requests provide:

```text
X-Portal-Record-Token: <token>
```

Comparison is constant-time. Missing/invalid tokens receive 404, not 401.

## 4. Endpoint and session model

All `/http-record/*` handlers first require:

- recording enabled;
- `exchange.remoteAddress.address.isLoopbackAddress`;
- a valid per-process token; and
- an allowed `Origin` when one is present.

Endpoints:

```text
POST /http-record/sessions
  body: {"upstream":"profile-api","fixtureSet":"field-researcher"}
  -> 201 {"session":"...","candidate":"..."}

POST /http-record?session=<opaque-id>
  body: HostHttpRequest
  -> 200 HostHttpResponse (redacted)

POST /http-record/close?session=<opaque-id>
  -> 200 candidate descriptor + reviewed-fixture comparison summary
```

Session IDs are 128-bit random opaque values. Sessions are in-memory, limited to
32, expire after 15 minutes idle, and hold:

- configured upstream ID;
- candidate filename;
- creation/last-access times;
- ordered redacted entries; and
- cumulative request/response bytes.

Candidates are written under:

```text
<httpFixturesDir>/.candidates/
```

using `<fixtureSet>-<UTC timestamp>-<random suffix>.json`. Names never derive
from URL/path data. Each successful exchange atomically rewrites the complete
candidate, so a crash leaves either the previous valid file or the new one.

Closing validates the final candidate and freezes the session. It reports entry
count, revision, expiry, and whether a reviewed fixture with that ID exists. It
does not expose bodies and cannot promote.

## 5. URL construction and SSRF boundary

The request supplies a relative path and query map only.

Before constructing the target:

- path must begin with exactly one `/`;
- it must contain no backslash, control character, percent-encoded slash or
  backslash, empty authority form, or `.`/`..` segment after decoding;
- query keys/values are bounded text and are added through the URL builder; and
- the final scheme, host, and port must exactly equal configured base values.

For each call the recorder:

1. resolves all A/AAAA results with the system resolver;
2. rejects an empty result or any any-local, loopback, link-local, site-local,
   multicast, IPv4-mapped private/local, or other non-global address;
3. supplies that exact immutable address list through OkHttp's `Dns` interface;
4. disables redirects, SSL redirects, proxies, cookies, authenticators, and
   retry-on-connection-failure; and
5. retains normal TLS hostname and certificate verification against the
   configured hostname.

The v1 recorder cannot opt into private addresses. Teams needing an internal
API must expose a dedicated safe recording gateway or extend this design with a
separately reviewed network boundary.

## 6. Request/response bounds

- Methods: configured subset of GET/POST/PUT/PATCH/DELETE; no CONNECT/TRACE.
- Caller body: UTF-8 text, at most 1 MiB.
- Upstream response: read at most 1 MiB plus one byte, then cancel/fail 413.
- Connect timeout: 5 seconds.
- Read/write timeout: 10 seconds.
- Whole-call timeout: 15 seconds.
- Response headers: only `content-type` and configured non-sensitive safe
  headers, with cumulative output capped at 32 KiB.
- No compression request header is forwarded; the candidate stores decoded
  text returned by the client.
- Non-2xx responses are still valid recorded responses, subject to the same
  bounds and privacy lint.
- Redirect responses are returned as 3xx records with `location` omitted; they
  are never followed.

## 7. Redaction-before-observation

Raw upstream bytes exist only in bounded local variables long enough to parse
and redact. Before hashing, logging, serialization, or returning to the caller:

- all sensitive request headers are dropped;
- injected auth is never added to the portable request object;
- sensitive query values become `"<redacted>"`;
- JSON object values whose keys match the sensitive-key policy become
  `"<redacted>"`, recursively;
- response `set-cookie` and all sensitive/hop-by-hop headers are dropped; and
- non-JSON bodies are rejected when app config declares required redaction keys,
  because safe structural redaction is impossible.

The default sensitive-key policy covers token, secret, password, session,
authorization, api-key variants, email, phone, account, card, ssn, and dob.
Apps may add exact lowercase keys, but cannot remove defaults.

Redaction runs before the candidate revision/hash and audit event are computed.
The existing replay privacy lint runs against every candidate immediately
before its atomic move.

## 8. Redacted request matching

H1 currently permits `"<redacted>"` in sensitive query/JSON fields but compares
requests literally. H2 makes the intended behavior explicit:

- a fixture `"<redacted>"` value is a wildcard only when its containing key is
  sensitive;
- at replay time the runtime request is normalized by replacing the same
  sensitive-key values before canonical matching;
- non-sensitive literal `"<redacted>"` remains literal;
- wildcard values are never included in errors, logs, cursor keys, or fidelity
  text; and
- duplicate sequencing still keys by the redacted canonical request.

This lets a checked-in fixture match a real token-bearing request without
persisting or comparing the token itself.

## 9. Candidate review and promotion

The recorder never edits `<httpFixturesDir>/<fixtureSet>.json`.

The close response and CLI print:

- candidate repository-relative path;
- entry count, revision, recorded/expiry times;
- method plus path hash for each exchange;
- status and byte counts; and
- a command that runs the existing privacy lint and a normal text diff.

Promotion is a deliberate filesystem/git action by the developer after review.
There is no browser endpoint for it in H2 v1.

## 10. Audit

Append one JSON line per attempted exchange to:

```text
<store>/http-record-audit.jsonl
```

Fields are limited to:

- UTC timestamp;
- random session ID suffix;
- upstream ID;
- method;
- SHA-256 of redacted path (not the path);
- outcome category/status;
- request and response byte counts;
- duration bucket; and
- candidate basename after a successful atomic write.

No URL, query, header, body, DNS address, auth env name/value, exception
message, or token is logged. Audit appends are bounded and failures do not
weaken the candidate privacy lint.

## 11. Failure behavior

Stable categories:

- disabled/remote/token/origin failure: 404;
- malformed command/request/path: 400;
- unknown upstream/session: 404;
- method not allowed: 405;
- missing auth environment: 424;
- DNS/non-global-address/redirect policy failure: 422;
- request/response too large: 413;
- connect/read/call timeout: 504;
- upstream/network failure: 502; and
- privacy-lint or atomic-write failure: 422.

Responses contain category, upstream ID where safe, method, and reason code.
They never contain target URL, resolved address, header, query, body, raw
exception message, or environment name.

## 12. Implementation slices

### H2a — policy and candidate engine

- config schema and validation;
- startup gate/token;
- loopback/origin/token guard;
- session/candidate state;
- URL/path/DNS policy;
- redactor, wildcard replay normalization, privacy lint reuse; and
- hermetic tests using an injected transport/resolver.

### H2b — bounded network transport

- OkHttp client with pinned validated DNS;
- no redirect/proxy/cookie/authenticator/retry behavior;
- environment auth injection;
- bounded request/response conversion; and
- body-free audit.

### H2c — local workflow

- record/close endpoints;
- a CLI helper that reads the token file without printing it;
- candidate lint/diff guidance;
- one controlled record → close → manual-review-copy → replay dogfood; and
- roadmap/current-state evidence.

Do not add editor automatic fallback from replay to record.

## 13. Acceptance gates

1. Config tests reject HTTP, IP literals, user-info/query/fragment, unsafe
   methods/headers, duplicate origins, and invalid TTL.
2. Guard tests cover disabled, remote, missing/wrong token, disallowed origin,
   and token rotation.
3. URL tests cover authority forms, encoded traversal/separators, backslashes,
   control characters, query encoding, and fixed origin.
4. DNS tests cover all IPv4/IPv6 local/private/link/multicast forms and prove
   the transport receives only the validated immutable result.
5. Transport tests prove redirects/proxies/cookies/retries are disabled and
   enforce all byte/time bounds.
6. Redaction tests prove secrets never appear in candidate, response, revision
   input, audit, or errors; sensitive wildcard replay still matches.
7. Candidate tests prove atomic append, session ordering/isolation/expiry,
   close/freeze, capacity bounds, and no reviewed-fixture overwrite.
8. Relay integration tests exercise create → record → close with an injected
   fake transport and stable status categories.
9. Existing H1, editor, app-lib, API, Android, and iOS build gates remain green.
10. A hermetic controlled-upstream integration completes record → candidate
    review → explicit promotion → deterministic replay without any automatic
    live fallback; production policy still rejects loopback/private addresses.

## 14. Remaining trust statement

H2 reduces the recording trust boundary; it does not remove it. The developer
who enables recording authorizes an outbound request to a reviewed app-owned
upstream and must review the candidate before promotion. The default relay
remains replay-only and has no outbound behavior.

## 15. Security references

- [OWASP Server Side Request Forgery Prevention Cheat
  Sheet](https://cheatsheetseries.owasp.org/cheatsheets/Server_Side_Request_Forgery_Prevention_Cheat_Sheet.html)
  — server-owned allowlists, A/AAAA validation, DNS pinning defenses, and no
  redirects.
- [OkHttp `Dns`
  contract](https://square.github.io/okhttp/5.x/okhttp/okhttp3/-dns/)
  — supplies the exact addresses attempted for a hostname, allowing validated
  address pinning without replacing TLS hostname verification.
- [JDK 17 `InetAddress`
  API](https://docs.oracle.com/en/java/javase/17/docs/api/java.base/java/net/InetAddress.html)
  — address classification and system A/AAAA resolution primitives.
