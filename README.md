# AppGate

Process supervisor for Pterodactyl Minecraft eggs used as a plain Java 25 host. Drop JARs into `apps/`; they run in
parallel. HTTP path proxy on `SERVER_PORT` forwards to localhost apps and strips the public prefix.

Requires **Java 25** on the egg. Child apps can still use Temurin 8/17/21/25 via `jdk/`.

## Layout

```
/home/container/
  App-Gate.jar
  config.json
  apps/*.jar
  data/<jarName>/   # cwd per app (jar basename without .jar)
  jdk/8|17|21|25/   # downloaded as needed
```

Upload `App-Gate.jar` as the server jar. First run creates `config.json`, `apps/`, and `data/`. Bind apps to `127.0.0.1`.
Egg args like `nogui` are ignored.

Ready line for the panel:

```text
Type '/help' for available commands
```

## Apps

Identity comes from the JAR filename (basename without `.jar`). Launch is `java -cp <jar> <MainClass>` with cwd
`data/<jarName>/` so relative paths stay per-app. Config syncs on discovery; set `"console": "my-api"` for
`apps/my-api.jar` (or omit if only one app).

## Proxy

```json
"proxy": {
  "enabled": true,
  "port": null,
  "routes": [
    {
      "path": "/api",
      "target": "http://127.0.0.1:8080"
    }
  ]
}
```

Longest prefix wins; prefix stripped. `port: null` → `SERVER_PORT`.

## Console

- plain line → attached app
- `!!text` → send `!text` to app
- `!attach <name>` / `!list` / `!stop` / `!help`

Crash autorestart and per-JAR hot-reload are on by default.

## Build

```bash
./gradlew jar
```

Output: `build/libs/App-Gate.jar` only.
