package net.imadz.common.application

import akka.actor.typed.ActorRef
import akka.persistence.typed.scaladsl.Effect
import net.imadz.common.CommonTypes.{DomainPolicy, iMadzError}

object CommandHandlerReplyingBehavior {

  /**
   * [NEW] 抽象工厂接口：定义 Command -> Param, Param -> Reply 的转换规范
   * @tparam Command 命令类型
   * @tparam State   状态类型
   * @tparam Param   Policy 需要的参数类型
   * @tparam Reply   回复给 Actor 的消息类型
   */
  trait CommandHelper[Command, State, Param, Reply] {
    // 1. 从 Command 和 State 中提取 Policy 需要的参数
    def toParam(state: State, command: Command): Param
    // 2. 构建失败回复
    def createFailureReply(param: Param)(error: iMadzError): Reply
    // 3. 构建成功回复
    def createSuccessReply(param: Param)(state: State): Reply
  }

  /**
   * [NEW] 支持 Helper 的 Runnable Policy
   * 它将 Policy 的执行与 Helper 的转换逻辑结合起来
   */
  case class RunnablePolicyWithHelper[Event, State, Param, Reply, Command](
                                                                            policy: DomainPolicy[Event, State, Param],
                                                                            helper: CommandHelper[Command, State, Param, Reply],
                                                                            state: State,
                                                                            command: Command
                                                                          ) {
    def replyWith(replyTo: ActorRef[Reply]): Effect[Event, State] = {
      // 1. 提取参数
      val param = helper.toParam(state, command)
      // 2. 执行 Policy
      policy(state, param).fold(
        // 3a. 失败：回复错误消息
        error => Effect.reply(replyTo)(helper.createFailureReply(param)(error)),
        // 3b. 成功：持久化事件 -> 更新状态(Akka负责) -> 回复成功消息(使用新状态)
        events => Effect.persist(events).thenReply(replyTo)(helper.createSuccessReply(param))
      )
    }
  }

  /**
   * [NEW] 入口函数：注入 Policy 和 Helper
   * 用法: runReplyingPolicy(MyPolicy, MyHelper)(state, command).replyWith(replyTo)
   */
  def runReplyingPolicy[Event, State, Param, Reply, Command](
                                                              policy: DomainPolicy[Event, State, Param],
                                                              helper: CommandHelper[Command, State, Param, Reply]
                                                            )(state: State, command: Command): RunnablePolicyWithHelper[Event, State, Param, Reply, Command] =
    RunnablePolicyWithHelper(policy, helper, state, command)

  // --- 兼容旧代码 (如果还有地方在用) ---
  case class AwaitingReplyToRunnablePolicy[Event, State, Param, ReplyMessage](policy: DomainPolicy[Event, State, Param], state: State, param: Param) {
    def replyWith(replyTo: ActorRef[ReplyMessage])(leftConfirmationFactory: Param => iMadzError => ReplyMessage, rightConfirmationFactory: Param => State => ReplyMessage): Effect[Event, State] =
      policy(state, param).fold(
        error => Effect.reply(replyTo)(leftConfirmationFactory(param)(error)),
        events => Effect.persist(events).thenReply(replyTo)(rightConfirmationFactory(param)))
  }

  def runReplyingPolicy[Event, State, Param, ReplyMessage](policy: DomainPolicy[Event, State, Param])(state: State, param: Param): AwaitingReplyToRunnablePolicy[Event, State, Param, ReplyMessage] =
    AwaitingReplyToRunnablePolicy(policy = policy, state = state, param = param)
}