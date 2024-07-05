# Akka Persistence Serialization: EventSourcedBehavior vs Remoting

## EventSourcedBehavior Serialization

When using `EventSourcedBehavior` with Akka Persistence, the serialization process is handled differently from general Akka actor message serialization. Here's how it works:

1. **Event Serialization**: Akka Persistence uses `EventAdapter`s to serialize and deserialize events before storing them or reading them from the journal.

2. **Snapshot Serialization**: Similarly, `SnapshotAdapter`s are used for serializing and deserializing snapshots.

3. **Default Serialization**: By default, Akka Persistence uses Java serialization for events and snapshots if no adapters are defined.

4. **Protobuf Integration**: When using Protobuf with `EventSourcedBehavior`, you typically implement `EventAdapter`s and `SnapshotAdapter`s to convert between your domain events/states and their Protobuf representations.

Example of an EventAdapter:

```scala
class MyEventAdapter extends EventAdapter[MyEvent, proto.MyEventProto] {
  override def toJournal(event: MyEvent): proto.MyEventProto = {
    // Convert MyEvent to MyEventProto
  }

  override def fromJournal(eventProto: proto.MyEventProto, manifest: String): MyEvent = {
    // Convert MyEventProto back to MyEvent
  }
}
```

## Remoting and Cluster Communication

The serialization we discussed earlier with `SerializerWithStringManifest` is primarily used for:

1. **Remoting**: When sending messages between nodes in a clustered environment.
2. **Cluster Sharding**: For serializing messages in distributed data.
3. **Persistence Query**: When using Persistence Query to read events from the journal in a serialized form.

## Distinguishing the Two

- **EventSourcedBehavior Serialization**: Uses `EventAdapter`s and `SnapshotAdapter`s, configured in your `application.conf` for Akka Persistence.
- **Remoting Serialization**: Uses `Serializer`s (like `SerializerWithStringManifest`), configured in the actor system's serialization settings.

## Correct Usage

1. For `EventSourcedBehavior`:
    - Implement `EventAdapter`s and `SnapshotAdapter`s.
    - Configure them in `application.conf`:

      ```hocon
      akka.persistence.journal {
        plugin = "akka.persistence.journal.proto"
        proto {
          event-adapters {
            my-event-adapter = "com.example.MyEventAdapter"
          }
          event-adapter-bindings {
            "com.example.MyEvent" = my-event-adapter
          }
        }
      }
      ```

2. For Remoting/Clustering:
    - Implement `Serializer`s or `SerializerWithStringManifest`.
    - Configure them in `application.conf`:

      ```hocon
      akka.actor {
        serializers {
          proto = "akka.remote.serialization.ProtobufSerializer"
          saga-protobuf = "net.imadz.infra.saga.SagaProtobufSerializer"
        }
        serialization-bindings {
          "net.imadz.infra.saga.SagaTransactionCoordinator$Command" = saga-protobuf
          "net.imadz.infra.saga.SagaTransactionCoordinator$Event" = saga-protobuf
          "net.imadz.infra.saga.SagaTransactionCoordinator$State" = saga-protobuf
        }
      }
      ```

## Conclusion

The confusion arose from mixing these two separate concerns. For `EventSourcedBehavior`, focus on `EventAdapter`s and `SnapshotAdapter`s. The `SerializerWithStringManifest` we discussed earlier is more relevant for remoting and cluster communication.