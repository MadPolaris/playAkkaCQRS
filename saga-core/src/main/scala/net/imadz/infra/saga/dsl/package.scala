package net.imadz.infra.saga

/** Package-level DSL sugar. */
package object dsl {

  /** Existential-friendly helper so steps with heterogeneous result types can be
    * listed in one Seq without explicit Any annotations. */
  def steps[E, C](xs: SagaStep[E, _, C]*): Seq[SagaStep[E, _, C]] = xs
}
