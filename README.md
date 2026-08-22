# trading-toolkit

Multi-market quotes toolkit (US Massive, Romania BVB, Germany Xetra, Japan J-Quants). The service talks to MongoDB, initializes each enabled market’s universe and quotes at startup, then refreshes them on a nightly cron. HTTP API + UI listen on port **7777**.

## Prerequisites

- JDK 21+
- [sbt](https://www.scala-sbt.org/)
- Docker (for Mongo)
- Node.js / npm (only if you build the React UI or run `packageService`)

## Config

```bash
cp local.properties.example local.properties
```

Edit `local.properties` at the repo root:

| Key | Purpose |
|---|---|
| `mongodb.url` | Default `mongodb://127.0.0.1:27017` |
| `ttk.markets` | Comma list, e.g. `us-massive,ro-bvb,de-xetra,jp-jquants` |
| `massive.api.key` | Required if `us-massive` is enabled |
| `jquants.api.key` | Required if `jp-jquants` is enabled ([J-Quants](https://jpx-jquants.com/en/register)) |
| `jquants.data.delay.days` | Optional; default `84` (free plan ~12-week delay; use `0` on Light+) |
| `finnhub.api.key` | Required if a market has financials (`us-massive`) |
| `ttk.skip.initialization` | `true` to skip startup universe/quotes backfill |
| `ttk.skip.cron` | `true` to skip nightly jobs |

Env vars override the file (`MASSIVE_API_KEY`, `MONGODB_URL`, …).

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

From the repo root (so `local.properties` is found):

```bash
# Mongo must already be up
sbt "service/run"
```

That runs `io.greensheep.trading.service.Main`. On startup it:

1. Opens a Mongo database per enabled market (`ttk-us-massive`, `ttk-ro-bvb`, `ttk-de-xetra`, `ttk-jp-jquants`, …)
2. Initializes universes and quotes **in parallel** for every provider (unless `ttk.skip.initialization=true`)
3. Schedules nightly quote/screen crons (unless `ttk.skip.cron=true`)
4. Serves the API (and packaged web UI if present) on http://localhost:7777

Useful flags while iterating:

```properties
ttk.markets=ro-bvb
ttk.skip.initialization=true
ttk.skip.cron=true
```

### Tests

```bash
sbt test
```

Provider-specific:

```bash
sbt "jquantsClientZio/test" "xetraClientZio/test" "bvbClientZio/test" "core/test"
```

## Frontend (optional)

```bash
cd web
npm install
npm start          # http://localhost:3000, proxies API to :7777
```

The header switches markets (**US** / **RO** / **DE**) and loads that market’s SEPA watchlist + quotes from `/markets/{country}/{provider}/...`.

Or point the service at a local build:

```properties
ttk.web.root=web/build
```

## Package / deploy

```bash
sbt "service/packageService"   # React build + fat jar
sbt "service/deploy"           # jar + local.properties → Docker (local or SSH via ttk.deploy.*)
```

`scripts/package.sh` and `scripts/deploy.sh` wrap the same sbt tasks.
