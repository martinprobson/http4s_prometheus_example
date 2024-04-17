package net.martinprobson.example.server

import cats.data.NonEmptyList
import cats.effect.{IO, IOApp, Resource}
import com.comcast.ip4s.*
import org.http4s.circe.jsonEncoder
import org.http4s.dsl.io.*
import org.http4s.{HttpRoutes, Request}
import org.http4s.ember.server.EmberServerBuilder
import org.http4s.implicits.*
import org.http4s.server.middleware.*
import io.circe.generic.auto.*
import io.circe.syntax.EncoderOps
import net.martinprobson.example.server.db.repository.{InMemoryUserRepository, UserRepository}
import net.martinprobson.example.common.model.User
import org.http4s.metrics.prometheus.{Prometheus, PrometheusExportService}
import org.http4s.server.Router
import org.typelevel.log4cats.SelfAwareStructuredLogger
import org.typelevel.log4cats.slf4j.Slf4jLogger

import java.net.InetAddress
import scala.concurrent.duration.*

object UserServer extends IOApp.Simple {

  private def log: SelfAwareStructuredLogger[IO] = Slf4jLogger.getLogger[IO]

  /** Post the user (defined in the Request) to the user repository
    * @param request
    *   The request containing the user to be posted
    * @param userRepository
    *   The repository holding user objects.
    * @return
    *   The user object that has been posted (wrapped in an IO)
    */
  private def postUser(request: Request[IO])(userRepository: UserRepository): IO[User] = for {
    user <- request.as[User]
    _ <- log.debug(s"Got User: $user")
    dbUser <- userRepository.addUser(user)
    _ <- log.debug(s"Added User: $dbUser to Db")
  } yield dbUser

  /** Get an individual users by id or Option.None if the user does not exist
   *
   * @param id The userid to retrieve.
   * @param userRepository The repository holding user objects
   * @return An Option of User wrapped in an IO
   */
  private def getUser(id: Long)(userRepository: UserRepository): IO[Option[User]] = for {
    _ <- log.info(s"In getUser: $id")
    u <- userRepository.getUser(id)
    _ <- log.info(s"Found: $u")
  } yield u

  /** List all users defined in the repository
   * @param userRepository
   *   The repository holding user objects.
   * @return
   *   A list of user objects wrapped in an IO.
   */
  private def getUsers(userRepository: UserRepository): IO[List[User]] = for {
    _ <- log.info("In getUsers")
    users <- userRepository.getUsers
    _ <- log.info(s"Got $users")
  } yield users

  /** Delete a user with the given id.
    * @param id
    *   The user id to delete
    * @param userRepository
    *   The repository holding user objects
    */
  private def deleteUser(id: Long)(userRepository: UserRepository): IO[Int] = for {
    _ <- log.info(s"In deleteUser: $id")
    response <- userRepository.deleteUser(id)
    _ <- log.info(s"Response: $response")
  } yield response

  /** Count the number of users in the repository
   *
   * @param userRepository
   *   A user repository object used to store/fetch user objects from a db
   * @return
   *   The total count of users in the repository
   */
  private def countUsers(userRepository: UserRepository): IO[Long] = for {
    _ <- log.info("In countUsers")
    count <- userRepository.countUsers
    _ <- log.info(s"Got count of $count users")
  } yield count

  /** Define a user service that responds to the defined http methods and endpoints.
    * @param userRepository
    *   A user repository object used to store/fetch user objects from a db
    * @return
    *   An HttpRoute defining our user service.
    */
  def userService(userRepository: UserRepository): HttpRoutes[IO] = HttpRoutes
    .of[IO] {
      case req @ POST -> Root / "user" =>
        postUser(req)(userRepository).flatMap(u => Ok(u.asJson))
      case GET -> Root / "user" / LongVar(id) =>
        getUser(id)(userRepository).flatMap {
          case Some(user) => Ok(user.asJson)
          case None       => NotFound()
        }
      case GET -> Root / "users" =>
        getUsers(userRepository).flatMap(u => Ok(u.asJson))
      case GET -> Root / "users" / "count" =>
        countUsers(userRepository).flatMap(c => Ok(c.asJson))
      case DELETE -> Root / "user" / LongVar(id) =>
        deleteUser(id)(userRepository).flatMap {
          case 0 => NotFound()
          case _ => Ok()
        }
    }

  /**
   * Define a meteredRouter which acts as a wrapper for our UserService.
   * <p>It sets up a metrics service and attaches it to the "/" endpoint.</p>
   * Note that:- <ol>
   *   <li>We wrap the UserService with a Metrics[IO] to actually collect the metrics.</li>
   *   <li>We define our own histogram buckets (for response times) as the defaults
   *   are too large for this simple service (that is just backed by an in-memory database).</li>
   *   </ol>
   * @param userService The original User service that we are collecting metrics for
   * @param classifier A classifier for the Prometheus labels (in this case, we are using the hostname)
   * @return A Resource[IO, HttpRoutes] - Our UserService and Metrics wrapped in a Resource.
   */
  private def meteredRouter(userService: HttpRoutes[IO], classifier: String): Resource[IO, HttpRoutes[IO]] = for {
    metricsService <- PrometheusExportService.build[IO]
    metrics <- Prometheus.metricsOps[IO](
      metricsService.collectorRegistry,
      "user_server",
      responseDurationSecondsHistogramBuckets = NonEmptyList(.002, List(0.004, 0.008, 0.016, 0.032, 0.064, 0.128, 0.256, 0.512, 1.024))
    )
    router = Router[IO](
      "/api" -> Metrics[IO](ops = metrics, classifierF = (_: Request[IO]) => Some(classifier))(
        userService
      ),
      "/" -> metricsService.routes
    )
  } yield router


  /**
   * This is our main entry point where the code will actually get executed.
   */
  override def run: IO[Unit] = program.flatMap(_ => log.info("Program exit"))

  /**
   * This is our main program: -
   * <ol>
   *   <li>Get the hostname for use as a classifier for our Prometheus metrics.</li>
   *   <li>Create a [[UserRepository]], we are just using an [[InMemoryUserRepository]] for this simple example</li>
   *   <li>Setup our Http routes (meteredRouter and UserService)</li>
   *   <li>Start a Server via a [[ org.http4s.ember.server.EmberServerBuilder ]]</li>
   * </ol>
   */
  private val program = (for {
    hostname <- Resource.eval(IO(InetAddress.getLocalHost.getHostName))
    userRepository <- Resource.eval(InMemoryUserRepository.empty)
    routes <- meteredRouter(userService(userRepository), hostname).onFinalize(log.info("Finalize of meteredRouter"))
    server <- EmberServerBuilder
      .default[IO]
      .withHost(ipv4"0.0.0.0")
      .withPort(port"8085")
      .withHttpApp(routes.orNotFound)
      .withShutdownTimeout(10.seconds)
      .withLogger(log)
      .build
      .onFinalize(log.info("Shutdown of EmberServer"))
  } yield server).use(_ => log.info(s"Starting: ${InetAddress.getLocalHost.getHostName}") >> IO.never)
}
