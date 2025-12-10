# Artifact: Protobuf Definition (协议定义)

**Target**: `ProtoDefinitionArtifact`, `SagaTransactionProtoArtifact`, `SagaParticipantProtoArtifact`
**File Pattern**: `*.proto`

## 1. 核心职责
定义跨进程通讯或持久化的数据格式。

## 2. 编码约束
1.  **Syntax**: 必须使用 `syntax = "proto3";`。
2.  **Options**:
    -   `option java_multiple_files = true;`
    -   `option java_package = "net.imadz.infrastructure.proto...";` (注意 Context 包名)。
3.  **Content**:
    -   **For Aggregates**: 定义 Events 和 State。
    -   **For Sagas**: 定义 Transaction State 和 Participant Payload。
4.  **Naming**: 字段名使用 `snake_case`，消息名使用 `PascalCase`。

## 3. 参考模板
```protobuf
syntax = "proto3";

package net.imadz.infrastructure.proto;

// Define the Money type
message MoneyPO {
  double amount = 1;
  string currency = 2;
}

// Define the BalanceChanged event
message BalanceChanged {
  MoneyPO update = 1;
  int64 timestamp = 2;
}

// Define the FundsReserved event
message FundsReserved {
  string transferId = 1;
  MoneyPO amount = 2;
}

// Define the FundsDeducted event
message FundsDeducted {
  string transferId = 1;
  MoneyPO amount = 2;
}

// Define the ReservationReleased event
message ReservationReleased {
  string transferId = 1;
  MoneyPO amount = 2;
}

// Define the IncomingCreditsRecorded event
message IncomingCreditsRecorded {
  string transferId = 1;
  MoneyPO amount = 2;
}

// Define the IncomingCreditsCommited event
message IncomingCreditsCommited {
  string transferId = 1;
}

// Define the IncomingCreditsCanceled event
message IncomingCreditsCanceled {
  string transferId = 1;
}

// Define the CreditBalanceEvent union
message CreditBalanceEventPO {
  oneof event {
    BalanceChanged balanceChanged = 1;
    FundsReserved fundsReserved = 2;
    FundsDeducted fundsDeducted = 3;
    ReservationReleased reservationReleased = 4;
    IncomingCreditsRecorded incomingCreditsRecorded = 5;
    IncomingCreditsCommited incomingCreditsCommited = 6;
    IncomingCreditsCanceled incomingCreditsCanceled = 7;
  }
}

// Define the CreditBalanceState snapshot
message CreditBalanceStatePO {
  string userId = 1; // UUID can be represented as a string
  map<string, MoneyPO> accountBalance = 2;
  map<string, MoneyPO> reservedAmount = 3;
  map<string, MoneyPO> incomingCredits = 4;
}
```