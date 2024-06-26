package net.imadz.common.application

import akka.actor.typed.ActorRef
import akka.persistence.typed.scaladsl.Effect
import net.imadz.common.CommonTypes.{DomainPolicy, iMadzError}

object CommandHandlerReplyingBehavior {

  case class AwaitingReplyToRunnablePolicy[Event, State, Param, ReplyMessage](policy: DomainPolicy[Event, State, Param], state: State, param: Param) {
    def replyWith(replyTo: ActorRef[ReplyMessage])(leftConfirmationFactory: Param => iMadzError => ReplyMessage, rightConfirmationFactory: Param => State => ReplyMessage): Effect[Event, State] =
      policy(state, param).fold(
        error => Effect.reply(replyTo)(leftConfirmationFactory(param)(error)),
        events => Effect.persist(events).thenReply(replyTo)(rightConfirmationFactory(param)))
  }

  def runReplyingPolicy[Event, State, Param, ReplyMessage](policy: DomainPolicy[Event, State, Param])(state: State, param: Param): AwaitingReplyToRunnablePolicy[Event, State, Param, ReplyMessage] =
    AwaitingReplyToRunnablePolicy(policy = policy, state = state, param = param)
}