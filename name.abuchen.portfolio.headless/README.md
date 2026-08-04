# Portfolio Performance headless host

Serves the REST API without the desktop application: an Equinox product containing
the core model, the REST bundle, and this host. No SWT UI, no workbench.

## What it is

`RestApiAddon` in the UI bundle composes five objects at startup: a file-access
registry, a client store, a `HostApplication`, the routes, and the server. This
module does exactly the same, with only the host swapped, so **nothing in
`name.abuchen.portfolio.rest` changes** except one added constructor overload (the
HTTP worker pool, below). That is the point: the module is additive, and upstream
churn cannot conflict with it beyond the three small SPI interfaces.

| Desktop provides | Headless equivalent |
| --- | --- |
| Open files (the user opened them) | `FileSet`: seeded from `files` in the config, changed at runtime over the control API |
| Opening, creating, duplicating, closing a file (menu commands) | `POST`/`DELETE /files` on the control port |
| Which files the API may see (a checkbox) | every file in the `FileSet` is registry-enabled while it is open |
| A UI thread serializing model access | a single dedicated thread in `HeadlessHost.syncExec` |
| An approval dialog for pairing | pre-provisioned tokens; pairing requests are declined |
| "The user is editing" (`423`) | always false; no request is ever answered `423` |
| A change counter and dirty flag per file | the same, driven off the client's own `PropertyChangeSupport` |
| A user who saves | `AutoSaver`, sweeping dirty files on an interval |
| `StartupAddon`'s exchange-rate job | `ExchangeRateRefresher`, blocking startup for the first download |
| `UpdatePricesJob` (imports SWT) | `QuoteRefresher`, same feeds, results applied on the model thread |
| Nothing | `ControlServer` - `GET /health` and the file routes, on a port of its own |

The change counter has to mean exactly what `ClientInput`'s does, because it is what
every file-scoped response validates its ETag against: it moves on each model change,
loading counts as a change, and only equality is meaningful. Dirtiness is derived
from it rather than kept as a flag, which is what makes "a change arrived while the
save was running" resolve to still-dirty instead of to a lost edit.

`ExchangeRateRefresher` bumps the counter too, without making the file dirty: new
rates change every converted figure in every cached response, and nothing in the file
changed. The desktop does not do this, and arguably owes it.

## Running it

```bash
mvn -f portfolio-app/pom.xml clean verify -Plocal-dev,headless \
  -pl :portfolio-target-definition,:name.abuchen.portfolio.pdfbox1,:name.abuchen.portfolio.pdfbox3,\
:name.abuchen.portfolio,:name.abuchen.portfolio.rest,:name.abuchen.portfolio.headless,:portfolio.headless.product \
  -DskipTests
```

