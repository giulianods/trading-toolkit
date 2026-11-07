# trading-toolkit

Multi-market quotes toolkit (US Massive, Romania BVB, Germany Xetra, Japan J-Quants). The service talks to MongoDB, initializes each enabled market’s universe and quotes at startup, then refreshes them on a nightly cron. HTTP API + UI listen on port **7777**.

## Develop with Docker

Install Docker with the Compose plugin (Docker Desktop on Windows). The
containers provide the JDK, Scala, sbt, Node.js, and MongoDB.

1. From the repository root, copy `local.properties.example` to
   `local.properties` if it doesn't already exist.
2. Set `ttk.markets=ro-bvb` to try the app without API keys, or keep the
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
`local.properties`, run `docker compose restart backend` to recompile and restart.

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
container. Once `local.properties` is configured, run `sbt service/run` in
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

US Massive quotes refresh at 00:30 New York time, after the Basic plan releases
the previous trading day's bars. The US screener runs after the quote refresh
and uses the latest completed quote session. Other markets use the server's
local timezone for their nightly schedules.

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

## License

This project is licensed under the [MIT License](LICENSE).
