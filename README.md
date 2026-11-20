# smiffer

Look up what is inside a Smithy model JAR without unzipping it.

Smithy models are often distributed as plain Maven artifacts — the `.smithy` files sit under
`META-INF/smithy/` inside a JAR, listed by a `manifest` file. That makes them awkward to inspect:
to answer "what shapes does this artifact define?" you otherwise download the JAR, unzip it, and
read raw IDL files that reference traits defined in some other artifact.

`smiffer` resolves the coordinate, assembles the model the way Smithy itself does, and prints the
part you asked about as real Smithy IDL.

```console
$ smiffer namespaces-external com.acme:acme-model:latest
3 namespace(s), 68 shapes

  com.acme.orders     41 shapes  services: OrderService
  com.acme.shared     19 shapes
  com.acme.types       8 shapes

$ smiffer get-external com.acme:acme-model:latest com.acme.orders#Order
$version: "2.1"

namespace com.acme.orders

/// A customer order.
structure Order {
    @required
    id: OrderId

    /// When the order was placed.
    placedAt: Timestamp

    lines: OrderLineList
}
```

It is built as a companion to [cellar](https://github.com/VirtusLab/cellar), which does the same
job for JVM library APIs, and follows the same command layout.

## Installation

With Nix:

```sh
nix run github:ghostbuster91/smiffer -- namespaces-external com.acme:acme-model:latest
nix profile install github:ghostbuster91/smiffer   # to keep it on PATH
```

The flake also exposes an overlay (`overlays.default`, attribute `smiffer`) and a dev shell with
sbt and a JDK (`nix develop`).

## Commands

Two families, mirroring cellar. The plain commands read the nearest `smithy-build.json`; the
`-external` ones take an explicit coordinate.

### From a Smithy project

Run these anywhere inside a project with a `smithy-build.json` — smiffer walks up to find it and
inspects the model JARs listed under `maven.dependencies`, using that file's `maven.repositories`
as well as your own.

    smiffer namespaces                 # what namespaces exist, and which define services
    smiffer list <namespace>           # the shapes in one namespace
    smiffer search <query>             # find a shape by name
    smiffer get <shape-id>             # render one shape as IDL

### From a coordinate

    smiffer namespaces-external <coordinate>
    smiffer list-external <coordinate> <namespace>
    smiffer search-external <coordinate> <query>
    smiffer get-external <coordinate> <shape-id>
    smiffer deps <coordinate>          # the artifact's transitive dependencies

Coordinates are `group:artifact:version`. Use `latest` as the version to resolve the newest
release:

```sh
smiffer get-external com.acme:acme-model:latest com.acme.orders#Order
```

### Shape IDs

A shape ID is `namespace#Name`, e.g. `com.acme.orders#Order`. A bare `Order` works too — smiffer
matches it case-insensitively across the model and tells you the candidates if it is ambiguous,
which saves a lookup when you do not know the namespace yet.

A member ID (`com.acme.orders#Order$id`) renders the structure that declares it. Members are not
standalone shapes in the IDL, so the containing structure is where the member is actually visible.

## Flags

| Flag | Applies to | Effect |
| --- | --- | --- |
| `--json` | `get`, `list`, `search` | Emit the Smithy JSON AST instead of IDL |
| `-c`, `--closure` | `get` | Also render every shape the target transitively references |
| `-l`, `--limit <N>` | `list`, `search` | Cap the number of results (default 100) |
| `-d`, `--include-dependencies` | `namespaces`, `list`, `search` | Include shapes defined by dependency JARs, not just the queried artifact |
| `--include-prelude` | `namespaces`, `list`, `search` | Include Smithy's own `smithy.api` shapes |
| `-r`, `--repository <url>` | all | Extra Maven repository, repeatable |
| `-v`, `--verbose` / `--debug` | all | Diagnostics on stderr |
| `-V`, `--version` | — | Print the version |

### Scope: what gets listed

`namespaces`, `list` and `search` show only the shapes the artifact you asked about **defines
itself**. Its dependencies are still loaded — a model that applies a trait from another artifact
does not assemble without it — but their shapes are not listed, because otherwise querying one
small model JAR buries you in whatever it happens to depend on. Pass `--include-dependencies` to
widen the scope.

`get` is different: it resolves a shape anywhere in the loaded model, dependencies included, since
asking for a shape by name should find it wherever it lives.

### `--closure`

A structure's members only name their target shapes, so reading one shape often means looking up
several more. `--closure` renders the whole reachable set in one go — a service with its
operations, their inputs and outputs, and the structures those reference:

```sh
smiffer get-external com.acme:acme-model:latest com.acme.orders#OrderService --closure
```

Smithy's prelude is left out of the closure; `String` and `Integer` need no introduction.

## Private repositories

Set `SMIFFER_REPOSITORIES` to inspect models published somewhere other than Maven Central. Commas
or whitespace separate entries, and `-r` adds to it rather than replacing it:

```sh
export SMIFFER_REPOSITORIES="$ARTIFACTORY_URL/maven,$ARTIFACTORY_URL/maven-snapshots"
smiffer namespaces-external com.acme:acme-model:latest
```

Credentials come from coursier's own configuration, so an existing `~/.config/coursier/credentials.properties`
is picked up as-is.

## Output

- **stdout** carries the payload only, so it is safe to pipe or paste into a prompt.
- **stderr** carries diagnostics: truncation notices, validation warnings, `--verbose` logging.
- Exit `0` on success, `1` on error.

Models that fail Smithy validation are still served, with a warning on stderr — a published model
with a validation error is usually still the model you need to read. `--debug` prints the
individual validation events.

## Development

```sh
nix develop          # sbt + JDK 21
sbt test
sbt assembly         # -> target/out/jvm/scala-3.9.0/smiffer/smiffer.jar
```

Built with Scala 3.9.0 on sbt 2, against `software.amazon.smithy:smithy-model`. The IDL output
comes from Smithy's own `SmithyIdlModelSerializer` rather than a hand-written printer, so it stays
correct as the Smithy spec grows.

Dependencies are prefetched for the Nix build by a fixed-output derivation. After changing
`build.sbt`, refresh its hash:

```sh
nix build .#deps      # fails, printing the hash it got
# paste that into depsHash in flake.nix
```

## Agent use

`skills/smiffer/SKILL.md` is a [Claude Code](https://claude.com/claude-code) skill describing when
and how to reach for smiffer. Point your agent at it, or copy it into `~/.claude/skills/`.
