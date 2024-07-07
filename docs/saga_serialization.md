# Saga Transaction Coordinator 和 Serialization

## 背景
在分布式事务协调器对各个事务参与方（资源提供方）协调过程中一定会涉及到进程间通信，这会涉及通信协议的 Serialization；除此之外，在 Event Sourcing 范式下实现的 Saga Transaction Coordinator 还涉及到 Event Journal 和 Snapshot 的存储，这也有一个 Serialization，往往为了保证 Event 或 Snapshot 的向后兼容， 往往 protobuf 是常见的选择。

### Actor Message 通信的序列化
在进程间 Actor Message 通信的场景中，通常有以下几个环节：
- 可以选择让 Actor Message 继承一个 Marker 接口，例如 org.some.CborSerializable，
```scala
package net.imadz.common

trait CborSerializable
```
- 然后在配置文件中把这个 Marker接口绑定到某个序列化器上

例如使用 JacksonJsonSerializer 对标签了net.imadz.common.CborSerializable的接口进行序列化操作 
```
akka {
  loglevel = DEBUG
   projection {
     jdbc.blocking-jdbc-dispatcher.thread-pool-executor.fixed-pool-size = 10
     jdbc.dialect = mysql-dialect
   }
   actor {
      serializers {
        jackson-json = "akka.serialization.jackson.JacksonJsonSerializer"
        proto = "akka.remote.serialization.ProtobufSerializer"
     }
     serialization-bindings {
       "net.imadz.common.CborSerializable" = jackson-cbor
       "com.google.protobuf.Message" = proto
     }
   }
```
或者使用自定义序列化器针对特定类型进行序列化
```scala
akka {
   actor {
     serializers {

        proto = "akka.remote.serialization.ProtobufSerializer"
        saga-transaction-step = "net.imadz.infra.saga.serialization.SagaTransactionStepSerializer"

     }
     serialization-bindings {
       "com.google.protobuf.Message" = proto
       "net.imadz.infra.saga.SagaTransactionStep" = saga-transaction-step

     }
     allow-java-serialization = on
     warn-about-java-serializer-usage = off
   }

}
```
- 所有实现具体的 Message 在编码时处理好序列化的特殊情况，例如在使用 json 序列化时对某些不需要序列化的属性添加 @JsonIgnore 标签或者定义标准的 case class
```scala
  sealed trait CreditCommand extends CborSerializable
  case class AddInitial(memberId: Id, initial: Int, replyTo: ActorRef[CreditBalanceConfirmation]) extends CreditCommand
```
> JacksonJsonSerializer 可以自动处理 case class，不需要额外的实现。这是因为：
> - Scala case class 默认实现了序列化接口，自动生成了很多有用的方法，包括 toString, equals, hashCode 等。
> - Jackson 有专门的 Scala 模块，可以正确处理 Scala 特有的类型和结构，包括 case class。

> JacksonJsonSerializer 会自动：
> - 序列化 case class 的所有主构造函数参数。
> - 使用字段名作为 JSON 对象的键。
> - 正确处理 Option 类型、不可变集合等 Scala 特有的类型。

>ActorRef 的序列化：
> 
> ActorRef 的序列化是一个特殊情况，因为它代表了一个 Actor 的引用，而不是简单的数据。Akka 对 ActorRef 的序列化有特殊处理：
> 
> 在本地系统内，ActorRef 不需要真正的序列化，因为它们可以直接传递。
> 在远程通信中，Akka 会将 ActorRef 序列化为一个特殊的格式，包含足够的信息以在远程系统中重新构造对应的 ActorRef。
> 这个过程通常由 Akka 的内部序列化器处理，而不是 JacksonJsonSerializer。Akka 使用一个叫做 "ActorRefResolver" 的组件来处理 ActorRef 的序列化和反序列化。
> 
> 具体来说：
> 
> 当 JacksonJsonSerializer 遇到 ActorRef 时，它会调用 Akka 提供的特殊序列化方法。
> 这个方法会将 ActorRef 转换为一个字符串表示（通常是 Actor 路径）。
> 在反序列化时，这个字符串会被转回 ActorRef。

