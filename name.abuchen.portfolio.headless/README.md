# Portfolio Performance headless host

Serves the REST API without the desktop application: an Equinox product containing
the core model, the REST bundle, and this host. No SWT UI, no workbench.

## What it is

`RestApiAddon` in the UI bundle composes five objects at startup: a file-access
registry, a client store, a `HostApplication`, the routes, and the server. This
module does exactly the same, with only the host swapped, so **nothing in
`name.abuchen.portfolio.rest` changes**. That is the point: the module is additive,
and upstream churn cannot conflict with it beyond the three small SPI interfaces.

| Desktop provides | Headless equivalent |
| --- | --- |
| Open files (the user opened them) | `files` in the config, loaded with `ClientFactory.load` at startup |
| Which files the API may see (a checkbox) | `alias` + `enabled` written into the registry from the same config |
| A UI thread serializing model access | a single dedicated thread in `HeadlessHost.syncExec` |
| An approval dialog for pairing | pre-provisioned tokens; pairing requests are declined |
| "The user is editing" (`423`) | always false; no request is ever answered `423` |
| A change counter and dirty flag per file | the same, driven off the client's own `PropertyChangeSupport` |

The change counter has to mean exactly what `ClientInput`'s does, because it is what
every file-scoped response validates its ETag against: it moves on each model change,
loading counts as a change, and only equality is meaningful.

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
that module's pom) because it is a local service, not something that ships
installers.

```bash
Eclipse.app/Contents/MacOS/PortfolioPerformanceHeadless \
  -data /path/to/daemon-workspace \
  -ppConfig /path/to/headless.json
```

`-data` is required: the registry and the client tokens live in the Eclipse instance
area, and it must **not** be the desktop application's workspace.

On a machine with no system JVM the launcher ini needs a `-vm` line inserted before
`-vmargs`, exactly like the desktop product. `mvn clean` wipes it each build.

### Configuration

```json
{
  "port": 5712,
  "files": [
    { "path": "/home/me/portfolio.xml", "alias": "main", "passwordEnv": "PP_PASSWORD_MAIN" }
  ],
  "clients": ["webapp"]
}
```

A password is never written here, only named: the value is read from that
environment variable. A client listed in `clients` that has no token yet gets one
minted at startup, logged **once** (the store only ever returns a token's plaintext
at creation).

## One owner per file

Two instances must not own one `.portfolio` file: each keeps its own in-memory copy
and whichever saves last silently discards the other's work. The daemon refuses to
start rather than let that happen. Three guards, in the order they fire:

1. **The port.** Something already listening on the configured port is almost
   certainly another instance (the desktop with its API enabled, or another daemon).
   Checked before anything is loaded, because the bind failure further down says far
   less.
2. **The file** (`PortfolioFileLock`). An exclusive OS lock on a sidecar
   `<file>.lock`, held for the process lifetime and claimed *before* the file is
   read. The refusal names the holder: `held by pid=40854 port=5713 since=...`.
   Because it is an OS lock and not a PID file it cannot go stale - if the holder
   dies the kernel releases it.
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

## Status: phase 1 (spike)

Verified against a real 520-transaction file: all eleven GET collections answer
`200` with structurally identical responses to the desktop's, and the static data is
identical (520 transactions, 44 instruments with matching names, the same three
taxonomies).

The three ownership guards above are in and verified.

**Not done yet, and it matters:**

- **No exchange rates.** This is the big one. A fresh daemon workspace has no ECB
  rate data and there is no refresh loop, so `ExchangeRateProviderFactory` falls back
  to rates embedded in the file. Measured against the same file: the daemon reported
  USD to CHF as `0.9937` where the desktop, with current ECB rates, reported `0.8101`
  - so **every converted figure is wrong**, and by a lot. Anything that crosses
  currencies (holdings valuation, performance, the allocation pie) cannot be trusted
  until the refresh loop exists. Unconverted, single-currency data is fine.
- **No quote updates.** Prices are whatever the file was last saved with.
- **No autosave.** The `PATCH`/`DELETE` routes mutate the in-memory client and
  nothing persists it, so a write is lost on exit. The desktop relies on the user
  saving; headless has to do it itself.
