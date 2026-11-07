<!-- Derived from @madpolaris/truthsrc docs/artifacts/scala-cqrs/application/query-service.md; catalog 1.0.0; source-sha256 128a72bc03915a71810f843554d94282c2a53a63ae94e7c8ccedba8b44a3dace. Edit the central document, not this snapshot. -->

# 查询与读模型组合服务

类别：`query-service`。本页属于版本化产物说明目录；源码名称、包位置和运行保证按下面的适用变体解释。

## 职责

按授权读取已投影事实并组成DTO，可表达水位和迟到；查询不是触发业务效果的隐式入口。

## 输入与人工信息

业务owner、字段含义、跨PID连接、分页与新鲜度策略需要显式选择。不能把六份说明性read-model自动视为可执行SQL DSL。

## 生产与所有权

由列出的生产者/实现提供。BC的Scala结构与JS最终写入者必须一起判断；同文件后续JS覆写时，最终台账行归最后写入者。当前银行兼容profile只按实际已有产物标识，不由旧文档推定能力。

目录状态：`implemented`；所有权：`mixed`。生成文件通过公共生成/更新通道维护；独立手写实现不是生成结果的可编辑副本。

## 结构与命名

application/query或对应readside实现；FeedAggregator属于多来源查询组合变体，不应被无意义platform分类隐藏。

类型使用UpperCamelCase，成员使用lowerCamelCase；数据库、protobuf和稳定业务ID分别遵循各自合同。描述目标命名不授权改写现有wire名称、PID或冻结载体。

## 依赖与装配

依赖查询port/Repository而非直接写侧状态，I/O由外层adapter提供。生成JDBC机制与银行DTO/索引选择分开。

## 回锚与更新

有业务知识输入的产物关联相应展开节点与最终源码位置；共享机制只关联其技术合同，不伪造业务来源。BC历史inline注释和JS外置同图映射分开解释；本批不恢复JS新增inline @knowledge。手写映射只是显式实现关系，不自动证明语义或精确span。

## 验证

权限与租户隔离、分页、空/过期读库、水位、重放追平；不能把有数据当readiness已追平。 文档覆盖、代码编译与业务运行是不同证据；只有实际执行对应检查才可以报告通过。

## 版本与实例

机器目录按各适用变体分别记录可用性/所有权，不能把一个变体的implemented传播到其他profile。

适用：bc-single / bc-multi / bank-p23。单模块和多模块是放置/装配变体，不是两套业务事实。BANK P23兼容层与BC目标模板可能保留具名差异；没有把R2运行库能力写成当前银行生成支持。

- `bc:src/main/scala/net/imadz/codegen/scaffolding/cqrs/template/application/QueryServiceTemplate.scala` — `QueryServiceTemplate`。
- `bank:handwritten/scala/example/bankwealth/readside/BankReadside.scala` — `BankReadside`。

来源的完整revision、文件摘要及清点处置在根目录的`sources.lock.json`；本页摘录不是生产认证。

### 类别依赖

`projection-repository-port`、`http-dto`。这些是职责依赖，不代替真实Scala import或执行顺序验证。
