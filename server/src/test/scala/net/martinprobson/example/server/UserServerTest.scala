package net.martinprobson.example.server

import cats.effect.IO
import net.martinprobson.example.common.model.User
import net.martinprobson.example.server.db.repository.InMemoryUserRepository
import org.http4s.{Method, Request}
import org.typelevel.log4cats.SelfAwareStructuredLogger
import org.typelevel.log4cats.slf4j.Slf4jLogger
import net.martinprobson.example.server.UserServer.userService
import org.http4s.*
import org.http4s.implicits.*
import io.circe.generic.auto.*
import io.circe.syntax.EncoderOps
import weaver.SimpleIOSuite

object UserServerTest extends SimpleIOSuite {

  def log: SelfAwareStructuredLogger[IO] = Slf4jLogger.getLogger[IO]

  def getResponse(request: Request[IO]): IO[Response[IO]] = {
    InMemoryUserRepository.empty.flatMap { ur =>
      val service: HttpRoutes[IO] = userService(ur)
      service.orNotFound.run(request)
    }
  }

  test("Invalid URL") {
    val request: Request[IO] = Request[IO](Method.GET, uri"/")
    getResponse(request).map(resp => expect(resp.status == Status.NotFound))
  }

  test("PostUser") {
    val request: Request[IO] = Request[IO](method = Method.POST, uri"http://localhost:8085/user")
      .withEntity(User(User.UNASSIGNED_USER_ID, "TestName", "TestEmail"))
    for {
      resp <- getResponse(request)
      _ <- expect(resp.status == Status.Ok).failFast
      body <- resp.bodyText.compile.toList
      _ <- expect(body.head == User(1, "TestName", "TestEmail").asJson.noSpaces).failFast
    } yield success
  }
}
