<!-- Derived from @madpolaris/truthsrc docs/artifacts/scala-cqrs/api/http-controller.md; catalog 1.0.0; source-sha256 481122eb4eb73924871060d7fc069814d7f6400a8fa86eef1972460dc4855ade. Edit the central document, not this snapshot. -->

# HTTP控制器与服务适配

类别：`http-controller`。本页属于版本化产物说明目录；源码名称、包位置和运行保证按下面的适用变体解释。

## 职责

解析请求、验证权限/CSRF、调用应用端口并翻译HTTP结果；不在controller维护业务状态机。

## 输入与人工信息

路由、输入/输出载体、错误状态、受众/权限及端口绑定必须显式；接口默认不公开所有聚合。

## 生产与所有权

由列出的生产者/实现提供。BC的Scala结构与JS最终写入者必须一起判断；同文件后续JS覆写时，最终台账行归最后写入者。当前银行兼容profile只按实际已有产物标识，不由旧文档推定能力。

目录状态：`implemented`；所有权：`generated`。生成文件通过公共生成/更新通道维护；独立手写实现不是生成结果的可编辑副本。

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

机器目录按各适用变体分别记录可用性/所有权，不能把一个变体的implemented传播到其他profile。

适用：bc-single / bc-multi / js-formal。单模块和多模块是放置/装配变体，不是两套业务事实。BANK P23兼容层与BC目标模板可能保留具名差异；没有把R2运行库能力写成当前银行生成支持。

- `bc:src/main/scala/net/imadz/codegen/scaffolding/cqrs/template/infrastructure/api/ControllerTemplate.scala` — `ControllerTemplate`。
- `bc:knowledge_base/design_docs/field_template/tools/gen_api_layer.mjs` — `run`。
- `js:generators/scala-cqrs-play-service/render-service.mjs` — `renderService`。

来源的完整revision、文件摘要及清点处置在根目录的`sources.lock.json`；本页摘录不是生产认证。

### 类别依赖

`service-contract`、`http-dto`、`authorization-policy`。这些是职责依赖，不代替真实Scala import或执行顺序验证。
