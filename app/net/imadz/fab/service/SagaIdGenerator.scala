package net.imadz.fab.service

import java.util.UUID
import java.nio.charset.StandardCharsets

object SagaIdGenerator {
  def generate(workOrderId: UUID, stageName: String, executionIndex: Int): UUID = {
    val payload = s"${workOrderId.toString}-$stageName-$executionIndex"
    UUID.nameUUIDFromBytes(payload.getBytes(StandardCharsets.UTF_8))
  }
}
