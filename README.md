# trading-toolkit

Multi-market quotes toolkit (US Massive and Alpaca, Romania BVB, Germany Xetra, Japan J-Quants, Canada EODHD). The service talks to MongoDB, initializes each enabled market’s universe and quotes at startup, then refreshes them on a nightly cron. HTTP API, MCP API, and UI listen on port **7777**.

## Screens, reports and portfolio

Click the green sheep logo to open the left navigation drawer. **Screens** keeps
the existing screening dashboard. **Reports** displays agent-written Markdown
research newest first, supports browsing older publications and downloading `.md`
files, and keeps the report's `asOf` date separate from its publication time.
Raw HTML is rendered as text. **Portfolio** shows account equity, cash/buying
power, an equity curve, open positions, realized/unrealized P/L, and recorded fills.
The market selector applies to all three sections.

Reports and paper ledgers live in each market profile's MongoDB database, so US
Massive and US Alpaca remain separate. Each portfolio starts with **100,000**:
RON for Romania, USD for US profiles, CAD for Canada, EUR for Germany, and JPY for Japan. Fills use the account
currency with no currency conversion. The account is cash-only and long-only;
it records completed paper trades and does not submit broker orders. Average
cost includes buy fees; sell fees reduce realized P/L. Corporate actions and
dividends are not applied automatically.

Agents can use REST or the equivalent MCP tools:

| Method | Market-qualified path | Purpose |
|---|---|---|
| GET | `/markets/{country}/{provider}/reports?limit=50&offset=0` | Latest reports; limit 1–200, offset 0–1,000,000 |
| POST | `/markets/{country}/{provider}/reports` | Publish `{title, markdown, asOf}`; returns 201 with generated ID/publication time |
| GET | `/markets/{country}/{provider}/reports/{id}` | Read one report |
| GET | `/markets/{country}/{provider}/portfolio` | Balances, positions, daily equity history, newest-first fills |
| POST | `/markets/{country}/{provider}/portfolio/trades` | Record `{id, ticker, side, quantity, price, executedAt, fees?, notes?}` |

POST bodies use `Content-Type: application/json`. `asOf` is an ISO date and
`executedAt` an ISO timestamp with timezone. Submit trades in chronological order
using the provider's exact ticker, including exchange suffixes. Use a stable
trade ID when retrying: an identical fill returns 200 without applying it twice;
conflicting details for that ID return 409. Insufficient cash/shares, future fills,
or invalid values return 400. A concurrent-update 409 can be retried with the same
ID. Quantities, prices and fees support at most eight decimal places. Trades are
stored in ordered buckets capped at 500 fills or 4 MiB of encoded BSON, whichever
comes first. Closed buckets are immutable. A transactional balance checkpoint
avoids replaying history on every append; unique trade receipts preserve retry
safety across buckets. The old 20,000-fill storage cap is removed, but the current
portfolio endpoint still loads full history and is not an unbounded-scale API.
Open positions are capped at 10,000 and checkpoint JSON at 4 MiB (UTF-8), with
validation errors rather than oversized MongoDB writes.

Valuations use stored daily closes and expose their dates. When no close is
available on or after a ticker's last fill date, its last fill price is used and
marked `priceSource: last_fill`. History uses only fills and prices through each
UTC date. Refresh the section to see subsequent agent updates. Report writing and
portfolio decisions are performed by your external agent; the toolkit provides
storage, validation and display, and does not schedule an LLM itself.

### Migrating existing paper portfolios

Existing nonempty legacy ledgers remain readable, but new writes are blocked
until an explicit offline migration completes. Before upgrading, back up the
market database and stop **all old and new application writers**. Do not run old
and new backend versions against the same portfolio concurrently.

For each market database, using its actual database name:

```sh
docker compose run --rm --no-deps backend sbt -batch \
  "service/runMain io.greensheep.trading.service.PortfolioMigration DATABASE_NAME --old-writers-stopped"
```

The command copies fills into bounded buckets with transactional checkpoints,
verifies the copied trades and reconstructed balances, then activates the new
storage. An interrupted copy can be resumed with the same command and unchanged
legacy source. The original `portfolio/ledger` document is preserved unchanged.
That legacy document is only a pre-migration snapshot: after new fills arrive,
reverting to the old backend would lose those fills from view. Restore from a
verified backup or export/reconcile newer fills before any rollback. Never delete
legacy data or the new collections as an automatic recovery step.

