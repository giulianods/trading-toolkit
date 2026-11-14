# Working in Trading Toolkit

Use the root `build.sbt` for the Scala 3 / ZIO multi-project build. The development stack uses JDK 21, Node 22, and MongoDB 8. See [README.md](README.md) for setup and provider configuration.

## Where changes belong

- `core/` contains stock and quote types, indicator calculations, and screening rules. Test calculations here with deterministic quote series.
- Provider clients live in `*-client-zio/`. Provider orchestration, startup, and scheduled jobs live in `service/`. `dataservice/` owns MongoDB persistence and market database selection.
- The running HTTP API is wired through `service/.../services/ApiEndpoints.scala`. Document contract changes in `openapi.yaml`. Market-specific endpoints live under `/markets/{country}/{provider}/`. Preserve the unprefixed US Massive aliases when changing shared endpoints.
- Keep MCP at `/mcp` in sync whenever the REST API changes. `McpEndpoints.scala` generates tools from the bundled `openapi.yaml` and invokes the REST handlers in process. Update the contract's parameters and schemas alongside routes, extend MCP mapping for any new parameter/body types or HTTP methods, and run `service/testOnly *McpEndpointsSpec`. Keep agent connection instructions in `README.md` current.
- The React dashboard lives in `web/`. `src/api.js` owns request paths, `src/App.js` owns market and screen selection, and `src/components/ChartGrid.jsx` renders the watchlist in server order.

## Market data and screens

- Keep the full market ID when loading or storing data. US Massive and US Alpaca have separate MongoDB databases, even when their tickers overlap.
- Keep screening rules in `TrendScreens.scala` and register them in the `ScreenerServices.scala` catalog. The configured `ttk.screens` order drives generation and the options returned by `GET /markets/screens`. Preserve existing screen IDs because they identify saved watchlists. Use a separate display label.
- When changing screen generation, check startup, startup with backfill skipped, scheduled refreshes, and historical snapshots. Use only bars on or before the snapshot date. Quote freshness, watchlist date, and watchlist generation time are separate fields.
- Use quote sessions for trading-day lookbacks. `Stock.performanceDays(5)` compares five bars including the current bar. A return across five intervals uses six closes. Check minimum history before calculating returns or indicators, and preserve ticker order through the API and UI.

## Verification

Run the smallest relevant suite from the repository root.

```sh
sbt "core/test" "service/test"
docker compose run --rm --no-deps backend sbt -batch "core/test" "service/test"
```

The second command is useful when JDK or sbt is unavailable on the host. For provider changes, use that module's sbt test target from the root build. For UI changes, run these commands from `web/`.

```sh
npm ci
npm test -- --watchAll=false --runInBand
npm run build
```

For a startup smoke test, use an isolated MongoDB with seeded quotes and set `TTK_MARKETS=ro-bvb`, `TTK_SKIP_INITIALIZATION=true`, and `TTK_SKIP_CRON=true`. This exercises screening against stored data without fetching provider quotes. US Alpaca can still start a backfill when its universe is missing or empty. Opt-in live tests may use provider quotas.

Whenever you change `local.conf.example`, merge the corresponding changes into `local.conf`. Preserve existing values, credentials, comments, and local overrides. Keep optional settings commented unless explicitly enabling them. If `local.conf` is missing, migrate any existing `local.properties` before filling gaps from the updated example.

Environment variables override `local.conf`. Keep credentials and generated logs out of patches and tool output. `sbt "service/packageService"` builds a local artifact. `service/deploy` can change a running Docker stack, including on a configured SSH host.