### Event Sourced 持久化
序列化器选择： 对于事件和快照的实际内容（payload），Akka 会按以下顺序选择序列化器： 
- a. 首先检查是否为该类型指定了特定的序列化器绑定。
- b. 如果没有找到特定绑定，则查看该类是否实现了 Serializable 接口。
- c. 如果实现了 Serializable，并且允许 Java 序列化（allow-java-serialization = on），则使用 Java 序列化。
- d. 如果以上都不适用，Akka 会抛出异常

```scala
akka {
   actor {
     serializers {

        proto = "akka.remote.serialization.ProtobufSerializer"
        saga-transaction-step = "net.imadz.infra.saga.serialization.SagaTransactionStepSerializer"

     }
     serialization-bindings {
       "com.google.protobuf.Message" = proto
       "net.imadz.infra.saga.SagaTransactionStep" = saga-transaction-step

     }
     allow-java-serialization = on
     warn-about-java-serializer-usage = off
   }

}
```

如果想选用 protobuf 作为事件存储，
- 要么用 protobuf 定义消息再生成事件代码；
  - 优点：不需要编写适配器、不需要注册关联绑定适配器
  - 缺点：无法表达不可序列化的类型；在事件所处的领域层增加了技术复杂性，可能增加代码编写、UT的成本
- 要么单独编写scala版本的事件，再单独编写 protobuf 文件并用 scalapb 生成包装类，再编写 EventAdapter完成转换适配
  - 优点：表达灵活；在核心层的代码编写和单元测试不涉及技术复杂性，相对简单。
  - 缺点：更多工作量

使用适配器版本代码，例如：
1. 引导式需要注册安装适配器
```scala
trait CreditBalanceBootstrap {

  def initCreditBalanceAggregate(sharding: ClusterSharding): Unit = {
    val behaviorFactory: EntityContext[CreditBalanceCommand] => Behavior[CreditBalanceCommand] = { context =>
      val i = math.abs(context.entityId.hashCode % CreditBalanceAggregate.tags.size)
      val selectedTag = CreditBalanceAggregate.tags(i)
      apply(Id.of(context.entityId), selectedTag)
    }

    sharding.init(Entity(CreditBalanceAggregate.CreditBalanceEntityTypeKey)(behaviorFactory))
  }

  private def apply(userId: Id, tag: String): Behavior[CreditBalanceCommand] =
    Behaviors.logMessages(LogOptions().withLogger(LoggerFactory.getLogger("iMadz")).withLevel(Level.INFO),
      Behaviors
        .setup { actorContext =>
          EventSourcedBehavior(
            persistenceId = PersistenceId(CreditBalanceEntityTypeKey.name, userId.toString),
            emptyState = CreditBalanceEntity.empty(userId),
            commandHandler = CreditBalanceBehaviors.apply,
            eventHandler = CreditBalanceEventHandler.apply
          ).withTagger(_ => Set(tag))
            .withRetention(RetentionCriteria.snapshotEvery(numberOfEvents = 100, keepNSnapshots = 3))
            .onPersistFailure(SupervisorStrategy.restartWithBackoff(200.millis, 5.seconds, 0.1).withStashCapacity(100))
            .eventAdapter(new CreditBalanceEventAdapter)
            .snapshotAdapter(new CreditBalanceSnapshotAdapter)
        })
}

```
2. 定义协议
```protobuf
syntax = "proto3";

package net.imadz.infrastructure.proto;

// Define the Money type
message MoneyPO {
  double amount = 1;
  string currency = 2;
}
```
3. 实现适配器
```scala
abstract class EventAdapter[E, P] {

  /**
   * Convert domain event to journal event type.
   *
   * Some journal may require a specific type to be returned to them,
   * for example if a primary key has to be associated with each event then a journal
   * may require adapters to return `com.example.myjournal.EventWithPrimaryKey(event, key)`.
   *
   * The `toJournal` adaptation must be an 1-to-1 transformation.
   * It is not allowed to drop incoming events during the `toJournal` adaptation.
   *
   * @param e the application-side domain event to be adapted to the journal model
   * @return the adapted event object, possibly the same object if no adaptation was performed
   */
  def toJournal(e: E): P

  /**
   * Return the manifest (type hint) that will be provided in the `fromJournal` method.
   * Use `""` if manifest is not needed.
   */
  def manifest(event: E): String

  /**
   * Transform the event on recovery from the journal.
   * Note that this is not called in any read side so will need to be applied
   * manually when using Query.
   *
   * Convert a event from its journal model to the applications domain model.
   *
   * One event may be adapter into multiple (or none) events which should be delivered to the `EventSourcedBehavior`.
   * Use the specialised [[EventSeq.single]] method to emit exactly one event,
   * or [[EventSeq.empty]] in case the adapter is not handling this event.
   *
   * @param p event to be adapted before delivering to the `EventSourcedBehavior`
   * @param manifest optionally provided manifest (type hint) in case the Adapter has stored one
   *                 for this event, `""` if none
   * @return sequence containing the adapted events (possibly zero) which will be delivered to
   *         the `EventSourcedBehavior`
   */
  def fromJournal(p: P, manifest: String): EventSeq[E]
}
```

