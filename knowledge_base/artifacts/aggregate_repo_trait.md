<!-- Derived from @madpolaris/truthsrc docs/artifacts/scala-cqrs/application/aggregate-repository-port.md; catalog 2.2.0; source-sha256 bce40b00014c90e35d160711faf766a736b7a1f7ebcce030595b54dab25b08b7. Edit the central document, not this snapshot. -->

# 聚合仓储接口

类别：`aggregate-repository-port`。本页属于版本化产物说明目录；源码名称、包位置和运行保证按下面的适用变体解释。

## 职责

按业务身份取得聚合引用的应用端口，不直接表达SQL表读取。

## 输入与人工信息

身份类型和实体寻址方式必须选择：BC sharding EntityRef与当前银行本地ActorRef不是同一个运行保证。

## 生产与所有权

BC由AggregateRepositoryBuilder → AggregateRepositoryTemplate读取application.repositories，生成返回EntityRef的trait。原始模板无条件保留@ImplementedBy及实现依赖；即使多模块把接口放kernel、实现放glue，反向依赖仍在。di-poc整理后的接口不能证明raw BC模板已清除此债。

JS正式Play service的templates/aggregate-repository.mjs#repositoryTemplate从reference-layout/domain-dependencies生成<Name>Repository[K]具体类。寻址合同与本地缓存实现是一个文件的两项职责，不另发接口trait，也不提供ClusterSharding保证。legacy repository-dsl的sharding接口与retired scala-cqrs-es为独立兼容/历史路径。

各profile的形状、所有权和限制见能力矩阵（公共包 docs/artifacts/CAPABILITY-MATRIX.md）。同文件多职责不能重复计数；源码检查不等于目标编译、跨JVM验证或最终批准。

## 结构与命名

BC application/aggregates/repository/<Name>Repository为trait；JS当前<Name>Repository[K]合并本地缓存机制。相同职责采用具名profile变体，不能冒相同文件布局。

类型使用UpperCamelCase，成员使用lowerCamelCase；数据库、protobuf和稳定业务ID分别遵循各自合同。描述目标命名不授权改写现有wire名称、PID或冻结载体。

## 依赖与装配

目标是多模块接口住kernel、实现和绑定在外层，不用@ImplementedBy反向依赖glue。当前raw BC模板在多模块也保留该注解，不能仅靠移动接口文件声称已达标；JS本地具体类也不是已拆分的kernel/glue接口实现对。

## 回锚与更新

有业务知识输入的产物关联相应展开节点与最终源码位置；共享机制只关联其技术合同，不伪造业务来源。BC历史inline注释和JS外置同图映射分开解释；本批不恢复JS新增inline @knowledge。手写映射只是显式实现关系，不自动证明语义或精确span。

## 验证

同ID返回同引用、冲突拒绝和关闭边界；跨JVM定位保证只能由实际sharding/持久化测试证明。 文档覆盖、代码编译与业务运行是不同证据；只有实际执行对应检查才可以报告通过。

## 版本与实例

机器目录按各适用变体分别记录可用性/所有权，不能把一个变体的implemented传播到其他profile。

主要实例：bc-single / bc-multi / js-formal；legacy/retired另见上文。BC目录拆分与依赖方向达标是两回事，JS本地Repository的两项职责也不表示两个文件。

- `bc:src/main/scala/net/imadz/codegen/scaffolding/cqrs/template/application/AggregateRepositoryTemplate.scala` — `AggregateRepositoryTemplate`，含当前注解债。
- `js:generators/scala-cqrs-play-service/templates/aggregate-repository.mjs` — `repositoryTemplate`。
- `js:generators/repository-dsl/emit.mjs` — legacy接口生成。

来源的完整revision、文件摘要及清点处置在根目录的`sources.lock.json`；本页摘录不是生产认证。

### 两种实际变体

BC sharding接口返回EntityRef，kernel接口/glue实现的物理拆分已存在，但raw模板保留@ImplementedBy反向依赖；root显式bind是目标及参考装配方式，不是模板已完成的普遍保证。银行JS本地形则为：

```scala
final class CreditBalanceRepository[K](
    identity: K => String,
    behavior: (K, String) => Behavior[Command],
    spawn: (String, Behavior[Command]) => ActorRef[Command])
```

它缓存Handle并在close后拒绝新查找，不等ClusterSharding，也不能停止共享ActorSystem。两种类别职责相近，但公开API、资源owner和部署保证不同。

### 类别依赖

`application-protocol`。这些是职责依赖，不代替真实Scala import或执行顺序验证。
