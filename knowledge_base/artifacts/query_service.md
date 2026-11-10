<!-- Derived from @madpolaris/truthsrc docs/artifacts/scala-cqrs/application/query-service.md; catalog 2.7.0; source-sha256 8c4d1e512d45f2455715b5c7ac594bd56dff905c2491c83eaf050d3925c82dfb. Edit the central document, not this snapshot. -->

# 查询与读模型组合服务

类别：`query-service`。本页属于版本化产物说明目录；源码名称、包位置和运行保证按下面的适用变体解释。

## 职责

按授权读取已投影事实并组成DTO，可表达水位和迟到；查询不是触发业务效果的隐式入口。

## 输入与人工信息

业务owner、字段含义、跨PID连接、分页与新鲜度策略需要显式选择。不能把说明性read-model集合自动视为可执行SQL DSL。

## 生产与所有权

BC由QueryServiceBuilder → QueryServiceTemplate读取SystemMetadata.application.queries，按selfType输出查询类及伴生DTO；多模块当前放root，方法体仍有???。它是部分骨架，不是查询实现，也不因ApplicationServiceTemplate有MANUAL块而自动取得相同覆盖保护。

JS legacy service-dsl/emit.mjs#emitQueryService读取显式kind=query、依赖和方法定义并写出<name>.scala，仍留???；它是真实独立CLI输出，不是正式typed-product链的完整查询服务。复制的rules_domain_gen头不能取代实际JS来源。bank-p23的BankReadside是外置手写组合实现，生成JDBC机制不拥有其业务查询政策。

按profile区分生成骨架、维护实现和生命周期，见能力矩阵（公共包 docs/artifacts/CAPABILITY-MATRIX.md）。源码检查不证明本轮目标编译、读侧追平、业务验收或最终批准。

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

主要实例：bc-single / bc-multi / bank-p23及JS legacy service-dsl。BC/JS骨架和银行手写读侧是不同实现责任，不能用当前读库数据或模板存在证明查询语义完整。

- `bc:src/main/scala/net/imadz/codegen/scaffolding/cqrs/template/application/QueryServiceTemplate.scala` — `QueryServiceTemplate`。
- `js:generators/service-dsl/emit.mjs` — `emitQueryService`。
- `bank:handwritten/scala/example/bankwealth/readside/BankReadside.scala` — `BankReadside`。

来源的完整revision、文件摘要及清点处置在根目录的`sources.lock.json`；本页摘录不是生产认证。

### 类别依赖

`projection-repository-port`、`http-dto`。这些是职责依赖，不代替真实Scala import或执行顺序验证。

## 第八批有限 Play 应用入口

新增 `js-play-application-candidate-v1`，由 `scala-cqrs-play-application-v1` 提供。原先profile的缺口描述保留为对应历史，不覆盖本段的新有限入口。

- `query-ports`：Platform-independent asynchronous query service contracts.
- `query-service`：Existing H2 state-map query on owned blocking execution context with complete keyset identity.

唯一作者和显式技术/外置实现bindings经过公共单writer；源码、真实编译、HTTP行为、安装与最终批准逐层记证。有限范围及不适用项见本族合同（公共包 docs/artifacts/scala-cqrs/PLAY-APPLICATION.md）；旧回执不重签，不表示银行或完整modular target已最终批准。
