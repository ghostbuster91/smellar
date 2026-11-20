---
name: smiffer
description: >
  Inspect Smithy models that are published as Maven JARs — list namespaces and
  shapes, search by name, and render a shape as Smithy IDL or JSON AST, without
  downloading or unzipping the JAR. Use this skill whenever you need to know what
  a Smithy model artifact contains: which services and operations it defines, the
  members of a structure, or where a shape lives. Prefer it over fetching and
  unzipping a model JAR by hand, and over guessing a shape's definition.
---

# smiffer

Smithy models shipped as Maven artifacts keep their `.smithy` files inside the JAR under
`META-INF/smithy/`. Reading them by hand means resolving the coordinate, unzipping, and then
chasing traits that are defined in a *different* artifact. `smiffer` assembles the model properly
and prints only what you asked for.

## Prerequisites

Run `smiffer --version` to check it is on PATH. If it is missing, the user needs to install it —
see the repository README.

## Project-aware commands

Run from anywhere inside a project containing a `smithy-build.json`; smiffer walks up to find it
and inspects the JARs in its `maven.dependencies`.

    smiffer namespaces                 # what namespaces exist, and which define services
    smiffer list <namespace>           # shapes in one namespace
    smiffer search <query>             # find a shape by name
    smiffer get <shape-id>             # render one shape as IDL

## Coordinate commands

    smiffer namespaces-external <coordinate>
    smiffer list-external <coordinate> <namespace>
    smiffer search-external <coordinate> <query>
    smiffer get-external <coordinate> <shape-id>
    smiffer deps <coordinate>          # transitive dependencies

- Coordinates are `group:artifact:version`; `latest` resolves the newest release.
- Shape IDs are `namespace#Name`. A bare `Name` also works — it is matched across the model, and an
  ambiguous name returns the candidates rather than picking one.
- `-r <url>` adds a Maven repository (repeatable); `SMIFFER_REPOSITORIES` sets them once.

## Workflow

Start wide, then narrow. Each step tells you what to ask for next.

1. **Don't know what the artifact holds?** → `smiffer namespaces-external <coord>`
   Gives shape counts per namespace and names the services, which is where a model starts.
2. **Know the namespace?** → `smiffer list-external <coord> <namespace>`
   One line per shape: type, ID, and the first line of its docs.
3. **Looking for a shape by name?** → `smiffer search-external <coord> <query>`
4. **Know the shape?** → `smiffer get-external <coord> <shape-id>`
5. **Need everything it references?** → add `--closure`

## Useful flags

- `--closure` (on `get`): also render every transitively referenced shape. Use this for a service
  or operation — one call instead of ten, since a structure's members otherwise only name their
  targets. Smithy's prelude is excluded.
- `--json`: Smithy JSON AST instead of IDL. Use when you need to parse the result; prefer IDL when
  you need to read it.
- `-d`, `--include-dependencies`: by default `namespaces`/`list`/`search` show only shapes the
  queried artifact defines itself. Add this when the shape you want lives in a dependency.
- `-l`, `--limit <N>`: raise the 100-result cap. Truncation is always reported on stderr.
- `--debug`: shows resolution steps and Smithy validation events. Reach for it when a shape you
  expect is reported as missing.

## Examples

```sh
# What is in this model artifact?
smiffer namespaces-external com.acme:acme-model:latest

# A service and everything it reaches — operations, inputs, outputs, nested structures
smiffer get-external com.acme:acme-model:latest com.acme.orders#OrderService --closure

# One structure, as IDL
smiffer get-external com.acme:acme-model:1.4.0 com.acme.orders#Order

# Find a shape when you only remember part of the name
smiffer search-external com.acme:acme-model:latest Order

# Machine-readable, for parsing
smiffer get-external com.acme:acme-model:latest com.acme.orders#Order --json

# From a private Artifactory
smiffer -r "$ARTIFACTORY_URL/maven" namespaces-external com.acme:acme-model:latest

# Inside a project with a smithy-build.json
smiffer namespaces
smiffer get com.acme.orders#Order
```

## Example output

`smiffer get-external software.amazon.smithy:smithy-waiters:latest smithy.waiters#WaiterDelay`

```smithy
$version: "2.1"

namespace smithy.waiters

@range(
    min: 1
)
integer WaiterDelay
```

The IDL is produced by Smithy's own serializer, so it is real, re-parseable IDL with the applied
traits and documentation intact — not an approximation.

## Output contract

- **stdout**: the payload only (IDL, JSON, or a listing) — safe to pipe.
- **stderr**: diagnostics — truncation notices, validation warnings, `--verbose`/`--debug` logs.
- **Exit 0** success, **exit 1** error. Errors are plain messages and suggest near matches for a
  mistyped coordinate, shape, or namespace.

A model that fails Smithy validation is still served, with a warning on stderr. That is usually
what you want — a published model with a validation error is still the model you need to read — but
if shapes seem to be missing, re-run with `--debug` to see the validation events.
