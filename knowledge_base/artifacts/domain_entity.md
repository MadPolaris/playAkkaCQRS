<!-- Derived from @madpolaris/truthsrc docs/artifacts/scala-cqrs/domain/domain-entity.md; catalog 2.3.0; source-sha256 2038486e7bfd84be84849663a1cf5128859aa63a41c02a84f901ff4d11b83550. Edit the central document, not this snapshot. -->

# 领域实体与状态初始化

类别：`domain-entity`。本页属于版本化产物说明目录；源码名称、包位置和运行保证按下面的适用变体解释。

## 职责

定义状态与事件归属并提供唯一空状态初始化。实体不是HTTP服务，也不是Actor生命周期管理器。

## 输入与人工信息

输入为聚合/entity定义、状态字段、事件族、默认值和标识约束。目标要求未声明的业务初值不得靠null或猜测补齐；JS准入typed产品与BC neutralValueFor的既有行为须分别核对，不能把该目标写成所有profile已实现的保证。

## 生产与所有权

BC单/多模块从SystemMetadata.domain.entities经DomainEntityBuilder → DomainEntityTemplate生成实体；多模块放入kernel，但empty使用neutralValueFor合成数值哨兵等默认值，Instant/未知类型可为null。这是现存初始化债，不是作者显式初值已完整落实。

JS正式域生成由buildStateModels/stateEntries消费reference-layout、domain-folds及exact-value-types；仅layered Play service形额外生成Entity别名与唯一empty初始化。非layered形以State为载体。legacy aggregate-dsl在entityDir启用时也输出实体，retired scala-cqrs-es是历史骨架，不能与当前typed产品互认能力。

能力、生命周期、生成责任及证据按profile分别记录，见能力矩阵（公共包 docs/artifacts/CAPABILITY-MATRIX.md）。本页说明源代码中的生产路径，不声称本轮已生成目标、编译或最终批准；生成文件仍经公共更新通道维护。

## 结构与命名

BC <Name>Entity承载State/Event及empty；JS兼容形的domain/entities/<Name>Entity导出原State/Event别名和empty，保留JVM载体身份。

类型使用UpperCamelCase，成员使用lowerCamelCase；数据库、protobuf和稳定业务ID分别遵循各自合同。描述目标命名不授权改写现有wire名称、PID或冻结载体。

## 依赖与装配

事件应用依赖实体合同，application行为决定哪些事件持久化。kernel中实体不依赖ext实现、glue PO或数据库。

## 回锚与更新

有业务知识输入的产物关联相应展开节点与最终源码位置；共享机制只关联其技术合同，不伪造业务来源。BC历史inline注释和JS外置同图映射分开解释；本批不恢复JS新增inline @knowledge。手写映射只是显式实现关系，不自动证明语义或精确span。

## 验证

核初始化、状态/事件成员、已选兼容别名及真实event fold调用，不能只看Entity文件存在。 文档覆盖、代码编译与业务运行是不同证据；只有实际执行对应检查才可以报告通过。

## 版本与实例

机器目录按各适用变体分别记录可用性/所有权，不能把一个变体的implemented传播到其他profile。

主要实例：bc-single / bc-multi / js-formal；legacy/retired路径见上文与能力矩阵。单/多模块指BC放置变体，JS layered选择不能视为多模块支持；BANK P23兼容形不推定R2能力。

- `bc:src/main/scala/net/imadz/codegen/scaffolding/cqrs/builder/domain/DomainEntityBuilder.scala` — `neutralValueFor`。
- `bc:src/main/scala/net/imadz/codegen/scaffolding/cqrs/template/domain/DomainEntityTemplate.scala` — `DomainEntityTemplate`。
- `js:generators/scala-cqrs-domain/templates/aggregate-state.mjs` — `stateEntries`。
- `js:generators/scala-cqrs-play-service/render-aggregate-layers.mjs` — `renderAggregateLayers`。

来源的完整revision、文件摘要及清点处置在根目录的`sources.lock.json`；本页摘录不是生产认证。

### 类别依赖

`value-object`、`domain-state`、`domain-protocol`。这些是职责依赖，不代替真实Scala import或执行顺序验证。
