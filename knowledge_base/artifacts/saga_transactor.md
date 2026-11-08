<!-- Derived from @madpolaris/truthsrc docs/artifacts/scala-cqrs/saga/saga-transactor.md; catalog 2.5.0; source-sha256 69e71952789df8fe93667009139171928ccf5b17948028b0bee2562b3093bea3. Edit the central document, not this snapshot. -->

# Saga事务门面与声明

类别：`saga-transactor`。本页属于版本化产物说明目录；源码名称、包位置和运行保证按下面的适用变体解释。

## 职责

描述跨聚合TCC事务参与顺序和启动/查询门面，不把Saga等同于Monarch步骤队列。

## 输入与人工信息

参与者、resource、Prepare/Commit/Compensate、交易身份、输入、定义版本和业务结果需要显式声明。

## 生产与所有权

- `saga-strict`提供运行库definition合同；`js-formal`生成具体Definition/TechnicalPorts，`bank-p23`注入手写I/O与actor owner，不能把具体声明与库协调器合并。
- `bc-strict`经StrictSagaModuleBuilder/Model/Template生成Module内接线，`bc-semantic-products`经ProductApplicationTemplate独立生成；`js-run-all-strict`另有旧strict source入口。
- `bc-single`生成legacy SagaDefinition；`bc-multi`的`saga-definition`未被`targetModuleOf`映射，writer退回无模块prefix的project root，不是已完成glue接线。
- `js-legacy`参考入口确实输出Args/Context/SagaDefinition/register Scala，归应用声明而非合同报告；`play-legacy`是手写transactor门面。同名`saga-definition` kind不证明新旧执行合同等价。

逐profile的生产链与缺口见CAPABILITY-MATRIX.md（公共包 docs/artifacts/CAPABILITY-MATRIX.md）。本次只校准事实，不改变P23、R2采用或OPEN retry结论。

## 结构与命名

旧Transactor/createSteps与SagaDefinition；strict使用StrictSagaDefinition、PayloadCodec和StrictParticipant。JS生成<Name>Definition是具体接线而非任意业务解释器。

类型使用UpperCamelCase，成员使用lowerCamelCase；数据库、protobuf和稳定业务ID分别遵循各自合同。描述目标命名不授权改写现有wire名称、PID或冻结载体。

## 依赖与装配

门面只使用共享Saga引擎；Commit后的独立退款是另一事务，不能以abort补偿已提交付款。

## 回锚与更新

有业务知识输入的产物关联相应展开节点与最终源码位置；共享机制只关联其技术合同，不伪造业务来源。BC历史inline注释和JS外置同图映射分开解释；本批不恢复JS新增inline @knowledge。手写映射只是显式实现关系，不自动证明语义或精确span。

## 验证

参与者计划、阶段顺序、提交/补偿边界、失败/未知的区别和独立业务oracle。 文档覆盖、代码编译与业务运行是不同证据；只有实际执行对应检查才可以报告通过。

## 版本与实例

完整profile见生产说明及矩阵；旧声明、新typed接线和运行库合同不能共享一项完成结论。测试源码不等生成/行为证据，P23冻结与OPEN retry项不变，最终批准未授予。

- `saga:src/main/scala/net/imadz/infra/saga/dsl/SagaDefinition.scala` — `SagaDefinition`（legacy）。
- `saga:src/main/scala/net/imadz/infra/saga/strict/StrictSagaDefinition.scala` — `StrictSagaDefinition`（库API）。
- `js:generators/scala-cqrs-application/render-host.mjs` — `renderDefinitions`。
- `bc:src/main/scala/net/imadz/codegen/scaffolding/cqrs/template/application/transactor/StrictSagaModuleTemplate.scala` — `generate`。
- `bc:src/main/scala/net/imadz/codegen/scaffolding/cqrs/template/products/ProductApplicationTemplate.scala` — `generate`。
- `bc:src/main/scala/net/imadz/codegen/scaffolding/cqrs/template/application/transactor/SagaDefinitionTemplate.scala` — `generate`（legacy）。
- `js:generators/saga-dsl/emit.mjs` — `emitLegacySagaSpec`。

来源revision、摘要及处置见sources.lock.json（公共包 docs/artifacts/sources.lock.json）；BC multi legacy未映射模块缺口保留。
