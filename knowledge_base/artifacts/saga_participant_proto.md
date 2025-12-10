# Artifact: Saga Participant Proto (参与者协议)

**Target**: `SagaParticipantProtoArtifact`
**File Pattern**: `protobuf/${saga_name}_participant.proto`

## 1. 核心职责
定义该 Saga 中所有参与者 (Participants) 的序列化数据结构。

## 2. 编码约束
1.  **Messages**: 为每个 Participant 定义一个 Message (e.g. `FromAccountParticipantPO`)。
2.  **Fields**: 必须包含恢复该 Participant 所需的所有字段 (如 IDs, Money 等)。
3.  **Import**: 引用 `credits.proto` (如果使用了共享的 MoneyPO)。

## 3. 参考模板
```protobuf
message FromAccountParticipantPO {
  string user_id = 1;
  MoneyPO amount = 2;
}
```