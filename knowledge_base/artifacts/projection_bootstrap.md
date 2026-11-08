<!-- Derived from @madpolaris/truthsrc docs/artifacts/scala-cqrs/projection/projection-bootstrap.md; catalog 2.5.0; source-sha256 baa340b96f11dd4ba80d8d59847229910d184edb66a9794338457d6946198388. Edit the central document, not this snapshot. -->

# 投影启动与注册

类别：`projection-bootstrap`。本页属于版本化产物说明目录；源码名称、包位置和运行保证按下面的适用变体解释。

## 职责

装配sourceProvider、handler、projection ID与分片并注册运行生命周期。不包含未声明的领域转换规则。

## 输入与人工信息

offset存储、tag/事件来源、并发与资源owner等技术选择必须明确；重启不能随意清offset或重用他人数据库。

## 生产与所有权

- `bc-single` / `bc-multi`：BC Bootstrap 模型/模板生成投影注册，multi 放到 glue，宿主控制启动。
- `js-formal`：`renderJournalReadside` 在 `<Store>Projection` 内生成 `initialize/start/shutdown`；应用显式创建并启动它，不生成独立 Bootstrap trait 或 ShardedDaemonProcess 注册。
- `js-legacy`：保留 `bootstrap-dsl` 的 `kind=projection`；`js-retired`：退役 `scala-cqrs-es` 的 projectionBootstrapModel 输出。旧集群注册方式不代表原生 journal projection 的运行机制。

逐 profile 的生命周期、生产链与证据层级见派生能力矩阵（公共包 docs/artifacts/CAPABILITY-MATRIX.md）。可用源码、生成接线、目标编译、行为验证和最终批准不是同一声明。

## 结构与命名

infrastructure/bootstrap/<View>Bootstrap或ApplicationBootstrap中的注册；single/multi路径不同，模块glue，root控制应用启动。

类型使用UpperCamelCase，成员使用lowerCamelCase；数据库、protobuf和稳定业务ID分别遵循各自合同。描述目标命名不授权改写现有wire名称、PID或冻结载体。

## 依赖与装配

读取配置并组合仓储/driver；停止要等待在途处理排空。source-ready、HTTP-listening和readside-caught-up是不同状态。

## 回锚与更新

有业务知识输入的产物关联相应展开节点与最终源码位置；共享机制只关联其技术合同，不伪造业务来源。BC历史inline注释和JS外置同图映射分开解释；本批不恢复JS新增inline @knowledge。手写映射只是显式实现关系，不自动证明语义或精确span。

## 验证

注册唯一性、正确来源、启动失败清理、关闭排空和新SQL库重放。 文档覆盖、代码编译与业务运行是不同证据；只有实际执行对应检查才可以报告通过。

## 版本与实例

本页按上述 profile 区分当前原生、保留/退役路径与手写参考；不跨 profile 传播实现或验收结论。完整来源 revision、摘要和生产链由目录、派生矩阵及 `sources.lock.json` 记录，本页不另抄暂定统计。

### 类别依赖

`projection-handler`、`projection-repository-implementation`。这些是职责依赖，不代替真实Scala import或执行顺序验证。

## 第六批有限 persistence-readside 候选登记

本类新增 `js-persistence-readside-candidate-v1`，由显式 `scala-cqrs-persistence-readside-v1`／`scala-cqrs-persistence-readside@1` 提供；模块为 `glue`。本段只登记实际源结构，不宣称本批已完成或已编译。

实际输出合同（可覆盖同一物理文件中的不同职责，不按合同数冒充文件数）

- `readside-runner`／`projection-runner`：Explicit initialize/start, bounded coalesced catch-up, observable failure and shutdown/drain lifecycle; only owns its own work, not the caller DataSource/ActorSystem. 路径 `modules/glue/src/main/scala/**/infrastructure/readside/*.scala`。

输入为唯一业务作者树、原七个 family2 产品及显式 persistence-readside 技术 binding；wire layout SHA 必须核对，不能自动重签。沿用 family2 kernel/glue 和唯一 writer；生成测试与独立 oracle 分开，sbt 定义存在不等任务执行。

本次登记移除旧 active evidence 引用，原24观察和旧回执在独立历史索引（公共包 docs/artifacts/evidence/capability-persistence-readside-prior-current06-history.json）保真；新观察要在生产冻结后单独采集并通过原公共验证器。本段及目录 implemented 仅表示实现存在，当前 generated/compiled/behavior/final-approved 不由本配方晋升。范围与限制见本族合同（公共包 docs/artifacts/scala-cqrs/PERSISTENCE-READSIDE.md）。

读侧文件库重放不是生产 Actor journal 耐久性；不扩张为 MySQL、任意读模型、HTTP、银行/Saga/Monarch 或架构批准。
