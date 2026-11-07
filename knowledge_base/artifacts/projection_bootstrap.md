<!-- Derived from @madpolaris/truthsrc docs/artifacts/scala-cqrs/projection/projection-bootstrap.md; catalog 2.2.0; source-sha256 49141d62bea030d7eef88d64d427fc642d621ee5ed89a77b6ff0b7d4cbe3aeb5. Edit the central document, not this snapshot. -->

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