### Portfolio database requirements and tests

Portfolio transactions require MongoDB configured as a replica set (or a sharded
cluster), not a standalone server. The development Compose stack starts a
single-node replica set named `ttk-rs` and waits for a writable primary. It preserves
the existing `mongo-data` volume; never use `down -v` to upgrade an existing database.
A single node supports transactions but does not provide high availability.
Replica-set member hostnames must be resolvable by the backend.

Run persistence tests against a separate, disposable replica set, without
starting the application or fetching market data:

```sh
docker compose -f compose.yaml -f compose.research-test.yaml up -d --wait research-mongo
docker compose -f compose.yaml -f compose.research-test.yaml run --rm --no-deps \
  -e RESEARCH_TEST_MONGODB_URL='mongodb://research-mongo:27017/?replicaSet=research-rs' \
  backend sbt -batch "service/testOnly *ResearchIntegrationSpec *PortfolioBucketIntegrationSpec *PortfolioTransactionSpec"
docker compose -f compose.yaml -f compose.research-test.yaml stop research-mongo
docker compose -f compose.yaml -f compose.research-test.yaml rm -f research-mongo
```

The test database uses temporary memory-backed storage and publishes no ports.
Tests create uniquely named databases and drop only those databases afterwards.
CI runs these tests before packaging. For an externally supplied disposable
replica set, set `RESEARCH_TEST_MONGODB_URL` and run the same sbt test targets.
Production database conversion and deployment are separate operator actions;
the development Compose changes do not reconfigure an existing remote server.

## Connect an agent through MCP

Start the backend using the setup below. In your agent's MCP server settings,
add a server named `trading-toolkit`, choose **Streamable HTTP**, and enter
`http://localhost:7777/mcp`. The backend serves MCP alongside REST; no separate
process or provider credentials are needed in the agent. The backend still needs
the normal market configuration and MongoDB connection.