```scala

class CreditBalanceEventAdapter extends EventAdapter[CreditBalanceEvent, CreditEventPO.Event] {

  override def toJournal(e: CreditBalanceEvent): CreditEventPO.Event =
    e match {
      case BalanceChanged(update, timestamp) =>
        CreditEventPO.Event.BalanceChanged(
          net.imadz.infrastructure.proto.credits.BalanceChanged(
            Some(MoneyPO(update.amount.doubleValue, update.currency.getCurrencyCode)),
            timestamp
          )
        )
      // more cases
    }

  override def manifest(event: CreditBalanceEvent): String = event.getClass.getName

  override def fromJournal(p: CreditEventPO.Event, manifest: String): EventSeq[CreditBalanceEvent] =
    p match {
      case CreditEventPO.Event.BalanceChanged(po) =>
        EventSeq.single(BalanceChanged(
          po.update.map((moneyPO: MoneyPO) => {
            val currency = Currency.getInstance(moneyPO.currency)
            Money(BigDecimal(moneyPO.amount), currency)
          }).get, po.timestamp)
        )
      // more cases
    }
}
```
最终，事件在 mongodb 中的样子，注意其中的 "_sm" 和 "_h" 包含了适配器中 manifest 方法的取值，便于反序列化过程使用
```json
{
    "_id" : ObjectId("63c2fb97ed3d9076249918a6"),
    "pid" : "UserIdentity|193df855-297d-4d11-994c-27ed8a6ba0c1",
    "from" : 1,
    "to" : 1,
    "events" : [
        {
            "v" : 1,
            "pid" : "UserIdentity|193df855-297d-4d11-994c-27ed8a6ba0c1",
            "sn" : 1,
            "_t" : "ser",
            "p" : BinData(0, "v2R1c2Vyv2dwcm9maWxlv2JpZFAZPfhVKX1NEZlMJ+2Ka6DBZG5hbWVyYmFycnkxNjczNzIyNzc0ODky/2pjcmVkZW50aWFsv2Z1c2VySWRQGT34VSl9TRGZTCftimugwWh1c2VybmFtZXJiYXJyeTE2NzM3MjI3NzQ4OTJkc2FsdHgkNWIyYTUzOTUtNjM2Yi00MjRjLWE2YTctNjY2MTI2ZDY3YmZianBhc3NwaHJhc2V4YjIwMDA6czFEOVZqaEpabk5lQzdzc3Mzeld5eVVkSG9xbVNZZUc3a0JMa2I5NEF2VT06TldJeVlUVXpPVFV0TmpNMllpMDBNalJqTFdFMllUY3ROalkyTVRJMlpEWTNZbVpp/2ppc0NhbmNlbGVk9P//"),
            "_h" : "net.imadz.timedbox.identity_context.domain.entity.UserEntity$UserCreated",
            "_sm" : "net.imadz.timedbox.identity_context.domain.entity.UserEntity$UserCreated",
            "_si" : 33,
            "_w" : "688c988a-237d-4709-996f-9d5a1051b1d6"
        }
    ],
    "v" : 1
}
```

