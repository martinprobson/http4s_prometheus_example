package net.martinprobson.example.server.db.repository

import cats.effect.IO
import net.martinprobson.example.common.model.User
import net.martinprobson.example.common.model.User.USER_ID

//noinspection ScalaUnusedSymbol
trait UserRepository {

  def deleteUser(id: USER_ID): IO[Int]
  def addUser(user: User): IO[User]
  def getUser(id: USER_ID): IO[Option[User]]
  def getUsers: IO[List[User]]
  def countUsers: IO[Long]

}
