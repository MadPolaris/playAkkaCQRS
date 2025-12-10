# Artifact: Saga Transaction Proto (事务协议)

**Target**: `SagaTransactionProtoArtifact`
**File Pattern**: `protobuf/${saga_name}_transaction.proto`

## 1. 核心职责
定义该 Saga 业务流程本身的启动参数或临时状态 (如有)。通常 Saga 状态由 Coordinator 管理，但如果 Transactor 需要持久化特定的请求数据，定义在此。

## 2. 编码约束
1.  **Usage**: 主要用于 Transactor 与 Client 之间的通讯协议 (Command/Reply) 如果需要序列化。
2.  **Structure**: 定义 `InitiateXxxRequest`, `XxxTransactionResult` 等。

## 3. 参考模板
```protobuf
syntax = "proto3";
import "credits.proto";

package net.imadz.infrastructure.proto;

// Define the Id type
message string {
  string value = 1;
}

// Define the Money type


// Define the TransactionStatus enum
enum TransactionStatusPO {
  UNKNOWN = 0;
  NEW = 1;
  INITIATED = 2;
  PREPARED = 3;
  COMPLETED = 4;
  FAILED = 5;
}

// Define the Failed status message
message FailedStatusPO {
  string reason = 1;
}

// Define the TransactionStatus union
message TransactionStatusMessagePO {
  TransactionStatusPO status = 1;
  oneof details {
    FailedStatusPO failed = 2;
  }
}

// Define the TransactionState message
message TransactionStatePO {
  string id = 1;
  string fromUserId = 2;
  string toUserId = 3;
  MoneyPO amount = 4;
  TransactionStatusMessagePO status = 5;
}

// Define the TransactionEvent union
message TransactionEventPO {
  oneof event {
    TransactionInitiatedPO initiated = 1;
    TransactionPreparedPO prepared = 2;
    TransactionCompletedPO completed = 3;
    TransactionFailedPO failed = 4;
  }
}

// Define the TransactionInitiated event
message TransactionInitiatedPO {
  string fromUserId = 1;
  string toUserId = 2;
  MoneyPO amount = 3;
}

// Define the TransactionPrepared event
message TransactionPreparedPO {
  string id = 1;
}

// Define the TransactionCompleted event
message TransactionCompletedPO {
  string id = 1;
}

// Define the TransactionFailed event
message TransactionFailedPO {
  string id = 1;
  string reason = 2;
}
```