### 总结
两种序列化场景有所不同，都可以使用 protobuf 进行序列化。
- 通信场景，
  - 序列化：根据普通对象能够序列化成 proto 字节数组；
  - 反序列化：能在具体场景中，由代码环节确定目标对象类型
- 事件存储，
  - 序列化：与前者相同
  - 反序列化：只能把manifest保存到事件的元数据写入 Journal，据此确定反序列化目标

## 问题
在 Saga Transaction Coordinator 场景中，Participant 接口中函数的返回值类型是抽象类型：
- 在通信场景中，要保证范型类型得到正确的序列化/反序列化，反序列化时缺少范型类型的 目标类型 （manifest） 信息
- 在事件存储场景中，参与方的返回值会被存储到事件当中，在反序列化过程中依然缺少该目标类型信息

## 解决方案
1. 在 protobuf message 协议中，增加范型类型的 manifest 信息
```proto
message Event {
  string payloadMessageType = 1;
  bytes payload = 2;
  // 其他字段...
}
```
2. 自定义序列化器，在序列化时根据范型类型实际 class name 写入目标类型；在反序列化时，先反序列化目标类型 class name，再对其进行反序列化
```scala
import akka.actor.ExtendedActorSystem
import akka.serialization.SerializerWithStringManifest
import com.google.protobuf.ByteString

class CustomEventSerializer(system: ExtendedActorSystem) extends SerializerWithStringManifest {
  override def identifier: Int = 1000

  override def manifest(o: AnyRef): String = o.getClass.getName

  override def toBinary(o: AnyRef): Array[Byte] = o match {
    case event: MyEvent =>
      val payloadType = event.payload.getClass.getName
      val payloadSerializer = system.serialization.findSerializerFor(event.payload)
      val payloadBytes = payloadSerializer.toBinary(event.payload)
      ProtoEvent(
        payloadMessageType = payloadType,
        payload = ByteString.copyFrom(payloadBytes)
      ).toByteArray
    case _ => throw new IllegalArgumentException(s"Cannot serialize ${o.getClass}")
  }

  override def fromBinary(bytes: Array[Byte], manifest: String): AnyRef = {
    val protoEvent = ProtoEvent.parseFrom(bytes)
    val payloadClass = system.dynamicAccess.getClassFor[AnyRef](protoEvent.payloadMessageType)
      .getOrElse(throw new ClassNotFoundException(s"Cannot find class ${protoEvent.payloadMessageType}"))
    val payloadSerializer = system.serialization.serializerFor(payloadClass)
    val payload = payloadSerializer.fromBinary(protoEvent.payload.toByteArray, payloadClass.getName)
    MyEvent(payload.asInstanceOf[MyEventPayload])
  }
}
```
3. 在配置文件中注册自定义序列化器，并完成 message 以及 event 类型与序列化器的绑定
```scala
akka {
  actor {
    serializers {
      custom-event = "com.example.CustomEventSerializer"
    }
    serialization-bindings {
      "com.example.MyEvent" = custom-event
    }
  }
}
```

实际问题中待处理的消息和事件
```scala
   // net.imadz.infra.saga.StepExecutor/OperationResponse
   case class OperationResponse[E, R](result: Either[RetryableOrNotException, R], replyTo: Option[ActorRef[StepResult[E, R]]]) extends Command

   // net.imadz.infra.saga.StepExecutor/OperationSucceeded
   case class OperationSucceeded[R](result: R) extends Event

```