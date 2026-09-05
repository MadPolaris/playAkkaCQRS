package net.imadz.monarch

/** Failure of one stage, classified by the host's business rules.
  *
  * @param stage     cursor of the failed stage (e.g. "RunRecipe_LITHO-01#4")
  * @param code      business error code, if the failure was classified
  * @param errorCode machine classification: business code or "UNEXPECTED"
  * @param detail    human-readable detail
  */
case class StageError(
    stage: String,
    code: Option[String],
    errorCode: String,
    detail: String
)

/** Thrown by a stage body to signal a *classified* business failure — the engine routes it
  * to the configured [[FailureInterceptor]] instead of failing the run. Any other NonFatal
  * is wrapped in a `StageError(_, None, "UNEXPECTED", ...)` and follows the same path.
  * Without an interceptor, both fail the run. */
final case class StageFailedException(error: StageError)
    extends RuntimeException(s"${error.errorCode}: ${error.detail}")
