<!-- Derived from @madpolaris/truthsrc docs/artifacts/scala-cqrs/domain/domain-service.md; catalog 2.5.0; source-sha256 ab516ad2a2e671d5c55cef4e1404967004182650f5833c8d09e6537a801cd0b5. Edit the central document, not this snapshot. -->

# 纯领域服务与计算实现

类别：`domain-service`。本页属于版本化产物说明目录；源码名称、包位置和运行保证按下面的适用变体解释。

## 职责

承载跨实体值计算或领域算法，不属于某一聚合的持久状态；也不因名称叫Service而允许I/O。

## 输入与人工信息

算法、规范化/哈希含义、冲突裁决规则需要人/agent给出并独立验证。只声明函数签名不足以确定实现。

## 生产与所有权

BC由DomainServiceBuilder → DomainServiceTemplate消费SystemMetadata.domain.services生成class/object骨架；方法体仍有???，多模块当前放root，不能把目标纯度或算法完备性当成既有保证。此模板不因ApplicationServiceTemplate有MANUAL块就自动具有相同保护。

JS legacy service-dsl/emit.mjs#emitDomainService按显式kind=domain、package/name/dependencies/methods输出<name>.scala，方法同样未实现；它是真实的独立CLI生产者，不是正式typed-product注册链。复制的rules_domain_gen文件头不改变JS实际写入者。run-all条件复制的静态MoneyOps只提供BigInt最小单位算术，不是领域算法或wire codec的通用生成器。

di-poc的ContentFingerprint是kernel内维护的算法，ext的ConceptDeltaResolver是另一个实现。各profile的骨架生成、手写实现与历史静态支持分开记录，见能力矩阵（公共包 docs/artifacts/CAPABILITY-MATRIX.md）；源码存在不等于本轮生成、编译或最终批准。

## 结构与命名

domain/service或domain/services中的纯object/class；需可替换实现时通过纯计算port。ContentFingerprint的算法是维护源码，不应因模板存在声称由业务真源自动重建。

类型使用UpperCamelCase，成员使用lowerCamelCase；数据库、protobuf和稳定业务ID分别遵循各自合同。描述目标命名不授权改写现有wire名称、PID或冻结载体。

## 依赖与装配

服务依赖内层值对象与契约；ext实现可被root绑定，kernel不可静态焊接ext FQN。

## 回锚与更新

有业务知识输入的产物关联相应展开节点与最终源码位置；共享机制只关联其技术合同，不伪造业务来源。BC历史inline注释和JS外置同图映射分开解释；本批不恢复JS新增inline @knowledge。手写映射只是显式实现关系，不自动证明语义或精确span。

## 验证

算法性质、规范编码/边界/幂等及port替身测试；不让hash一致替代业务同一性定义。 文档覆盖、代码编译与业务运行是不同证据；只有实际执行对应检查才可以报告通过。

## 版本与实例

机器目录按各适用变体分别记录可用性/所有权，不能把一个变体的implemented传播到其他profile。

主要实例：bc-single / bc-multi / di-poc，以及JS legacy service-dsl与静态支持。BC多模块root骨架、di-poc kernel算法和ext实现不是同一个放置事实；legacy不表示当前正式typed链已具备同样生产能力。

- `bc:src/main/scala/net/imadz/codegen/scaffolding/cqrs/template/domain/DomainServiceTemplate.scala` — `DomainServiceTemplate`。
- `dipoc:modules/kernel/src/main/scala/net/imadz/metarch/domain/service/ContentFingerprint.scala` — `ContentFingerprint`。
- `js:generators/service-dsl/emit.mjs` — `emitDomainService`。
- `js:generators/scaffold-static/app/net/imadz/common/MoneyOps.scala` — 静态纯算术支持。

来源的完整revision、文件摘要及清点处置在根目录的`sources.lock.json`；本页摘录不是生产认证。

### 类别依赖

`value-object`。这些是职责依赖，不代替真实Scala import或执行顺序验证。

### 第二批独立模块候选

`scala-cqrs-modular-candidate-v1`新增独立Parcel参考及纯目标合同，属于reference-only，不是现役生成入口。Pure, checked parcel-capacity calculation with independent boundary expectations. 结构、兼容差异及资格见目标合同（公共包 docs/artifacts/scala-cqrs/TARGET-PROFILES.md）；真实编译/行为结果单独记录，不把参考升级为生成器或最终金样。
