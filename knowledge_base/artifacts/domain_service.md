<!-- Derived from @madpolaris/truthsrc docs/artifacts/scala-cqrs/domain/domain-service.md; catalog 1.0.0; source-sha256 3814dbdaedcea19f8f6aab59b3a4672a24cb239c0231cc94ec50bc09234751a7. Edit the central document, not this snapshot. -->

# 纯领域服务与计算实现

类别：`domain-service`。本页属于版本化产物说明目录；源码名称、包位置和运行保证按下面的适用变体解释。

## 职责

承载跨实体值计算或领域算法，不属于某一聚合的持久状态；也不因名称叫Service而允许I/O。

## 输入与人工信息

算法、规范化/哈希含义、冲突裁决规则需要人/agent给出并独立验证。只声明函数签名不足以确定实现。

## 生产与所有权

由列出的生产者/实现提供。BC的Scala结构与JS最终写入者必须一起判断；同文件后续JS覆写时，最终台账行归最后写入者。当前银行兼容profile只按实际已有产物标识，不由旧文档推定能力。

目录状态：`implemented`；所有权：`mixed`。生成文件通过公共生成/更新通道维护；独立手写实现不是生成结果的可编辑副本。

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

适用：bc-single / bc-multi / di-poc。单模块和多模块是放置/装配变体，不是两套业务事实。BANK P23兼容层与BC目标模板可能保留具名差异；没有把R2运行库能力写成当前银行生成支持。

- `bc:src/main/scala/net/imadz/codegen/scaffolding/cqrs/template/domain/DomainServiceTemplate.scala` — `DomainServiceTemplate`。
- `dipoc:modules/kernel/src/main/scala/net/imadz/metarch/domain/service/ContentFingerprint.scala` — `ContentFingerprint`。

来源的完整revision、文件摘要及清点处置在根目录的`sources.lock.json`；本页摘录不是生产认证。

### 类别依赖

`value-object`。这些是职责依赖，不代替真实Scala import或执行顺序验证。
