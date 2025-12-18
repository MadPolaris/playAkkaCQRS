package net.imadz.infra.saga.serialization

import net.imadz.infra.saga.SagaParticipant

trait SagaParticipantSerializerStrategy {
  def manifest: String
  def participantClass: Class[_]

  def toBinary(participant: SagaParticipant[_, _]): Array[Byte]

  /**
   * 反序列化
   * @param bytes 二进制数据
   * @param resolver 依赖解析器 (即 Extension)
   * @param ec 执行上下文 (通常由 System 提供)
   */
  def fromBinary(bytes: Array[Byte]): SagaParticipant[_, _]
}