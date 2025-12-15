# Invariants for Meta Architecture

## Aggregates
1. package name
2. imports
   - Common
     - Akka
       - import akka.actor.typed.ActorRef
         import akka.cluster.sharding.typed.scaladsl.EntityTypeKey
         import akka.persistence.typed.scaladsl.Effect
     - common types
       - CborSerializable
       - Id
       - iMadzError
   - Specific
      - Domain classes 
        - value object classes
        - entity class
        - events and states
      - Behavior object
        - 
3. object name
4. commands and command replies classes hierarchies
5. command handler type alias
6. EntityTypeKey
7. tags

## CommandHandler