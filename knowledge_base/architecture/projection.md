Component: CQRS Projection (Read-Side)

**Concept**:
负责将领域事件转换为读模型（Read Model）的组件。

#### A. Application Layer (Logic)
1.  **[Artifact] Projection Definition** (`ProjectionDefinitionArtifact`)
    * **File**: `application/projection/${Name}Projection.scala`
    * **职责**: 定义 `SourceProvider` (eventsByTag) 和 `ExactlyOnceProjection` 结构。
2.  **[Artifact] Projection Handler** (`ProjectionHandlerArtifact`)
    * **File**: `application/projection/${Name}Handler.scala`
    * **职责**: 继承 `JdbcHandler`。将 `EventEnvelope` 映射为 Repository 调用。
3.  **[Artifact] Repository Interface** (`ProjectionRepositoryTraitArtifact`)
    * **File**: `application/projection/repository/${Name}Repository.scala`
    * **职责**: 定义读库的增删改查接口。

#### B. Infrastructure Layer (Implementation)
4.  **[Artifact] Bootstrap** (`ProjectionBootstrapArtifact`)
    * **File**: `infrastructure/bootstrap/${Name}Bootstrap.scala`
    * **职责**: 使用 `ShardedDaemonProcess` 初始化并运行 Projection。
5.  **[Artifact] Repository Impl** (`RepositoryImplArtifact`)
    * **File**: `infrastructure/repositories/projection/${Name}RepositoryImpl.scala`
    * **职责**: 使用 `ScalikeJDBC` 实现 Repository 接口。需兼容 MySQL 方言。
**Implementation Pattern**:

