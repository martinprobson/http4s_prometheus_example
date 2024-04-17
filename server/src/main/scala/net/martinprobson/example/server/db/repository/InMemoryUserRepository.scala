package net.martinprobson.example.server.db.repository

import cats.effect.{IO, Ref}
import net.martinprobson.example.common.model.User
import net.martinprobson.example.common.model.User.USER_ID
import net.martinprobson.example.server.db.repository.InMemoryUserRepository.UserDb
import org.typelevel.log4cats.slf4j.Slf4jLogger

import scala.collection.immutable.SortedMap

/**
 * This is an implementation of the [[UserRepository]] trait that maintains
 * the users in an in memory sorted map.
 * See [[https://github.com/martinprobson/cats-http4s-fs2-doobie/blob/main/server/src/main/scala/net/martinprobson/example/server/db/repository/DoobieUserRepository.scala DoobieUserRepository.scala]]
 * for a JDBC implementation using a real database.
 * @param db The [[UserDb]] class that stores the users
 */
class InMemoryUserRepository(db: Ref[IO, UserDb]) extends UserRepository {

  override def deleteUser(id: USER_ID): IO[Int] = for {
    logger <- Slf4jLogger.create[IO]
    _ <- logger.debug(s"About to delete user: $id")
    result <- db.modify(userDb => {
      val result = if (userDb.db.contains(id)) 1 else 0
      (UserDb(userDb.db.filterNot( (key, _) => key == id),userDb.id), result)
    })
  } yield result

  override def addUser(user: User): IO[User] = for {
    logger <- Slf4jLogger.create[IO]
    _ <- logger.debug(s"About to create : $user")
    u <- db.modify(userDb => {
      val id = userDb.id + 1
      val u = UserDb(userDb.db.updated(key = id, value = User(id,user.name,user.email)),id)
      (u,u.db.get(id))
    })
    _ <- logger.debug(s"Created user: $user")
  } yield u.get

  override def getUser(id: USER_ID): IO[Option[User]] =
    db.get.map { userDb => userDb.db.get(key = id).map { user => User(id, user.name, user.email) } }

  override def getUsers: IO[List[User]] = db.get.map { userDb =>
    userDb.db.map { case (id, user) => User(id, user.name, user.email) }.toList
  }

  override def countUsers: IO[Long] = db.get.flatMap { userDb => IO(userDb.db.size.toLong) }

}

object InMemoryUserRepository {
  case class UserDb(db: SortedMap[USER_ID, User], id: USER_ID)

  def empty: IO[UserRepository] = for {
    db <- Ref[IO].of(UserDb(SortedMap.empty[USER_ID, User],0L))
  } yield new InMemoryUserRepository(db)
}
