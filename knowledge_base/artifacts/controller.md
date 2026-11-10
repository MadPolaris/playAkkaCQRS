<!-- Derived from @madpolaris/truthsrc docs/artifacts/scala-cqrs/api/http-controller.md; catalog 2.7.0; source-sha256 d71f8ae58af8512993e33cddb6d9f68076a00094a0f696f38995c0634d95aaeb. Edit the central document, not this snapshot. -->

# HTTP控制器与服务适配

类别：`http-controller`。本页属于版本化产物说明目录；源码名称、包位置和运行保证按下面的适用变体解释。

## 职责

解析请求、验证权限/CSRF、调用应用端口并翻译HTTP结果；不在controller维护业务状态机。

## 输入与人工信息

路由、输入/输出载体、错误状态、受众/权限及端口绑定必须显式；接口默认不公开所有聚合。

## 生产与所有权

- `bc-single`：BC Scala `ControllerTemplate` 生成首轮控制器，BC Node `gen_api_controllers/index.mjs` 及其模板负责后处理和最终写入；`gen_api_layer.mjs` 仅转接该入口。
- `bc-multi`：控制器放在 Play root，但签名扫描构造 `modules/kernel/src/main/scala/app/...`，实际 Scala 源在 `modules/kernel/src/main/scala/<pkg>/...`。这个路径错配尚不能算多模块 API 接通。
- `js-formal`：`renderService` / `renderPageLoader` 生成服务 Components 中的 typed action，再由 `applyEntries` 写入；不是 `api-dsl` 生成的独立控制器。
- `bc-semantic-products`：独立 Scala `ProductServiceTemplate` 生成服务 action/Loader。
- `js-legacy`：保留 `api-dsl`；`js-retired`：退役 `scala-cqrs-es-api`。文件名称相同不代表生产者或接口合同相同。

逐 profile 的生命周期、生产链与证据层级见派生能力矩阵（公共包 docs/artifacts/CAPABILITY-MATRIX.md）。可用源码、生成接线、目标编译、行为验证和最终批准不是同一声明。

## 结构与命名

BC controllers/*与JS Service Components内的typed路由是不同profile；多模块Play controller在root。

类型使用UpperCamelCase，成员使用lowerCamelCase；数据库、protobuf和稳定业务ID分别遵循各自合同。描述目标命名不授权改写现有wire名称、PID或冻结载体。

## 依赖与装配

依赖kernel端口，由root绑定ext/glue实现；HTTP200/202不等于TA或投资终态成功。

## 回锚与更新

有业务知识输入的产物关联相应展开节点与最终源码位置；共享机制只关联其技术合同，不伪造业务来源。BC历史inline注释和JS外置同图映射分开解释；本批不恢复JS新增inline @knowledge。手写映射只是显式实现关系，不自动证明语义或精确span。

## 验证

真实请求解码、未知字段/重复JSON键、权限/CSRF/冲突与页面接口测试。 文档覆盖、代码编译与业务运行是不同证据；只有实际执行对应检查才可以报告通过。

## 版本与实例

本页按上述 profile 区分当前原生、保留/退役路径与手写参考；不跨 profile 传播实现或验收结论。完整来源 revision、摘要和生产链由目录、派生矩阵及 `sources.lock.json` 记录，本页不另抄暂定统计。

### 类别依赖

`service-contract`、`http-dto`、`authorization-policy`。这些是职责依赖，不代替真实Scala import或执行顺序验证。

## 第八批有限 Play 应用入口

新增 `js-play-application-candidate-v1`，由 `scala-cqrs-play-application-v1` 提供。原先profile的缺口描述保留为对应历史，不覆盖本段的新有限入口。

- `http-errors`：Redacted consistent parser/router/server error responses.
- `http-controller`：Real Play actions, shared role/resource/CSRF gates and exact command/query result mapping.

唯一作者和显式技术/外置实现bindings经过公共单writer；源码、真实编译、HTTP行为、安装与最终批准逐层记证。有限范围及不适用项见本族合同（公共包 docs/artifacts/scala-cqrs/PLAY-APPLICATION.md）；旧回执不重签，不表示银行或完整modular target已最终批准。
