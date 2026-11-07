<!-- Derived from @madpolaris/truthsrc docs/artifacts/scala-cqrs/application/application-protocol.md; catalog 1.0.0; source-sha256 71f6097e0dbbb51c007a52ac9a932efa8b460bf8b94aac9c33abff176b16b234. Edit the central document, not this snapshot. -->

# Actor命令与回复协议

类别：`application-protocol`。本页属于版本化产物说明目录；源码名称、包位置和运行保证按下面的适用变体解释。

## 职责

定义投递到聚合的Actor命令、replyTo和回复族，以及CommandHandler签名；与不含Actor句柄的业务payload分离。

## 输入与人工信息

由领域命令、查询、拒绝/成功响应与明确的异步交互约定组装。回复语义和是否需要确认不能从名字猜测。

## 生产与所有权

由列出的生产者/实现提供。BC的Scala结构与JS最终写入者必须一起判断；同文件后续JS覆写时，最终台账行归最后写入者。当前银行兼容profile只按实际已有产物标识，不由旧文档推定能力。

目录状态：`implemented`；所有权：`generated`。生成文件通过公共生成/更新通道维护；独立手写实现不是生成结果的可编辑副本。

## 结构与命名

BC Protocol通常定义case classes；JS兼容Protocol导出旧Request/Reply与伴生构造器类型别名，保留二进制载体。多模块它属于kernel接口层而非glue。

类型使用UpperCamelCase，成员使用lowerCamelCase；数据库、protobuf和稳定业务ID分别遵循各自合同。描述目标命名不授权改写现有wire名称、PID或冻结载体。

## 依赖与装配

port/Repository接口可引用Protocol；接口不得注解引用glue实现，多模块在root绑定。ActorRef[Reply]只是通信句柄，不承诺消息执行成功。

## 回锚与更新

有业务知识输入的产物关联相应展开节点与最终源码位置；共享机制只关联其技术合同，不伪造业务来源。BC历史inline注释和JS外置同图映射分开解释；本批不恢复JS新增inline @knowledge。手写映射只是显式实现关系，不自动证明语义或精确span。

## 验证

消息族完整、伴生apply/unapply可用、类型别名准确、错误/成功回复可区分。 文档覆盖、代码编译与业务运行是不同证据；只有实际执行对应检查才可以报告通过。

## 版本与实例

机器目录按各适用变体分别记录可用性/所有权，不能把一个变体的implemented传播到其他profile。

适用：bc-single / bc-multi / js-formal。单模块和多模块是放置/装配变体，不是两套业务事实。BANK P23兼容层与BC目标模板可能保留具名差异；没有把R2运行库能力写成当前银行生成支持。

- `bc:src/main/scala/net/imadz/codegen/scaffolding/cqrs/template/application/AggregateProtocolTemplate.scala` — `AggregateProtocolTemplate`。
- `js:generators/scala-cqrs-play-service/render-aggregate-layers.mjs` — `renderAggregateLayers`。

来源的完整revision、文件摘要及清点处置在根目录的`sources.lock.json`；本页摘录不是生产认证。

### 类别依赖

`domain-protocol`、`value-object`。这些是职责依赖，不代替真实Scala import或执行顺序验证。
