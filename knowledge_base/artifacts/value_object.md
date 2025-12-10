# Artifact: Value Object (值对象)

**Target**: `ValueObjectArtifact`
**File Pattern**: `domain/values/${Name}.scala`

## 1. 核心职责
封装领域中的度量、描述或数量。它由属性值定义，而非身份定义。

## 2. 编码约束 (Hard Constraints)
1.  **Immutability**: 必须使用 `final case class`。所有字段必须是 `val`。
2.  **Rich Model**: 严禁只写数据字段。**必须**包含该概念相关的业务逻辑（如加减乘除、比较、格式化）。
3.  **Type Safety**:
    -   严禁使用 `Double` / `Float` 处理金额，必须使用 `BigDecimal`。
    -   运算方法必须检查兼容性（如币种是否一致）。
4.  **Error Handling**:
    -   **严禁**抛出异常 (`throw new Exception`)。
    -   校验失败或不兼容时，返回 `Option[T]` (None) 或 `Either[Error, T]`。
5.  **Naming**: 对于数学运算，使用符号方法名 (`+`, `-`, `*`, `<=`) 而非英文 (`plus`, `minus`)。

## 3. 参考模板

package $packageName

import $dependencies

case class Money(amount: BigDecimal, currency: Currency) {
  def +(other: Money): Option[Money] = 
    if (currency == other.currency) Some(copy(amount = amount + other.amount)) else None
}
