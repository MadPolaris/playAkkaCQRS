package net.imadz.common

import net.imadz.common.CommonTypes.Id

import java.util.UUID

object CommonTypes {

  type Id = UUID

  final case class iMadzError(code: String, message: String) extends Throwable with CborSerializable

  trait ReadModel

  trait DomainPolicy[Event, State, Param] {
    def apply(state: State, param: Param): Either[iMadzError, List[Event]]
  }

  trait DomainService

  trait ApplicationService
}

object Id {
  def of(value: String): Id = UUID.fromString(value)

  def gen: Id = java.util.UUID.randomUUID()
}