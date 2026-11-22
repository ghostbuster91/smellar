package smellar

/** Smithy's built-in prelude namespace.
  *
  * Only `smithy.api` qualifies. It is implicitly present in every model and almost never what
  * someone inspecting a published artifact is looking for, so it is filtered out unless
  * `--include-prelude` asks for it.
  *
  * Namespaces like `smithy.waiters`, `smithy.rules` and `smithy.test` look similar but are ordinary
  * trait packages published as their own artifacts — treating them as prelude would make the
  * artifact that defines them appear to contain nothing at all.
  */
object Prelude:
  def isPrelude(namespace: String): Boolean =
    namespace == "smithy.api" || namespace.startsWith("smithy.synthetic")
