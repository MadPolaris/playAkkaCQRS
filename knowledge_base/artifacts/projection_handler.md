<!-- Derived from @madpolaris/truthsrc docs/artifacts/scala-cqrs/projection/projection-handler.md; catalog 2.2.0; source-sha256 fdf25b04be23c6fcaa5fcd7d30984e8c1f1c191a42c2615afc763afab34356d4. Edit the central document, not this snapshot. -->

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
