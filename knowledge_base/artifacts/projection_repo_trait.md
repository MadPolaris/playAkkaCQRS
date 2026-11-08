<!-- Derived from @madpolaris/truthsrc docs/artifacts/scala-cqrs/projection/projection-repository-port.md; catalog 2.5.0; source-sha256 5b12ff480a1d655aefa5e04c1ef83073c7b7cff4b5313f32f54cfc4adcc4e2b1. Edit the central document, not this snapshot. -->

# 读侧仓储接口

类别：`projection-repository-port`。本页属于版本化产物说明目录；源码名称、包位置和运行保证按下面的适用变体解释。

## 职责

对读取/更新视图定义数据库无关的端口；它是领域/应用契约，不因名字含Repository就属于glue实现。

## 输入与人工信息

身份、读写原语和结果类型来自视图合同；query不等于可任意执行SQL。

## 生产与所有权

- `bc-single`：BC `ProjectionRepositoryTemplate` 生成接口，但保留实现 import 与 `@ImplementedBy`，不能笼统称数据库无关、内层纯净。
- `bc-multi`：BC 生成 kernel 接口时移除上述实现依赖；实际闭包仍应按签名/import 检查。
- `js-legacy`：`generateViewRepos → renderTrait` 返回接口，由 run-all 写入；该模板显式带实现依赖。
- `js-retired`：退役 projection 的 `deriveView → renderRepository → applyEntries` 是独立旧接口生产链。
- 原生 `<Store>Repository` 是具体 DataSource/JDBC 类及路径依赖 Session；Journal/Bindings/Ownership 是其他职责的端口，尚未确立与这里旧 Repository trait 等价的 `js-formal` 变体。

逐 profile 的生命周期、生产链与证据层级见派生能力矩阵（公共包 docs/artifacts/CAPABILITY-MATRIX.md）。可用源码、生成接线、目标编译、行为验证和最终批准不是同一声明。

## 结构与命名

application/projection/repository/<View>Repository；multi接口在kernel，签名中不能漏入具体driver/PO类型。

类型使用UpperCamelCase，成员使用lowerCamelCase；数据库、protobuf和稳定业务ID分别遵循各自合同。描述目标命名不授权改写现有wire名称、PID或冻结载体。

## 依赖与装配

root绑定glue或ext实现；移除接口上的@ImplementedBy避免kernel反向依赖。纯静态助手可留伴生对象，但不能藏创建driver工厂。

## 回锚与更新

有业务知识输入的产物关联相应展开节点与最终源码位置；共享机制只关联其技术合同，不伪造业务来源。BC历史inline注释和JS外置同图映射分开解释；本批不恢复JS新增inline @knowledge。手写映射只是显式实现关系，不自动证明语义或精确span。

## 验证

接口闭包与依赖方向，替身可测试性，组合查询/复合键签名；不能只验编译就证明真实数据库一致性。 文档覆盖、代码编译与业务运行是不同证据；只有实际执行对应检查才可以报告通过。

## 版本与实例

本页按上述 profile 区分当前原生、保留/退役路径与手写参考；不跨 profile 传播实现或验收结论。完整来源 revision、摘要和生产链由目录、派生矩阵及 `sources.lock.json` 记录，本页不另抄暂定统计。

### 类别依赖

`read-model`。这些是职责依赖，不代替真实Scala import或执行顺序验证。

### 第二批独立模块候选

`scala-cqrs-modular-candidate-v1`新增独立Parcel参考及纯目标合同，属于reference-only，不是现役生成入口。Read-only route lookup port; no generated projection, database or write-side actor lookup is claimed. 结构、兼容差异及资格见目标合同（公共包 docs/artifacts/scala-cqrs/TARGET-PROFILES.md）；真实编译/行为结果单独记录，不把参考升级为生成器或最终金样。

## 第六批有限 persistence-readside 候选登记

本类新增 `js-persistence-readside-candidate-v1`，由显式 `scala-cqrs-persistence-readside-v1`／`scala-cqrs-persistence-readside@1` 提供；模块为 `glue`。本段只登记实际源结构，不宣称本批已完成或已编译。

实际输出合同（可覆盖同一物理文件中的不同职责，不按合同数冒充文件数）

- `readside-repository`／`projection-repository`：Concrete JDBC repository plus nested transaction Session trait in glue; not an invented standalone kernel repository port. Atomic event/state/index/checkpoint and bounded keyset queries. 路径 `modules/glue/src/main/scala/**/infrastructure/readside/*.scala`。

输入为唯一业务作者树、原七个 family2 产品及显式 persistence-readside 技术 binding；wire layout SHA 必须核对，不能自动重签。沿用 family2 kernel/glue 和唯一 writer；生成测试与独立 oracle 分开，sbt 定义存在不等任务执行。

本次登记移除旧 active evidence 引用，原24观察和旧回执在独立历史索引（公共包 docs/artifacts/evidence/capability-persistence-readside-prior-current06-history.json）保真；新观察要在生产冻结后单独采集并通过原公共验证器。本段及目录 implemented 仅表示实现存在，当前 generated/compiled/behavior/final-approved 不由本配方晋升。范围与限制见本族合同（公共包 docs/artifacts/scala-cqrs/PERSISTENCE-READSIDE.md）。

读侧文件库重放不是生产 Actor journal 耐久性；不扩张为 MySQL、任意读模型、HTTP、银行/Saga/Monarch 或架构批准。
