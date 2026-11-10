<!-- Derived from @madpolaris/truthsrc docs/artifacts/scala-cqrs/projection/projection-handler.md; catalog 2.7.0; source-sha256 d219bf4bcf6593472a2f249df8bdd233410bb46979c1d37eaf2e8040bf07eeb7. Edit the central document, not this snapshot. -->

# 事件投影处理器

类别：`projection-handler`。本页属于版本化产物说明目录；源码名称、包位置和运行保证按下面的适用变体解释。

## 职责

在投影框架回调中解码事件，执行读侧更新并明确成功/失败边界。不是Actor写侧事件handler，也不是事务Saga。

## 输入与人工信息

源事件/视图映射、decoder、identity和Repository接口、处理语义及错误政策必须已声明。

## 生产与所有权

- `bc-single`：BC Scala `ProjectionHandlerTemplate` 生成骨架，BC Node `gen_projection_handlers.mjs` 条件性补写最终 handler；两者都不是 metarch-truthsrc JS。
- `bc-multi`：该后处理的 handler/converter 路径含多余 `app/`，与 Scala 的 `modules/glue/src/main/scala/<pkg>/...` 不匹配。graph/不支持的 handler 还可能保留人工桩，不能统称完整行为。
- `js-formal`：`renderJournalReadside` 生成 `<Store>Projection.project(envelope)`，不是每个事件一类 handler。
- `js-legacy`：`projection-dsl` 先生成骨架，`smviews` 可替换内容；`js-retired`：退役 projection producer 为独立旧链。骨架或 `???` 是未完成源码，不是已实现业务行为。

逐 profile 的生命周期、生产链与证据层级见派生能力矩阵（公共包 docs/artifacts/CAPABILITY-MATRIX.md）。可用源码、生成接线、目标编译、行为验证和最终批准不是同一声明。

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

本页按上述 profile 区分当前原生、保留/退役路径与手写参考；不跨 profile 传播实现或验收结论。完整来源 revision、摘要和生产链由目录、派生矩阵及 `sources.lock.json` 记录，本页不另抄暂定统计。

### 类别依赖

`projection-apply-event`、`projection-repository-port`、`proto-converter`。这些是职责依赖，不代替真实Scala import或执行顺序验证。

## 第六批有限 persistence-readside 候选登记

本类新增 `js-persistence-readside-candidate-v1`，由显式 `scala-cqrs-persistence-readside-v1`／`scala-cqrs-persistence-readside@1` 提供；模块为 `glue`。本段只登记实际源结构，不宣称本批已完成或已编译。

实际输出合同（可覆盖同一物理文件中的不同职责，不按合同数冒充文件数）

- `readside-handler`／`projection-handler`：Same-connection sequence/duplicate checks, existing domain EventHandler fold, event/state/index/checkpoint writes; does not duplicate business rules. 路径 `modules/glue/src/main/scala/**/infrastructure/readside/*.scala`。

输入为唯一业务作者树、原七个 family2 产品及显式 persistence-readside 技术 binding；wire layout SHA 必须核对，不能自动重签。沿用 family2 kernel/glue 和唯一 writer；生成测试与独立 oracle 分开，sbt 定义存在不等任务执行。

本次登记移除旧 active evidence 引用，原24观察和旧回执在独立历史索引（公共包 docs/artifacts/evidence/capability-persistence-readside-prior-current06-history.json）保真；新观察要在生产冻结后单独采集并通过原公共验证器。本段及目录 implemented 仅表示实现存在，当前 generated/compiled/behavior/final-approved 不由本配方晋升。范围与限制见本族合同（公共包 docs/artifacts/scala-cqrs/PERSISTENCE-READSIDE.md）。

读侧文件库重放不是生产 Actor journal 耐久性；不扩张为 MySQL、任意读模型、HTTP、银行/Saga/Monarch 或架构批准。

## 第八批有限 Play 应用入口

新增 `js-play-application-candidate-v1`，由 `scala-cqrs-play-application-v1` 提供。原先profile的缺口描述保留为对应历史，不覆盖本段的新有限入口。

- `readside-handler`：Same-connection sequence/duplicate checks, existing domain EventHandler fold, event/state/index/checkpoint writes; does not duplicate business rules.

唯一作者和显式技术/外置实现bindings经过公共单writer；源码、真实编译、HTTP行为、安装与最终批准逐层记证。有限范围及不适用项见本族合同（公共包 docs/artifacts/scala-cqrs/PLAY-APPLICATION.md）；旧回执不重签，不表示银行或完整modular target已最终批准。
