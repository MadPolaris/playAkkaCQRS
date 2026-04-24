package net.imadz.infra.saga

import net.imadz.infra.saga.serialization.SagaParticipantSerializerStrategy

class TestParticipantSerializerStrategy(val manifest: String, val participantClass: Class[_], instance: () => SagaParticipant[_, _, _]) extends SagaParticipantSerializerStrategy {
  override def toBinary(participant: SagaParticipant[_, _, _]): Array[Byte] = Array.emptyByteArray
  override def fromBinary(bytes: Array[Byte]): SagaParticipant[_, _, _] = instance()
}

object TestParticipantSerializerStrategy {
  def forObject(obj: SagaParticipant[_, _, _]): TestParticipantSerializerStrategy = {
    new TestParticipantSerializerStrategy(obj.getClass.getName, obj.getClass, () => obj)
  }
}
