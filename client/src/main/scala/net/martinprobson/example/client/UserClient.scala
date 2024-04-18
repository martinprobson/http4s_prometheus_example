package net.martinprobson.example.client

import cats.effect.{IO, IOApp}
import cats.syntax.option.none
import org.http4s.circe.{jsonOf, *}
import fs2.{Pipe, Stream}
import io.circe.generic.auto.*
import net.martinprobson.example.common.MemorySource
import net.martinprobson.example.common.model.User
import net.martinprobson.example.common.model.User.USER_ID
import org.http4s.{Method, Request}
import org.http4s.client.Client
import org.http4s.ember.client.*
import org.http4s.implicits.*
import org.typelevel.log4cats.SelfAwareStructuredLogger
import org.typelevel.log4cats.slf4j.Slf4jLogger

object UserClient extends IOApp.Simple {

  private def log: SelfAwareStructuredLogger[IO] = Slf4jLogger.getLogger[IO]

  /** Given a client and a source stream of Users:- <ol> <li>Call the
    * [[postUsers]] method to post them to the server</li> <li>Call the
    * [[getUser]] method fetch all the users we have just created</li>
    * <li>Finally, call the [[deleteUser]] method to delete all the users we
    * have created</li> </ol> <p>Note that the operations all happen in
    * parallel</p>
    * @param client
    *   The client used to execute the post .
    * @param source
    *   A source stream of users.
    */
  def userClient(client: Client[IO], source: Stream[IO, User]): IO[Unit] = for {
    /* 1. First, create the users */
    _ <- postUsers(client, source)
      .observe(processPostErrors)
      .map {
        case Left((e, u)) => -1L
        case Right(u)     => u.id
      }
      .filter(id => id > 0)
      /* 2. Then read them by calling the get endpoint */
      .parEvalMapUnorderedUnbounded(id => getUser(id, client))
      .map {
        case Some(user) => user.id
        case None       => -1L
      }
      .filter(id => id > 0)
      .evalTap(s => log.info(s"GetUser - $s"))
      /* 3. Finally delete them via the delete endpoint */
      .parEvalMapUnorderedUnbounded(n => deleteUser(n, client))
      .compile
      .drain
  } yield ()

  /** Log any errors that come back from our post user calls
    */
  private def processPostErrors
      : Pipe[IO, Either[(String, User), User], Nothing] = {
    _.filter(_.isLeft)
      .map {
        case Left((e, u)) => (e, u)
        case Right(u)     => ("", User(-1L, "", ""))
      }
      .evalTap((error, user) =>
        log.error(s"Error posting user: $user - Error: $error")
      )
      .drain
  }

  /** Build an EmberClient (with an attached [[RateLimitRetry]] policy) and pass
    * it to the [[userClient]] method.
    * @param source
    *   The source stream of Users
    */
  def program(source: Stream[IO, User]): IO[Unit] = {
    EmberClientBuilder
      .default[IO]
      .withLogger(log)
      .build
      .onFinalize(log.info("Shutdown of EmberClient"))
      .use(client => userClient(client, source))
  }

  /** Main entry point for our client program, call our program with an in
    * memory generated stream of Users
    */
  override def run: IO[Unit] = program(MemorySource(10000).stream)

  /** Get the user specified by the given id, or return None if not found.
    * @param id
    *   The userid to fetch
    * @param client
    *   The client to use
    * @return
    *   The user wrapped in an Option, or None if not found
    */
  private def getUser(id: USER_ID, client: Client[IO]): IO[Option[User]] = {
    val target = uri"http://localhost:8085/api/user" / id
    client.expect[User](target).map(u => Some(u)).handleError { _ =>
      none[User]
    }
  }

  /** Delete the user specified by the given id.
    * @param id
    *   The userid to delete
    * @param client
    *   The client to use
    * @return
    *   The user wrapped in an Option, or None if not found
    */
  private def deleteUser(id: USER_ID, client: Client[IO]): IO[String] = {
    def req(id: USER_ID): Request[IO] = Request[IO](
      method = Method.DELETE,
      uri"http://localhost:8085/api/user" / id
    )
    log.info(s"delete id $id") >>
      client.expect[String](req(id))
  }

  private def postUser(
      user: User,
      client: Client[IO]
  ): IO[Either[(String, User), User]] = {
    def req(user: User): Request[IO] =
      Request[IO](method = Method.POST, uri"http://localhost:8085/api/user")
        .withEntity(user)
    log.info(s"call $user") >>
      client
        .expect(req(user))(jsonOf[IO, User])
        .map(u => Right(u))
        .handleError(e => Left((e.toString, user)))
  }

  /** Given a [[net.martinprobson.example.common.Source]] stream of
    * [[net.martinprobson.example.common.model.User]]'s, call [[postUser]] to
    * post each User to the server end-point in parallel.
    * @param client
    *   A client that will handle the post call.
    * @param source
    *   The source stream of Users.
    * @return
    *   A result stream containing an Either giving the result of each post
    *   call.
    */
  private def postUsers(
      client: Client[IO],
      source: Stream[IO, User]
  ): Stream[IO, Either[(String, User), User]] = for {
    c <- Stream(client)
    result <- source
      .parEvalMapUnbounded(user => postUser(user, c))
  } yield result
}
