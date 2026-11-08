# trading-toolkit

Multi-market quotes toolkit (US Massive and Alpaca, Romania BVB, Germany Xetra, Japan J-Quants, Canada EODHD). The service talks to MongoDB, initializes each enabled market’s universe and quotes at startup, then refreshes them on a nightly cron. HTTP API + UI listen on port **7777**.

## Develop with Docker

Install Docker with the Compose plugin (Docker Desktop on Windows). The
containers provide the JDK, Scala, sbt, Node.js, and MongoDB.

1. For a new checkout, copy `local.conf.example` to `local.conf` in the repository root.
   If you already have `local.properties`, follow the migration notes under [Config](#config).
2. Set `ttk.markets = ["ro-bvb"]` to try the app without API keys, or keep the
   markets you want and fill in their keys using the config table below.
   Compose sets `MONGODB_URL` to use its own MongoDB container.
3. Start the stack:

   ```sh
   docker compose up --build
   ```

Open the React UI at http://localhost:3000 and select an enabled market.
The backend status is at http://localhost:7777/status. The first start
downloads dependencies and can take longer while the backend backfills quotes.

Frontend edits reload automatically. After a Scala edit or a change to
`local.conf`, run `docker compose restart backend` to recompile and restart.

```sh
docker compose run --rm --no-deps backend sbt -batch test
docker compose down
```

The test command runs the sbt suite in a separate container. MongoDB data
and dependency caches persist across `docker compose down`.

### Open in IntelliJ IDEA

With Docker Desktop running and IntelliJ IDEA's Dev Containers plugin enabled,
open `.devcontainer/devcontainer.json`. Use the gutter action **Create Dev
Container → Create Dev Container and Mount Sources**. This opens the local
checkout inside the backend container with JDK 21 and sbt. Stop any separate
`docker compose up` session first so the IDE can use the same local ports.

The Dev Container starts MongoDB and the React dev server alongside the IDE
container. Once `local.conf` is configured, run `sbt service/run` in
IntelliJ's container terminal or launch `Main` from the IDE.

For Git on a Windows checkout, run `git config --local core.autocrlf true`
in the repository if Windows Git uses CRLF. The host and container share
the repository's Git config, including the commit author. To use an SSH
remote from IntelliJ, start the Windows OpenSSH agent, add your GitHub key
with `ssh-add`, and reconnect with SSH agent forwarding enabled.

## Prerequisites for running outside Docker

- JDK 21+
- [sbt](https://www.scala-sbt.org/)
- Docker (for Mongo)
- Node.js / npm (only if you build the React UI or run `packageService`)

## Config

Create the file from the template for a new installation.

```bash
cp local.conf.example local.conf
```

Edit `local.conf` at the repo root. It uses HOCON syntax.

| Key | Purpose |
|---|---|
| `mongodb.url` | Default `mongodb://127.0.0.1:27017` |
| `ttk.markets` | List of market IDs, e.g. `["us-massive", "us-alpaca", "ro-bvb", "de-xetra", "jp-jquants"]` |
| `ttk.screens` | Ordered list of screen IDs, e.g. `["SEPA", "7_PCT_UP"]`. Defaults to all built-in screens with SEPA first. |
| `massive.api.key` | Required for US-Massive and for US-Alpaca's common-stock reference data |
| `alpaca.api.key`, `alpaca.api.secret` | Required if `us-alpaca` is enabled. Set `alpaca.paper = false` for live account keys |
| `jquants.api.key` | Required if `jp-jquants` is enabled ([J-Quants](https://jpx-jquants.com/en/register)) |
| `jquants.data.delay.days` | Optional; default `84` (free plan ~12-week delay; use `0` on Light+) |
| `finnhub.api.key` | Required for `us-massive`. Optional for US-Alpaca financials |
| `eodhd.api.key` | Required for `ca-eodhd` downloads. Optional for income statements on US and Romanian charts |
| `ttk.skip.initialization` | `true` to skip startup backfill except for a missing or empty US-Alpaca universe |
| `ttk.skip.cron` | `true` to skip nightly jobs |

Environment variables override the file. List overrides use comma-separated values,
for example `TTK_MARKETS=ro-bvb` or `TTK_SCREENS=SEPA,7_PCT_UP`.
The service looks for `local.conf` in the working directory and its parents.
Use `-Dttk.config.file=/path/to/local.conf` to select a different file.

For an existing installation, carry your values from `local.properties` into
`local.conf`. Quote strings such as credentials and MongoDB URLs, and turn
market and screen lists into HOCON arrays. The service can still read
`local.properties` when no `local.conf` is found. Deployment uses `local.conf`.

### Canadian stocks

Add `ca-eodhd` to `ttk.markets` and set `eodhd.api.key` in `local.conf` or
`EODHD_API_KEY` in the environment. To run Canada by itself, set
`TTK_MARKETS=ca-eodhd`. The full universe needs an
[EODHD Historical Data — All World plan](https://eodhd.com/help/plans) or a plan
that includes the same historical prices and splits.

The provider tracks CAD-denominated common shares on the TSX and TSX Venture.
It stores split-adjusted daily bars in `ttk-ca-eodhd` and refreshes the rolling
23-month window at startup and after trading weekdays at 00:30 Toronto time.
Each refresh uses two symbol-list requests and up to two requests per stock
for prices and splits. The dashboard market is **CA** and the API uses
exchange-qualified tickers such as `/markets/ca/eodhd/quotes/RY.TO`.
Canadian screens group listings by issuer using ISINs and company names.
Each screen date uses one listing per matched issuer, favoring the most
recently traded listing and then the highest average turnover over up to 20 sessions.

## Start MongoDB

Compose file lives under `service/docker/mongo-ttk/`.

```bash
cd service/docker/mongo-ttk
docker compose up -d mongo
```

Optional Mongo Express UI (admin / pass):

```bash
docker compose up -d mongo mongo-express
```

- Mongo: `mongodb://127.0.0.1:27017`
- Express: http://localhost:8081

Leave the DB running across restarts; only recreate the app container when deploying.

## Start from sbt

From the repo root (so `local.conf` is found):

```bash
# Mongo must already be up
sbt "service/run"
```

That runs `io.greensheep.trading.service.Main`. On startup it:

1. Opens a Mongo database per enabled market (`ttk-us-massive`, `ttk-ro-bvb`, `ttk-de-xetra`, `ttk-jp-jquants`, …)
2. Initializes universes and quotes **in parallel** for enabled providers, subject to `ttk.skip.initialization`
3. Runs the configured screens for each market, using stored quotes when startup backfill is skipped
4. Schedules nightly quote/screen crons (unless `ttk.skip.cron=true`)
5. Serves the API (and packaged web UI if present) on http://localhost:7777

The dashboard's Screen selector uses the configured order and opens the first screen.
Supported IDs are `SEPA`, `VCP`, `7_PCT_UP`, `7_PCT_DOWN`, `NEW_52_WEEK_HIGH`,
`NEW_52_WEEK_LOW`, and `NEW_IPOS`. **7%UP** includes stocks whose latest close
is at least 7% above their close five trading sessions earlier and requires at
least 16 closing prices. **7%UP** and **7% Down** both sort by the app's IBD
relative strength rank across the market, highest first.

US Massive quotes refresh at 00:30 New York time, after the Basic plan releases
the previous trading day's bars. The US screener runs after the quote refresh
and uses the latest completed quote session. Other markets use the server's
local timezone for their nightly schedules, except US-Alpaca.

US-Alpaca stores its own quotes and screens in `ttk-us-alpaca`, under
`/markets/us/alpaca/` and the **US Alpaca** dashboard tab. It refreshes at
20:30 New York time using historical SIP daily bars, after the US extended
session and the free plan's 15-minute delay. When the Alpaca universe is
missing or empty, startup launches a background backfill of 23 months of
split-adjusted quotes and three months of screens, even with
`ttk.skip.initialization=true`. The screening universe is the intersection of
active Alpaca assets listed on NYSE or Nasdaq and Massive's common-stock
reference list. Quotes come from Alpaca. Existing quotes for excluded symbols
remain in MongoDB. Alpaca startup initialization rebuilds recent watchlist and
breadth history using the current common-stock universe.

Useful flags while iterating:

```hocon
ttk.markets = ["ro-bvb"]
ttk.skip.initialization = true
ttk.skip.cron = true
```

### Tests

```bash
sbt test
```

Provider-specific:

```bash
sbt "jquantsClientZio/test" "xetraClientZio/test" "bvbClientZio/test" "core/test"
```

With Alpaca credentials in `local.conf`, run the live Alpaca tests
against Compose's MongoDB:

```sh
docker compose run --rm -e ALPACA_INTEGRATION_MONGODB_URL=mongodb://mongo:27017 backend sbt -batch "service/testOnly *AlpacaLiveIntegrationSpec"
```

The suite uses two live symbols, forces SIP pagination, and checks backfill,
repeat refresh, and HTTP responses. It creates and drops a randomly named
test database. The live suite is skipped unless
`ALPACA_INTEGRATION_MONGODB_URL` is set.

## Frontend (optional)

```bash
cd web
npm install
npm start          # http://localhost:3000, proxies API to :7777
```

The header switches markets and screens and loads the selected watchlist and quotes from `/markets/{country}/{provider}/...`.

For US and Romanian companies, the chart panel requests annual and quarterly
income statements when a card comes into view. The server caches successful
requests for seven days in MongoDB. Use an EODHD plan with Fundamentals access
for full symbol access. Missing statement fields appear as unavailable.

Or point the service at a local build:

```hocon
ttk.web.root = "web/build"
```

## Package / deploy

```bash
sbt "service/packageService"   # React build + fat jar
sbt "service/deployArgo"       # Build the current checkout and update argo
sbt "service/deploy"           # jar + local.conf → Docker (local or SSH via ttk.deploy.*)
```

`service/deployArgo` requires Node 22 and SSH key access to `gds@192.168.0.153`
from the environment running sbt. Rebuild the development container once
to pick up its bundled Node 22. The task builds the UI and assembles the
JAR without running test suites. It verifies the upload, backs up the
previous JAR, and recreates the `ttk` service in `~/ttk` with the server's
existing configuration and MongoDB. If startup fails, it restores the
previous JAR.

`scripts/package.sh` and `scripts/deploy.sh` wrap `service/packageService`
and `service/deploy` respectively. The latter copies `local.conf` to its
configured target.

## License

This project is licensed under the [MIT License](LICENSE).
