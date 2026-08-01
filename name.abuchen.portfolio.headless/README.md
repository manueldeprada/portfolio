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
| Open files (the user opened them) | `files` in the config, loaded with `ClientFactory.load` at startup |
| Which files the API may see (a checkbox) | `alias` + `enabled` written into the registry from the same config |
| A UI thread serializing model access | a single dedicated thread in `HeadlessHost.syncExec` |
| An approval dialog for pairing | pre-provisioned tokens; pairing requests are declined |
| "The user is editing" (`423`) | always false; no request is ever answered `423` |
| A change counter and dirty flag per file | the same, driven off the client's own `PropertyChangeSupport` |
| A user who saves | `AutoSaver`, sweeping dirty files on an interval |
| `StartupAddon`'s exchange-rate job | `ExchangeRateRefresher`, blocking startup for the first download |
| `UpdatePricesJob` (imports SWT) | `QuoteRefresher`, same feeds, results applied on the model thread |
| Nothing | `HealthServer` - `GET /health` on a port of its own |

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

Only `files` is required. Every duration names its unit in its key, so a number
cannot be misread, and **0 disables** each of them consistently (`healthPort` too).

| Key | Default | What it does |
| --- | --- | --- |
| `port` | 5712 | the REST API. Refuses to start if something already listens here |
| `healthPort` | `port + 1` | `GET /health`. A port already in use here is a warning, not a refusal |
| `workerThreads` | 8 | HTTP workers. The desktop fixes this at 2, which queues everything behind one slow calculation |
| `autosaveSeconds` | 60 | sweep interval for dirty files, and the bound on work at risk |
| `backupOnStart` | true | one `<name>.backup-after-open.<ext>` copy before the daemon writes anything |
| `quoteRefreshMinutes` | 60 | price update cadence; the first cycle runs at startup |
| `exchangeRateTimeoutSeconds` | 60 | how long startup waits for the first rate download before serving anyway |

A password is never written here, only named: the value is read from that
environment variable, wiped after the load, and never kept. An encrypted file is
re-encrypted from the key `ClientFactory.load` leaves on the `Client`, so autosave
needs nothing further.

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

## Health

`GET /health` on `healthPort`, unauthenticated, loopback only, on a port of its own -
a liveness probe cannot present a bearer token, and the REST API's contract is
`openapi.yaml`, which describes the desktop's API too.

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
`dirty` is the normal state between two sweeps and is reported without being a failure.

## One owner per file

Two instances must not own one `.portfolio` file: each keeps its own in-memory copy
and whichever saves last silently discards the other's work. **Now that the daemon
saves by itself, this is no longer a theoretical risk.** The daemon refuses to start
rather than let it happen. Three guards, in the order they fire:

1. **The port.** Something already listening on the configured port is almost
   certainly another instance (the desktop with its API enabled, or another daemon).
   Checked before anything is loaded, because the bind failure further down says far
   less.
2. **The file** (`PortfolioFileLock`). An exclusive OS lock on a sidecar
   `<file>.lock`, held for the process lifetime and claimed *before* the file is
   read. The refusal names the holder: `held by pid=40854 port=5713 since=...`.
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