The product lands under
`portfolio-headless-product/target/products/name.abuchen.portfolio.headless.product/<os>/<ws>/<arch>/`.
It is built for one environment only (the `headless.os`/`ws`/`arch` properties in
that module's pom, overridable with `-Dheadless.os=...`) because it is a local
service, not something that ships installers.

```bash
Eclipse.app/Contents/MacOS/PortfolioPerformanceHeadless \
  -vm "$JAVA_HOME/lib/libjli.dylib" \
  -data /path/to/daemon-workspace \
  -ppConfig /path/to/headless.json
```

`-data` is required: the registry, the client tokens and the downloaded exchange
rates live in the Eclipse instance area, and it must **not** be the desktop
application's workspace.

On a machine with no system JVM, pass `-vm` on the command line as above. The
packaged launcher ini has no `-vm` entry and `mvn clean` rewrites it every build, so
patching the ini is work you have to redo; the argument is not.

In this workspace, none of that is normally typed: **the webapp's backend starts the
daemon itself** and stops it again, unless something is already serving the API port.
`../../scripts/run-headless-stack.sh` therefore only does the Maven build above and
brings up the dev servers, and `../../scripts/package-headless.sh` produces a
self-contained artifact holding the daemon, the BFF and the built frontend, whose
`start.sh` is likewise one process. The commands here are for running the daemon on
its own - comparing it against a desktop instance, or serving a file read-only.

### Configuration

```json
{
  "port": 5712,
  "healthPort": 5713,
  "workerThreads": 8,
  "files": [
    { "path": "/home/me/portfolio.xml", "alias": "main", "passwordEnv": "PP_PASSWORD_MAIN" }
  ],
  "clients": ["webapp"],
  "autosaveSeconds": 60,
  "backupOnStart": true,
  "quoteRefreshMinutes": 60,
  "exchangeRateTimeoutSeconds": 60
}
```

**Every key is optional, `files` included.** Those files are the ones the daemon opens
at startup; anything opened later over the control API is not written back here, because
in that mode the caller is what keeps the list of who owns which file and this file would
be a second, stale copy of it. A daemon serving nothing yet is a normal state - it is
what a web service in front of it looks like before any account has uploaded anything.

Every duration names its unit in its key, so a number cannot be misread, and **0
disables** each of them consistently (`healthPort` too).

| Key | Default | What it does |
| --- | --- | --- |
| `port` | 5712 | the REST API. Refuses to start if something already listens here |
| `healthPort` | `port + 1` | the control port: `GET /health` plus the file routes. A port already in use here is a warning, not a refusal |
| `workerThreads` | 8 | HTTP workers. The desktop fixes this at 2, which queues everything behind one slow calculation |
| `autosaveSeconds` | 60 | sweep interval for dirty files, and the bound on work at risk |
| `backupOnStart` | true | one `<name>.backup-after-open.<ext>` copy of each file as it is opened, before the daemon writes to it |
| `quoteRefreshMinutes` | 60 | price update cadence; the first cycle runs at startup |
| `exchangeRateTimeoutSeconds` | 60 | how long startup waits for the first rate download before serving anyway |

A password is never written here, only named: the value is read from that
environment variable, wiped after the load, and never kept. An encrypted file is
re-encrypted from the key `ClientFactory.load` leaves on the `Client`, so autosave
needs nothing further - and that is also what lets a file opened over the control
port with a password supplied once keep saving without it.

A client listed in `clients` that has no token yet gets one minted at startup, logged
**once** (the store only ever returns a token's plaintext at creation).

## Exchange rates are a correctness problem, not a nicety

A fresh daemon workspace has no stored ECB data, so `ExchangeRateProviderFactory`
falls back to the rates embedded in the portfolio file - which are as old as the file.
Phase 1, without a refresh loop, reported USD to CHF as `0.9937` where the desktop
reported `0.8101`: **every converted figure was wrong, and by a lot.**

So the first download happens **before the server binds its port**, blocking for up to
`exchangeRateTimeoutSeconds`. Serving a converted figure computed from stale rates is
worse than starting a minute later, and a client has no way to tell the two apart. A
timeout or a failure logs and continues: an offline machine must still be able to serve
a single-currency file, where none of this matters. `/health` reports
`exchangeRates.lastUpdate`, and a daemon that has never managed one reads `degraded`.

After that the cadence is the desktop's, for the desktop's reason: the ECB reference
rates are updated around 16:00 CET on working days, so the next attempt is 17:00 CET
or six hours from now, whichever is sooner. Rates are saved to the instance area on
every successful update and on shutdown, so a restart does not re-download them and an
offline restart still has yesterday's.

## Quotes

`QuoteRefresher` is `UpdatePricesJob` without the Eclipse `Job` and the SWT progress
reporting: the same feeds, the same grouping criterion (so requests to one host stay
serialized while different hosts run in parallel), the same historical update policies,
the same rate-limit backoff, and the same permanent-error marking. Two differences:

- **Results are applied on the model thread.** The desktop mutates securities straight
  from a background job; here an HTTP worker may be halfway through a performance
  calculation on the same model, so the fetch happens off the model thread and only
  the write is marshalled onto it.
- **Nothing prompts for authentication.** The built-in Portfolio Performance feed
  needs a signed-in account for most instruments, which a daemon cannot provide; that
  group is skipped with one log line per cycle, and `/health` reports it under
  `quotes.error`. Every other feed works.

A cycle that downloads the same prices again does not mark the file dirty, or an
untouched file would be rewritten every hour for ever.

A cycle can also be asked for: `POST /quotes/refresh` on the control port queues one
and answers 202 immediately (the control server has one thread, and holding the
request open for a minute would block `/health` behind it); the caller polls
`GET /quotes` until `running` turns false. That status also reports, per instrument,
what could not be updated in the last completed cycle - the feed needing a signed-in
account, a rate limit, a feed that answered errors and no prices - so a client can
show *which* prices are stale rather than a single error string. `/health` keeps only
the one-line summary.

## Saving

The desktop's rule is "only the user saves", which it can afford because there is a
user watching an unsaved-changes marker. Here a write that is not persisted is lost on
exit, which is what phase 1 did.

`AutoSaver` sweeps the open files every `autosaveSeconds` and saves the dirty ones - a
sweep rather than a timer armed on each change, because a quote refresh fires thousands
of property changes in a few seconds. The save runs **on the model thread**, blocking
API requests for its duration; that is deliberate and matches the desktop, which saves
on the UI thread, because a serializer walking a model another thread is editing
produces a file that is subtly wrong. A failed save leaves the file dirty so the next
sweep retries, and shows up in `/health` as `saveError`.

`stop()` flushes whatever is still dirty, and a JVM shutdown hook calls it, so a
`SIGTERM` does not cost the last interval of work. **Never `kill -9` the daemon.**

## The control port

`ControlServer` binds `healthPort`, loopback only, and serves two things that do not
belong on the REST API: liveness, and the file administration a web service in front of
this daemon needs. Neither belongs in `openapi.yaml`, which describes the desktop's API
too - a probe cannot present a bearer token, and the desktop has no equivalent of "open
this path as a file" because a user opens files by hand.

```
GET    /health                    no token; liveness, plus what is degraded
GET    /files                     token; what is open, with each file's id
POST   /files                     token; open, create or copy one
DELETE /files/{id}[?save=false]   token; save, release and forget one
GET    /quotes                    token; the last refresh cycle: when, what failed
POST   /quotes/refresh            token; queue a refresh cycle now, answered 202
```

An encrypted file takes a `password` in the body, and the two answers a caller acts
on differently are separate statuses: **`401 password-required`** when it needs one
and **`403 password-rejected`** when the one given was wrong. Both are typed
exceptions rather than the loader's own message, which is localized and says the same
thing for a corrupt file.

**The file routes take the same bearer token the REST API takes**, and `/health`
deliberately does not. Opening and deleting files is not something anything that can
reach the port may do; the token is what an operator has to hand out to have an API
client at all, so there is one set of credentials rather than two.

### GET /health

```json
{
  "status": "ok",
  "uptimeSeconds": 26,
  "apiPort": 5712,
  "exchangeRates": { "lastUpdate": "2026-08-01T13:28:51Z", "error": null },
  "quotes": { "lastUpdate": null, "error": null },
  "files": [{ "label": "main", "dirty": false, "changeCount": 2,
              "lastSave": "2026-08-01T13:30:02Z", "saveError": null }]
}
```

**It answers 200 while the process is serving, even when something is wrong.** Being
offline, or failing to save, is reported as `"status": "degraded"`; neither is fixed by
a restart, and the daemon is still answering every request correctly from the data it
has. Reserve a non-200 for "this process cannot serve", which here means not answering.
`dirty` is the normal state between two sweeps and is reported without being a failure,
and so is an empty `files` array.

### The file routes

```bash
# open a file that exists
curl -H "Authorization: Bearer $TOKEN" -d '{"path":"/srv/pp/acct/main.portfolio"}' :5713/files

# create an empty one, in the desktop's own default format for a new file
curl -H "Authorization: Bearer $TOKEN" \
     -d '{"path":"/srv/pp/acct/new.portfolio","create":true,"baseCurrency":"CHF"}' :5713/files

# duplicate an open one, unsaved changes included
curl -H "Authorization: Bearer $TOKEN" \
     -d '{"path":"/srv/pp/acct/copy.portfolio","copyOf":"<id>"}' :5713/files

# give one up: saved if dirty, unlocked, and forgotten
curl -X DELETE -H "Authorization: Bearer $TOKEN" :5713/files/<id>
```

The `id` in the response is the registry uuid, which is what `GET /v1/files` reports and
what every REST path addresses the file by - so a caller stores that and needs to know
nothing else about where the file lives.

Five properties worth preserving, each of which cost a decision:

- **An open is idempotent.** A file already open answers `200` with `"created": false`
  rather than being loaded a second time into a second in-memory copy. That is what lets
  a caller re-assert its whole list of files on every start without tracking what the
  daemon already has - which is how the BFF recovers from a daemon restart.
- **A copy is written from the live model on the model thread**, not copied on disk, so
  the source's unsaved changes are part of what is duplicated. It uses `exportAs`, not
  `saveAs`: `saveAs` adopts the flags it wrote onto the *source* client, which would
  quietly change the format the original is saved in from then on. A copy of an
  **encrypted** source is encrypted too, with the password the caller supplies - the
  source's own is not kept after the load, and writing the copy in the clear would drop
  a file's protection on the user's behalf. A password on a plain file is ignored: a copy
  is a copy, not a chance to add encryption the original does not have.
- **A close saves, unless the caller says not to.** `?save=false` is for a caller that
  is about to delete the file: writing it one last time first is work whose only effect
  is a longer window in which the file exists.
- **Closing releases the file lock and forgets the registry record.** One owner per file
  still holds for files opened at runtime, and a workspace that accumulated a record per
  file ever served would be one nobody could read. A path that comes back therefore
  comes back with a new id.
- **A failed load leaves nothing behind.** The lock is claimed before the file is read
  and released if the load throws, or the next attempt would be refused for a reason
  that has nothing to do with why the first one failed. `404` for nothing at that path,
  `422` for something that is a file but not a portfolio file, `409` for a file another
  instance owns.

**Deleting the file itself is the caller's job**, not this daemon's: it owns the
directory layout, and a close leaves a file that is complete and openable. What the
daemon owns is that the file is flushed and unlocked first.

## One owner per file

Two instances must not own one `.portfolio` file: each keeps its own in-memory copy
and whichever saves last silently discards the other's work. **Now that the daemon
saves by itself, this is no longer a theoretical risk.** The daemon refuses to start,
or refuses the file, rather than let it happen. Three guards, in the order they fire:

1. **The port.** Something already listening on the configured port is almost
   certainly another instance (the desktop with its API enabled, or another daemon).
   Checked before anything is loaded, because the bind failure further down says far
   less.
2. **The file** (`PortfolioFileLock`). An exclusive OS lock on a sidecar
   `<file>.lock`, held for as long as the file is open and claimed *before* the file is
   read - including for a file opened at runtime, which is answered `409` if another
   instance holds it. The refusal names the holder: `held by pid=40854 port=5713 since=...`.
   Because it is an OS lock and not a PID file it cannot go stale - if the holder
   dies the kernel releases it. The sidecar itself is left behind on exit, which is
   harmless: the lock is what excludes, not the file's existence.
3. **The workspace.** Eclipse's own instance-area lock, inherited by requiring
   `-data`; two daemons cannot share one workspace. Not something this module
   implements.

All three are verified to fire.

**The gap, stated plainly:** an advisory lock only excludes processes that also take
it, and the desktop application does not take this one. So guard 2 makes two
*daemons* on one file impossible, but a desktop instance holding the same file is
only caught by guard 1, and only when both use the same port. Closing that properly
means having the desktop claim the same sidecar when a `ClientInput` opens a file -
a small change, but on the desktop side, and not done here.

## Status

Phases 1 to 3 of the plan this was built from are done. The daemon serves every GET
endpoint with responses matching the desktop's, keeps its rates and quotes current,
persists what the API writes, reports its own health, and ships as one artifact
alongside the BFF (`../../scripts/package-headless.sh`).

Since then the set of open files became **mutable at runtime** (`FileSet` plus the
control port's file routes), which is what lets the BFF give each account its own
portfolio files and let it upload, duplicate and delete them. The daemon holds no notion
of an account: it owns files, one lock each, and answers by id. Who may see which file is
the BFF's to enforce, and deliberately so - putting tenancy in here would mean a second
identity model next to the one that already has the sessions and the passwords.

Verified against the real 520-transaction file with a desktop instance running
alongside on its own port, both queried in one request batch so prices could not drift
between them:

| | desktop | daemon |
| --- | --- | --- |
| eleven GET collections | 200 | 200, same shape, same counts (520 transactions, 44 instruments, 66 trades) |
| implied USD to CHF | 0.812478 | 0.812478 |
| TTWROR, YTD, CHF | 0.113708 | 0.113706 |
| volatility / semi-deviation | 0.122081 / 0.086941 | identical |

The 0.0002 percentage point TTWROR difference is the handful of instruments whose
latest quote comes from the authenticated built-in feed. Before the quote refresh ran
it was 0.0032 pp, which is what that gap costs.

Autosave, the shutdown flush, the startup backup and both refresh loops were verified
on copies of that file rather than on it, in both the protobuf and the XML format.

### One finding worth carrying forward

**A minimal product needs `org.apache.servicemix.bundles.xpp3` listed explicitly.**
Nothing declares it as a requirement - the servicemix XStream bundle imports
`org.xmlpull.v1` optionally - so `autoIncludeRequirements` does not pull it in, and
without it every XML format (plain, compressed and encrypted) fails to load *and* to
save with `NoClassDefFoundError: org/xmlpull/v1/XmlPullParserException`. Phase 1 never
noticed, because the file it was verified against is the protobuf format and it never
saved anything. The desktop feature lists it for the same reason. Its absence in the
test fragment is how it was found.

### Not done

- **The desktop does not take the sidecar lock** (see above), so daemon-vs-desktop on
  one file is only caught when they share a port.
- **Feeds needing a signed-in Portfolio Performance account** cannot be used headless.
- **No container image.** The product pom cross-builds (`-Dheadless.os=linux
  -Dheadless.ws=gtk -Dheadless.arch=x86_64`), so the remaining work is a Linux JRE in
  the image and a Linux build machine. The single-artifact packaging already delivers
  "no desktop application required", which is what phase 3 was for.
- **No plain-jar build** (the plan's optional phase 4). It was contingent on the
  Equinox product proving operationally awkward, and it has not: one directory, one
  `start.sh`, two health endpoints.
