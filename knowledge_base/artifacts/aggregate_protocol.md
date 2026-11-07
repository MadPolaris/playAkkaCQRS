<!-- Derived from @madpolaris/truthsrc docs/artifacts/scala-cqrs/application/application-protocol.md; catalog 2.2.0; source-sha256 c305de00e2975c14eb1ab8474d0aaf181868542d952b3663d922d33c13d492fb. Edit the central document, not this snapshot. -->

# Actor命令与回复协议

类别：`application-protocol`。本页属于版本化产物说明目录；源码名称、包位置和运行保证按下面的适用变体解释。

## 职责

定义投递到聚合的Actor命令、replyTo和回复族，以及CommandHandler签名；与不含Actor句柄的业务payload分离。

## 输入与人工信息

由领域命令、查询、拒绝/成功响应与明确的异步交互约定组装。回复语义和是否需要确认不能从名字猜测。

## 生产与所有权

BC由AggregateProtocolBuilder/Template读取application聚合的commandFamily/replyFamily，生成实际Actor命令与回复类型，多模块放kernel。

JS正式layered Play service由templates/aggregate-protocol.mjs的protocolTemplate/legacyTemplate从reference-layout/domain-dependencies同时维护应用Protocol与旧domain/<Name>Aggregate载体。Protocol是type/value别名，Request/Reply的物理case class仍在兼容文件。legacy aggregate-dsl的Actor协议及retired scala-cqrs-es骨架不是该兼容别名链的替代实现。

各profile的消息载体、生成责任和生命周期见能力矩阵（公共包 docs/artifacts/CAPABILITY-MATRIX.md）。源码合同存在不证明本轮消息传递、序列化、目标编译或最终批准。

## 结构与命名

BC Protocol通常定义case classes；JS layered兼容Protocol导出旧Request/Reply的类型别名及伴生构造器值，保留二进制载体。BC多模块Protocol放kernel而非glue；此放置不能推广为JS正式单项目已具有相同模块布局。

类型使用UpperCamelCase，成员使用lowerCamelCase；数据库、protobuf和稳定业务ID分别遵循各自合同。描述目标命名不授权改写现有wire名称、PID或冻结载体。

## 依赖与装配

port/Repository接口可引用Protocol；接口不得注解引用glue实现，多模块在root绑定。ActorRef[Reply]只是通信句柄，不承诺消息执行成功。

## 回锚与更新

有业务知识输入的产物关联相应展开节点与最终源码位置；共享机制只关联其技术合同，不伪造业务来源。BC历史inline注释和JS外置同图映射分开解释；本批不恢复JS新增inline @knowledge。手写映射只是显式实现关系，不自动证明语义或精确span。

## 验证

消息族完整、伴生apply/unapply可用、类型别名准确、错误/成功回复可区分。 文档覆盖、代码编译与业务运行是不同证据；只有实际执行对应检查才可以报告通过。

## 版本与实例

机器目录按各适用变体分别记录可用性/所有权，不能把一个变体的implemented传播到其他profile。

主要实例：bc-single / bc-multi / js-formal；legacy/retired另见上文。单/多模块是BC放置变体，JS兼容别名保持已有载体而非另造同名消息族。

- `bc:src/main/scala/net/imadz/codegen/scaffolding/cqrs/template/application/AggregateProtocolTemplate.scala` — `AggregateProtocolTemplate`。
- `js:generators/scala-cqrs-play-service/templates/aggregate-protocol.mjs` — `protocolTemplate / legacyTemplate`。
- `js:generators/scala-cqrs-play-service/render-aggregate-layers.mjs` — `renderAggregateLayers`。

来源的完整revision、文件摘要及清点处置在根目录的`sources.lock.json`；本页摘录不是生产认证。

### 类别依赖

`domain-protocol`、`value-object`。这些是职责依赖，不代替真实Scala import或执行顺序验证。