**[Artifact] Projection Handler**
```scala
package net.imadz.application.projection

import akka.cluster.sharding.typed.scaladsl.ClusterSharding
import akka.projection.eventsourced
import akka.projection.eventsourced.EventEnvelope
import akka.projection.jdbc.scaladsl.JdbcHandler
import net.imadz.application.projection.repository.MonthlyIncomeAndExpenseSummaryRepository
import net.imadz.domain.entities.CreditBalanceEntity.BalanceChanged
import net.imadz.infrastructure.persistence.CreditBalanceEventAdapter
import net.imadz.infrastructure.proto.credits.{CreditBalanceEventPO => CreditEventPO}

import java.time.{Instant, LocalDateTime, ZoneId}

case class MonthlyIncomeAndExpenseSummaryProjectionHandler(sharding: ClusterSharding,
                                                           repository: MonthlyIncomeAndExpenseSummaryRepository) extends JdbcHandler[eventsourced.EventEnvelope[CreditEventPO.Event], ScalikeJdbcSession] {
  val adapter = new CreditBalanceEventAdapter

  override def process(session: ScalikeJdbcSession, envelope: EventEnvelope[CreditEventPO.Event]): Unit = {
    adapter.fromJournal(envelope.event, "").events.foreach {
      case BalanceChanged(update, timestamp) =>
        val (year, month, day) = getDateFromTimestamp(timestamp)
        val userId = envelope.persistenceId
        if (update.amount > 0)
          repository.updateIncome(userId, update.amount, year, month, day)
        else {
          repository.updateExpense(userId, -update.amount, year, month, day)
        }
      case _ =>
        ()
    }
  }

  private def getDateFromTimestamp(timestamp: Long): (Int, Int, Int) = {
    val instant = Instant.ofEpochMilli(timestamp)
    val dateTime = LocalDateTime.ofInstant(instant, ZoneId.systemDefault())
    val year = dateTime.getYear
    val month = dateTime.getMonthValue
    val day = dateTime.getDayOfMonth
    (year, month, day)
  }
}

```
**[Artifact] Projection Definition**
```scala

package net.imadz.application.projection

import akka.actor.typed.ActorSystem
import akka.cluster.sharding.typed.scaladsl.ClusterSharding
import akka.contrib.persistence.mongodb.MongoReadJournal
import akka.persistence.query.Offset
import akka.projection.eventsourced.EventEnvelope
import akka.projection.eventsourced.scaladsl.EventSourcedProvider
import akka.projection.jdbc.scaladsl.JdbcProjection
import akka.projection.scaladsl.{ExactlyOnceProjection, SourceProvider}
import akka.projection.{ProjectionId, eventsourced}
import net.imadz.application.aggregates.CreditBalanceAggregate
import net.imadz.application.projection.repository.MonthlyIncomeAndExpenseSummaryRepository
import net.imadz.infrastructure.proto.credits.CreditBalanceEventPO

object MonthlyIncomeAndExpenseSummaryProjection {

  val projectionName = "MonthlyIncomeAndExpenseSummary"


  def createProjection(system: ActorSystem[_], sharding: ClusterSharding, index: Int, repository: MonthlyIncomeAndExpenseSummaryRepository): ExactlyOnceProjection[Offset, EventEnvelope[CreditBalanceEventPO.Event]] = {
    val sourceProvider: SourceProvider[Offset, eventsourced.EventEnvelope[CreditBalanceEventPO.Event]] = EventSourcedProvider
      .eventsByTag(system = system,
        readJournalPluginId = MongoReadJournal.Identifier,
        tag = CreditBalanceAggregate.tags(index))

    JdbcProjection.exactlyOnce(
      projectionId = ProjectionId(projectionName, CreditBalanceAggregate.tags(index)),
      sourceProvider = sourceProvider,
      sessionFactory = () => new ScalikeJdbcSession(),
      handler = () => MonthlyIncomeAndExpenseSummaryProjectionHandler(sharding, repository)
    )(system)
  }
}

```
**[Artifact] Repository Interface**
```scala
package net.imadz.application.projection.repository

import com.google.inject.ImplementedBy
import net.imadz.infrastructure.repositories.projection.MonthlyIncomeAndExpenseSummaryRepositoryImpl

import scala.concurrent.Future

object MonthlyIncomeAndExpenseSummaryTable {
  case class MonthlyIncomeAndExpenseSummary(userId: String, income: BigDecimal, expense: BigDecimal, year: Int, month: Int)
}

@ImplementedBy(classOf[MonthlyIncomeAndExpenseSummaryRepositoryImpl])
trait MonthlyIncomeAndExpenseSummaryRepository {

  import MonthlyIncomeAndExpenseSummaryTable._

  def filterByPeriodLCRO(userId: String, startFromYear: Int, startFromMonth: Int, untilYear: Int, untilMonth: Int): Future[List[MonthlyIncomeAndExpenseSummary]]

  def updateIncome(userId: String, amount: BigDecimal, year: Int, month: Int, day: Int): Unit

  def updateExpense(userId: String, amount: BigDecimal, year: Int, month: Int, day: Int): Unit

}
```
**[Artifact] Repository Impl**
```scala
package net.imadz.infrastructure.repositories.projection

import net.imadz.application.projection.repository.MonthlyIncomeAndExpenseSummaryRepository
import net.imadz.application.projection.repository.MonthlyIncomeAndExpenseSummaryTable.MonthlyIncomeAndExpenseSummary
import scalikejdbc._

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future


class MonthlyIncomeAndExpenseSummaryRepositoryImpl extends MonthlyIncomeAndExpenseSummaryRepository {

  override def filterByPeriodLCRO(userId: String, startFromYear: Int, startFromMonth: Int, untilYear: Int, untilMonth: Int): Future[List[MonthlyIncomeAndExpenseSummary]] = Future {
    DB readOnly { implicit session =>
      sql"""
        SELECT user_id, income, expense, year, month
        FROM monthly_income_and_expense_summary
        WHERE user_id = ${userId}
          AND ((year > ${startFromYear}) OR (year = ${startFromYear} AND month >= ${startFromMonth}))
          AND ((year < ${untilYear}) OR (year = ${untilYear} AND month <= ${untilMonth}))
        ORDER BY year, month
      """
        .map(rs => MonthlyIncomeAndExpenseSummary(
          userId = rs.string("user_id"),
          income = rs.bigDecimal("income"),
          expense = rs.bigDecimal("expense"),
          year = rs.int("year"),
          month = rs.int("month")
        )).list.apply()
    }
  }

  override def updateIncome(userId: String, amount: BigDecimal, year: Int, month: Int, day: Int): Unit = {
    DB localTx { implicit session =>
      sql"""
        INSERT INTO monthly_income_and_expense_summary (user_id, income, year, month, day)
        VALUES (${userId}, ${amount}, ${year}, ${month}, ${day})
        ON DUPLICATE KEY UPDATE
        income = income + VALUES(income)
      """.update.apply()
    }
  }

  override def updateExpense(userId: String, amount: BigDecimal, year: Int, month: Int, day: Int): Unit = {
    DB localTx { implicit session =>
      sql"""
        INSERT INTO monthly_income_and_expense_summary (user_id, expense, year, month, day)
        VALUES (${userId}, ${amount}, ${year}, ${month}, ${day})
        ON DUPLICATE KEY UPDATE
        expense = expense + VALUES(expense)
      """.update.apply()
    }
  }
}

```

