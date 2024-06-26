package net.imadz.common

import java.util.UUID

object CommonTypes {

  type Id = UUID

  final case class iMadzError(code: String, message: String)

  trait ReadModel

  /**
   * Marker trait for serialization with Jackson CBOR
   */
  trait CborSerializable

  trait DomainPolicy[Event, State, Param] {
    def apply(state: State, param: Param): Either[iMadzError, List[Event]]
  }

  trait DomainService

  trait ApplicationService
}

object Id {
  def of(value: String): UUID = UUID.fromString(value)
}