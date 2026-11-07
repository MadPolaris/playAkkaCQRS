<!-- Derived from @madpolaris/truthsrc docs/artifacts/scala-cqrs/projection/projection-handler.md; catalog 1.0.0; source-sha256 2499d405435888b02ea6d91fef63dcc19429cfdeafb66cd73364606cc29e8938. Edit the central document, not this snapshot. -->

# 事件投影处理器

类别：`projection-handler`。本页属于版本化产物说明目录；源码名称、包位置和运行保证按下面的适用变体解释。

## 职责

在投影框架回调中解码事件，执行读侧更新并明确成功/失败边界。不是Actor写侧事件handler，也不是事务Saga。

## 输入与人工信息

源事件/视图映射、decoder、identity和Repository接口、处理语义及错误政策必须已声明。

## 生产与所有权

由列出的生产者/实现提供。BC的Scala结构与JS最终写入者必须一起判断；同文件后续JS覆写时，最终台账行归最后写入者。当前银行兼容profile只按实际已有产物标识，不由旧文档推定能力。

目录状态：`implemented`；所有权：`generated`。生成文件通过公共生成/更新通道维护；独立手写实现不是生成结果的可编辑副本。

## 结构与命名

BC <View>On<Event>Handler由Scala建壳、JS最终写入；multi位于glue。JS当前BankJournalReadsideProjection统一调度PB状态/关系索引，是具名机制profile，不能冒扁平表CRUD同形。

类型使用UpperCamelCase，成员使用lowerCamelCase；数据库、protobuf和稳定业务ID分别遵循各自合同。描述目标命名不授权改写现有wire名称、PID或冻结载体。

## 依赖与装配

依赖decoder、纯applyEvent和实际session Repository；exactly-once JDBC offset不代表整个银行业务副作用exactly-once。

## 回锚与更新

有业务知识输入的产物关联相应展开节点与最终源码位置；共享机制只关联其技术合同，不伪造业务来源。BC历史inline注释和JS外置同图映射分开解释；本批不恢复JS新增inline @knowledge。手写映射只是显式实现关系，不自动证明语义或精确span。

## 验证

重复/乱序/失败重试、事务回滚、水位、poison事件和新库重放；未实现或优雅跳过必须逐项显露。 文档覆盖、代码编译与业务运行是不同证据；只有实际执行对应检查才可以报告通过。

## 版本与实例

机器目录按各适用变体分别记录可用性/所有权，不能把一个变体的implemented传播到其他profile。

适用：bc-single / bc-multi / js-formal。单模块和多模块是放置/装配变体，不是两套业务事实。BANK P23兼容层与BC目标模板可能保留具名差异；没有把R2运行库能力写成当前银行生成支持。

- `bc:knowledge_base/design_docs/field_template/tools/gen_projection_handlers.mjs` — `handlerPath`。
- `bc:src/main/scala/net/imadz/codegen/scaffolding/cqrs/template/application/ProjectionHandlerTemplate.scala` — `ProjectionHandlerTemplate`。
- `js:generators/scala-cqrs-play-service/render-journal-readside.mjs` — `renderJournalReadside`。

来源的完整revision、文件摘要及清点处置在根目录的`sources.lock.json`；本页摘录不是生产认证。

### 类别依赖

`projection-apply-event`、`projection-repository-port`、`proto-converter`。这些是职责依赖，不代替真实Scala import或执行顺序验证。
