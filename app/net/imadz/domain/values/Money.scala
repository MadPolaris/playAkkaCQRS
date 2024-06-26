package net.imadz.domain.values

import java.util.Currency

// Value Object
case class Money(amount: BigDecimal, currency: Currency) {
  def -(other: Money): Option[Money] = {
    if (this.currency == other.currency) Some(copy(amount = this.amount - other.amount))
    else None
  }

  def +(other: Money): Option[Money] = {
    if (this.currency == other.currency) Some(copy(amount = this.amount + other.amount))
    else None
  }

  def <=(other: Money): Option[Boolean] = {
    if (this.currency == other.currency) Some(this.amount <= other.amount)
    else None
  }
}
