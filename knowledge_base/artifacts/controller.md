# Artifact: Controller (API 控制器)

**Target**: `ControllerArtifact`
**File Pattern**: `controllers/${Name}.scala`

## 1. 核心职责
处理 HTTP 请求，解析参数，调用 Application Services，返回 JSON 响应。

## 2. 编码约束
1.  **Framework**: 继承 `play.api.mvc.BaseController`。
2.  **DI**: 使用 `@Inject` 注入 `ActorSystem`, `ClusterSharding` 以及各类 Services。
3.  **Bootstrap**:
    -   必须混入所有相关的 Bootstrap Trait (如 `CreditBalanceBootstrap`, `MoneyTransferSagaCoordinatorBootstrap`)。
    -   **关键**: 在类构造函数中调用这些 trait 的 `init...` 方法。
4.  **Format**: 定义 `implicit val format` 用于 JSON 序列化。
5.  **Action**: 使用 `Action.async` 处理异步请求。

## 3. 参考模板
```scala
@Singleton
class HomeController @Inject()(
  val system: ActorSystem,
  val sharding: ClusterSharding,
  service: CreateCreditBalanceService,
  cc: ControllerComponents
) extends BaseController with CreditBalanceBootstrap {
  
  // Bootstrap Init
  initCreditBalance(sharding)

  def create(id: String) = Action.async { ... }
}
```