For example, in VS Code run **MCP: Add Server**, choose **HTTP**, enter that URL,
and use `trading-toolkit` as the server ID. Enable its tools in your agent chat.
See the [VS Code MCP connection guide](https://code.visualstudio.com/docs/agent-customization/mcp-servers).
Agents running inside another container must use a reachable backend hostname,
such as `http://backend:7777/mcp` on the Compose network. A hosted agent cannot
reach your computer's `localhost`; it needs a reachable deployment URL.

MCP tools cover every REST operation in [openapi.yaml](openapi.yaml), including
health, screen choices, data freshness, quotes (JSON or CSV), financials, income
statements, watchlists and history, company descriptions, market breadth, reports,
and paper portfolios. Tools include report publishing and trade recording.
Tool names are the lowercase HTTP method followed by `_` and the path with slashes and hyphens replaced by
underscores and braces removed. Examples:

| Tool | Arguments |
|---|---|
| `get_status` | `{}` |
| `get_markets` | `{}` — configured profiles, source choices and legacy aliases |
| `get_markets_screens` | `{}` |
| `get_markets_country_provider_quotes_ticker` | `{"country":"ro","provider":"bvb","ticker":"TLV"}` |
| `get_markets_country_provider_watchlists_name` | `{"country":"us","provider":"alpaca","name":"SEPA"}` |
| `get_markets_country_provider_financials` | `{"country":"us","provider":"massive","tickers":"AAPL,MSFT","category":"annual"}` |
| `get_quotes_ticker` | `{"ticker":"AAPL","Accept":"text/csv"}` |
| `get_markets_country_provider_reports` | `{"country":"ro","provider":"bvb","limit":50}` |
| `post_markets_country_provider_reports` | `{"country":"ro","provider":"bvb","body":{"title":"Market review","markdown":"# Overview\n\nMarket research…","asOf":"2026-11-18"}}` |
| `get_markets_country_provider_portfolio` | `{"country":"us","provider":"alpaca"}` |
| `post_markets_country_provider_portfolio_trades` | `{"country":"us","provider":"alpaca","body":{"id":"agent-fill-001","ticker":"AAPL","side":"buy","quantity":10,"price":200,"executedAt":"2026-11-18T15:00:00Z","fees":1,"notes":"Initial position"}}` |

Prefer market-qualified tools: the unprefixed tools always use **US Massive**.
Discover current tool names and argument schemas with `tools/list`. Dates use
`YYYY-MM-DD`; ticker lists are comma-separated strings. Results include
`structuredContent: {"status":200,"body":...}` and the same payload as text.
The body preserves REST arrays and ticker order. CSV bodies are strings.
REST failures set `isError: true` and retain the HTTP status and response body.
Invalid tool names or arguments return JSON-RPC errors.

Try asking your agent: “List the enabled screens, then fetch the latest SEPA
watchlist for the US Alpaca market and show its quote freshness date.”

### Test the MCP connection

The endpoint implements stateless [MCP Streamable HTTP](https://modelcontextprotocol.io/specification/2025-11-25/basic/transports)
with JSON responses. It supports protocol versions `2025-11-25`, `2025-06-18`,
and `2025-03-26`. Clients initialize, send `notifications/initialized`, then
discover and call tools. Send both accepted response types and use the negotiated
version in subsequent requests. No `MCP-Session-Id` is issued; `GET` and `DELETE`
return 405 because there is no standalone SSE stream or session to terminate.

Run these commands in Bash (use `curl.exe` in Windows shells and adapt quoting):

```sh
curl -sS http://localhost:7777/mcp \
  -H 'Content-Type: application/json' \
  -H 'Accept: application/json, text/event-stream' \
  -d '{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-11-25","capabilities":{},"clientInfo":{"name":"my-agent","version":"1.0"}}}'

curl -sS http://localhost:7777/mcp \
  -H 'Content-Type: application/json' \
  -H 'Accept: application/json, text/event-stream' \
  -H 'MCP-Protocol-Version: 2025-11-25' \
  -d '{"jsonrpc":"2.0","method":"notifications/initialized"}'

curl -sS http://localhost:7777/mcp \
  -H 'Content-Type: application/json' \
  -H 'Accept: application/json, text/event-stream' \
  -H 'MCP-Protocol-Version: 2025-11-25' \
  -d '{"jsonrpc":"2.0","id":2,"method":"tools/list"}'

curl -sS http://localhost:7777/mcp \
  -H 'Content-Type: application/json' \
  -H 'Accept: application/json, text/event-stream' \
  -H 'MCP-Protocol-Version: 2025-11-25' \
  -d '{"jsonrpc":"2.0","id":3,"method":"tools/call","params":{"name":"get_markets_screens","arguments":{}}}'
```

MCP uses the same access boundary as REST: the service does not implement
authentication. Compose publishes port 7777 on loopback. For remote access,
use a private network or an authenticated HTTPS reverse proxy protecting both
interfaces. Native agents normally omit `Origin`. When it is present, MCP
allows only `http://localhost:7777`, `http://127.0.0.1:7777`, and
`http://[::1]:7777` by default. For an intentional browser-origin client, set
`TTK_MCP_ALLOWED_ORIGINS` (or `ttk.mcp.allowed.origins` in `local.conf`) to a
comma-separated list of exact origins; this replaces the defaults and does not
enable CORS. An origin allowlist is not authentication.

### Keep REST and MCP in sync

Whenever a REST route, parameter, or response changes, update `openapi.yaml`
alongside `ApiEndpoints.scala`. The build bundles that contract into the service;
`McpEndpoints.scala` generates tool descriptions and argument schemas from it and
dispatches tool calls directly through the REST app. Rebuild and restart the
backend, then reconnect the agent to refresh discovery. Existing tool names are
stable while paths remain unchanged; an explicit OpenAPI `operationId` can fix
the name when introducing a route.

`McpEndpointsSpec` checks REST route coverage against the generated catalog and
exercises every tool's dispatch. New HTTP methods, request bodies, or non-scalar
parameters require checking the MCP mapping and tests. GET and POST with JSON
object bodies (including nested objects and arrays) are supported; unsupported contracts
fail at startup rather than silently omitting tools. Run from the repository root:

```sh
sbt "service/testOnly *McpEndpointsSpec"
# Without a host JDK/sbt:
docker compose run --rm --no-deps backend sbt -batch "service/testOnly *McpEndpointsSpec"
```

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

### Configurable market profiles

`ttk.markets` is an ordered list of enabled profiles. Each profile chooses data
sources independently in `ttk.profiles`. For example:

```hocon
ttk.markets = [us-massive, us-alpaca, ro-bvb, de-xetra, jp-jquants]
ttk.profiles {
  us-massive { prices = massive, financials = finnhub, metadata = massive, listings = massive }
  us-alpaca {
    prices = alpaca
    financials = finnhub
    metadata = massive
    listings = finnhub
    aliases = [us-finnhub]
  }
  ro-bvb { prices = bvb, financials = eodhd, metadata = bvb, listings = bvb }
  de-xetra { prices = xetra, financials = none, metadata = xetra, listings = xetra }
  jp-jquants { prices = jquants, financials = none, metadata = jquants, listings = jquants }
}
```

The dashboard fetches `GET /markets` (also an MCP tool) and displays only enabled
profiles in configuration order, with labels and source details from the server.
An optional `label` overrides the generated display name. Profile IDs are storage
and routing identities, independent of provider choices. A new ID such as
`us-research` can select supported sources without adding a new catalog entry.
Invalid provider capabilities, countries and ambiguous aliases fail validation.

Prices and screen history stay in each profile's `ttk-{country}-{profile}` MongoDB
database. Use a new profile ID when changing the price provider of an existing
price history; startup rejects mixing feeds in an existing database. Financials
are shared by country and selected source: both US profiles read the same
`income-statements` cache in `ttk-us-finnhub`. Refreshes serialize cache checks and
skip reports fetched within seven days, avoiding duplicate provider downloads.
EODHD income statements for US/RO load on demand using `eodhd.api.key`; `none`
disables financial charts. Metadata comes from the selected native provider or
an existing Massive reference cache, without extra metadata API calls. Native
Alpaca metadata contains listing names rather than business narratives.

Existing databases remain intact. The `us-finnhub` route alias now resolves to
the configured Alpaca-price profile, while its existing Finnhub statement cache
continues to supply both US profiles. Unprefixed US Massive routes stay supported.
Aliases also restore saved dashboard selections. The legacy `/financials` metrics
contract remains a view of stored per-share metrics; combined income charts use
the configured source through `/income-statements/{ticker}`.

Alpaca prices use split-adjusted daily SIP bars. Its `listings` source can be
Finnhub or Massive and restricts the universe to NYSE/Nasdaq common shares;
ETFs, preferred shares and OTC listings are excluded. Credentials are required
only for selected price/listings providers and Finnhub financials. Massive
metadata can reuse a stored reference cache without a Massive API key.

Finnhub statements provide consolidated revenue, net income, net margin and EPS.
Bank net revenue can be reconstructed from net interest and total noninterest
income. Recognizable SEC currency unit IDs become ISO labels; unavailable fields
stay empty. Consecutive fiscal YTD totals are differenced into individual quarters,
including Q4 from annual minus Q3. Derived-quarter EPS stays empty because
weighted share counts can change. A failed report preserves available data and
is left due for retry; `/status` records financial failures separately from prices.

Startup and scheduled price refreshes use the configured price adapter. Startup
with initialization skipped rescreens stored data; an empty Alpaca universe still
gets its initial backfill. Financial jobs share the source cache and rate gate.
Every Finnhub call, redirect and retry waits at least 1.1 seconds; HTTP 429 holds
the gate for another minute. A cold 4,732-company cache takes at least 174 minutes
for two statement requests each, plus response time and retries. Reusing the
existing source cache avoids that backfill. Separate processes sharing a Finnhub
key must coordinate its quota. Live tests are opt-in via `FINNHUB_LIVE_TESTS=true`.

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
| `alpaca.api.key`, `alpaca.api.secret` | Required if `us-alpaca` or `us-finnhub` is enabled. Set `alpaca.paper = false` for live account keys |
| `jquants.api.key` | Required if `jp-jquants` is enabled ([J-Quants](https://jpx-jquants.com/en/register)) |
| `jquants.data.delay.days` | Optional; default `84` (free plan ~12-week delay; use `0` on Light+) |
| `finnhub.api.key` | Required for `us-massive` and `us-finnhub`. Optional for US-Alpaca financials |
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
requests for seven days in MongoDB. Annual and quarterly charts are stacked,
each combining revenue and net-income bars with a margin line on a separate
percentage axis.
Use an EODHD plan with Fundamentals access
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

`service/deployArgo` requires Node 22 and SSH key access to `gds@argo.local`
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
