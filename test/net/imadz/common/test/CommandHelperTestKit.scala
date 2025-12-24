package net.imadz.common.test

import net.imadz.common.application.CommandHandlerReplyingBehavior.CommandHelper
import net.imadz.common.CommonTypes.iMadzError
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

trait CommandHelperTestKit extends AnyWordSpec with Matchers {

  /**
   * 通用测试方法：一个函数测完 Helper 的所有能力
   * [OPT] 优化了测试名称，使其在 IDE 中更易区分
   */
  def verifyHelper[C, S, P, R](
                                name: String,
                                helper: CommandHelper[C, S, P, R]
                              )(
                                state: S,           // 准备一个状态
                                cmd: C,             // 准备一个命令
                                expectedParam: P,   // 期望提取出的参数
                                expectedSuccess: R, // 期望的成功回复
                                expectedError: R    // 期望的失败回复 (假设错误为 genericError)
                              ): Unit = {

    val genericError = iMadzError("TEST_ERR", "Test error message")

    // 使用外层 Scope 包含名字
    s"CommandHelper: $name" should {

      // [FIX] 在具体的 Test Case 里也带上名字或动词，防止 IDE 误折叠或混淆
      s"1. [$name] should extract parameter correctly" in {
        helper.toParam(state, cmd) shouldBe expectedParam
      }

      s"2. [$name] should create failure reply correctly" in {
        helper.createFailureReply(expectedParam)(genericError) shouldBe expectedError
      }

      s"3. [$name] should create success reply correctly" in {
        helper.createSuccessReply(expectedParam)(state) shouldBe expectedSuccess
      }
    }
  }
}