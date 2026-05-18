package net.imadz.fab.routing

import scala.util.parsing.combinator.RegexParsers

/**
 * Parser for OCAP condition expressions using [[scala.util.parsing.combinator]].
 *
 * Grammar:
 * {{{
 *   expression  ::= term ("OR" term)*
 *   term        ::= factor ("AND" factor)*
 *   factor      ::= "NOT" factor | "(" expression ")" | comparison
 *   comparison  ::= ident ("BETWEEN" number "AND" number | "<=" number | ">=" number | "<" number | ">" number)
 * }}}
 *
 * Example inputs:
 *   - "cd_nm > 34.0"
 *   - "cd_nm BETWEEN 28.0 AND 34.0"
 *   - "cd_nm > 34.0 AND rework_count < 3"
 *   - "cd_nm > 34.0 OR cd_nm < 28.0"
 *   - "cd_nm > 34.0 AND (rework_count < 3 OR cd_nm < 28.0)"
 *   - "NOT cd_nm > 34.0"
 */
object ExpressionParser extends RegexParsers {

  override val skipWhitespace = true

  /**
   * Parse a condition expression string into a [[ConditionExpression]].
   *
   * @return [[Right]](expression) on success, [[Left]](errorMessage) on failure.
   */
  def apply(input: String): Either[String, ConditionExpression] =
    parseAll(expression, input) match {
      case Success(result, _)     => Right(result)
      case Failure(msg, remaining) => Left(s"Parse failure at position ${remaining.pos}: $msg")
      case Error(msg, remaining)  => Left(s"Parse error at position ${remaining.pos}: $msg")
    }

  // ── Grammar productions ───────────────────────────────────────────
  // Uses `^^` with regular (non-partial) functions to avoid requiring
  // the `~` extractor in scope for pattern matching.

  private def expression: Parser[ConditionExpression] =
    term ~ rep("OR" ~> term) ^^ { r =>
      val head = r._1
      val tail = r._2
      if (tail.isEmpty) head else AggregateCondition(head :: tail, Or)
    }

  private def term: Parser[ConditionExpression] =
    factor ~ rep("AND" ~> factor) ^^ { r =>
      val head = r._1
      val tail = r._2
      if (tail.isEmpty) head else AggregateCondition(head :: tail, And)
    }

  private def factor: Parser[ConditionExpression] =
    ("NOT" ~> factor) ^^ (f => AggregateCondition(List(f), Not)) |
      "(" ~> expression <~ ")" |
      comparison

  private def comparison: Parser[ConditionExpression] =
    (ident ~ betweenExpr) ^^ { r =>
      val range = r._2
      MeasurementCondition(r._1, WithinRange, range._1, range._2)
    } |
      (ident ~ ("<=" ~> number)) ^^ { r => MeasurementCondition(r._1, LessThanOrEqual, r._2) } |
      (ident ~ (">=" ~> number)) ^^ { r => MeasurementCondition(r._1, GreaterThanOrEqual, r._2) } |
      (ident ~ ("<" ~> number)) ^^ { r => MeasurementCondition(r._1, LessThan, r._2) } |
      (ident ~ (">" ~> number)) ^^ { r => MeasurementCondition(r._1, GreaterThan, r._2) }

  private def betweenExpr: Parser[(Double, Double)] =
    "BETWEEN" ~> number ~ "AND" ~ number ^^ { r =>
      val lo = r._1._1
      val hi = r._2
      (lo, hi)
    }

  // ── Lexical tokens ────────────────────────────────────────────────

  private def number: Parser[Double] =
    regex("""-?\d+(\.\d+)?""".r) ^^ (_.toDouble)

  private def ident: Parser[String] =
    regex("""[a-zA-Z_][a-zA-Z0-9_]*""".r)
}