```scala

package net.imadz.application.projection

import akka.japi.function.Function
import akka.projection.jdbc.JdbcSession
import scalikejdbc.DB
import java.sql.Connection

object ScalikeJdbcSession {
  def withSession[R](f: ScalikeJdbcSession => R): R = {
    val session = new ScalikeJdbcSession()
    try {
      f(session)
    } finally {
      session.close()
    }
  }
}

/**
 * Provide database connections within a transaction to Akka Projections.
 */
final class ScalikeJdbcSession extends JdbcSession {
  val db: DB = DB.connect()
  db.autoClose(false)

  override def withConnection[Result](func: Function[Connection, Result]): Result = {
    db.begin()
    db.withinTxWithConnection(func(_))
  }

  override def commit(): Unit = db.commit()

  override def rollback(): Unit = db.rollback()

  override def close(): Unit = db.close()
}

```

```scala
package net.imadz.application.projection

import akka.actor.typed.ActorSystem
import com.typesafe.config.Config
import com.zaxxer.hikari.HikariDataSource
import scalikejdbc.config.{DBs, NoEnvPrefix, TypesafeConfig, TypesafeConfigReader}
import scalikejdbc.{ConnectionPool, DataSourceCloser, DataSourceConnectionPool}

object ScalikeJdbcSetup {

  /**
   * Initiate the ScalikeJDBC connection pool configuration and shutdown.
   * The DataSource is setup with ActorSystem's config.
   *
   * The connection pool will be closed when the actor system terminates.
   */
  def init(system: ActorSystem[_]): Unit = {
    initFromConfig(system.settings.config)
    system.whenTerminated.map { _ =>
      ConnectionPool.closeAll()
    }(scala.concurrent.ExecutionContext.Implicits.global)

  }

  /**
   * Builds a Hikari DataSource with values from jdbc-connection-settings.
   * The DataSource is then configured as the 'default' connection pool for ScalikeJDBC.
   */
  private def initFromConfig(config: Config): Unit = {

    val dbs = new DBsFromConfig(config)
    dbs.loadGlobalSettings()

    val dataSource = buildDataSource(
      config.getConfig("jdbc-connection-settings"))

    ConnectionPool.singleton(
      new DataSourceConnectionPool(
        dataSource = dataSource,
        closer = HikariCloser(dataSource)))
  }

  private def buildDataSource(config: Config): HikariDataSource = {
    val dataSource = new HikariDataSource()

    dataSource.setPoolName("read-side-connection-pool")
    dataSource.setMaximumPoolSize(
      config.getInt("connection-pool.max-pool-size"))

    val timeout = config.getDuration("connection-pool.timeout").toMillis
    dataSource.setConnectionTimeout(timeout)

    dataSource.setDriverClassName(config.getString("driver"))
    dataSource.setJdbcUrl(config.getString("url"))
    dataSource.setUsername(config.getString("user"))
    dataSource.setPassword(config.getString("password"))

    dataSource
  }

  /**
   * This is only needed to allow ScalikeJdbc to load its logging configurations from the passed Config
   */
  private class DBsFromConfig(val config: Config)
      extends DBs
      with TypesafeConfigReader
      with TypesafeConfig
      with NoEnvPrefix

  /**
   * ScalikeJdbc needs a closer for the DataSource to delegate the closing call.
   */
  private case class HikariCloser(dataSource: HikariDataSource)
      extends DataSourceCloser {
    override def close(): Unit = dataSource.close()
  }

}

```