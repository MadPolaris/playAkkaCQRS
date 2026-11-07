<!-- Derived from @madpolaris/truthsrc docs/artifacts/scala-cqrs/application/aggregate-repository-port.md; catalog 1.0.0; source-sha256 7522e5a3121924e4367d1b56967eae249985c804fe99f893b95b2d961b74e14f. Edit the central document, not this snapshot. -->

# 聚合仓储接口

类别：`aggregate-repository-port`。本页属于版本化产物说明目录；源码名称、包位置和运行保证按下面的适用变体解释。

## 职责

按业务身份取得聚合引用的应用端口，不直接表达SQL表读取。

## 输入与人工信息

身份类型和实体寻址方式必须选择：BC sharding EntityRef与当前银行本地ActorRef不是同一个运行保证。

## 生产与所有权

由列出的生产者/实现提供。BC的Scala结构与JS最终写入者必须一起判断；同文件后续JS覆写时，最终台账行归最后写入者。当前银行兼容profile只按实际已有产物标识，不由旧文档推定能力。

目录状态：`implemented`；所有权：`generated`。生成文件通过公共生成/更新通道维护；独立手写实现不是生成结果的可编辑副本。

## 结构与命名

BC application/aggregates/repository/<Name>Repository为trait；JS当前<Name>Repository[K]合并本地缓存机制。相同职责采用具名profile变体，不能冒相同文件布局。

类型使用UpperCamelCase，成员使用lowerCamelCase；数据库、protobuf和稳定业务ID分别遵循各自合同。描述目标命名不授权改写现有wire名称、PID或冻结载体。

## 依赖与装配

多模块接口住kernel，不用@ImplementedBy反向依赖glue；实现和绑定在外层。旧单模块注解是兼容变体，不是通用架构要求。

## 回锚与更新

有业务知识输入的产物关联相应展开节点与最终源码位置；共享机制只关联其技术合同，不伪造业务来源。BC历史inline注释和JS外置同图映射分开解释；本批不恢复JS新增inline @knowledge。手写映射只是显式实现关系，不自动证明语义或精确span。

## 验证

同ID返回同引用、冲突拒绝和关闭边界；跨JVM定位保证只能由实际sharding/持久化测试证明。 文档覆盖、代码编译与业务运行是不同证据；只有实际执行对应检查才可以报告通过。

## 版本与实例

机器目录按各适用变体分别记录可用性/所有权，不能把一个变体的implemented传播到其他profile。

适用：bc-single / bc-multi / js-formal。单模块和多模块是放置/装配变体，不是两套业务事实。BANK P23兼容层与BC目标模板可能保留具名差异；没有把R2运行库能力写成当前银行生成支持。

- `bc:src/main/scala/net/imadz/codegen/scaffolding/cqrs/template/application/AggregateRepositoryTemplate.scala` — `AggregateRepositoryTemplate`。
- `js:generators/scala-cqrs-play-service/render-aggregate-layers.mjs` — `renderAggregateLayers`。

来源的完整revision、文件摘要及清点处置在根目录的`sources.lock.json`；本页摘录不是生产认证。

## 两种实际变体

BC sharding接口返回EntityRef并由glue实现，接口在kernel，root负责bind。银行JS本地形则为：

```scala
final class CreditBalanceRepository[K](
    identity: K => String,
    behavior: (K, String) => Behavior[Command],
    spawn: (String, Behavior[Command]) => ActorRef[Command])
```

它缓存Handle并在close后拒绝新查找，不等ClusterSharding，也不能停止共享ActorSystem。两种类别职责相近，但公开API、资源owner和部署保证不同。

### 类别依赖

`application-protocol`。这些是职责依赖，不代替真实Scala import或执行顺序验证。
