import cats.data.Ior.Left
import cats.effect.concurrent.Ref
import cats.effect.{ContextShift, IO, Resource, Timer}
import io.circe.syntax._
import io.circe.Json
import io.prometheus.client.CollectorRegistry
import io.prometheus.client.hotspot.DefaultExports
import org.http4s.HttpRoutes
import org.http4s.metrics.prometheus.Prometheus
import org.http4s.server.Router
import org.http4s.server.middleware.Metrics
import sttp.model.StatusCode
import sttp.tapir.docs.openapi._
import sttp.tapir.json.circe._
import sttp.tapir.openapi.circe.yaml._
import sttp.tapir.server.ServerEndpoint
import sttp.tapir.server.http4s._
import sttp.tapir.swagger.http4s.SwaggerHttp4s
import sttp.tapir.{endpoint, jsonBody, _}
import cats.syntax.all._
import scala.concurrent.ExecutionContext


package object httpEndpoints {

  implicit val ec: ExecutionContext           = scala.concurrent.ExecutionContext.Implicits.global
  implicit val cs: ContextShift[IO]           = IO.contextShift(scala.concurrent.ExecutionContext.global)
  implicit val timer: Timer[IO]               = IO.timer(ec)
  val notes: IO[Ref[IO, Map[String, Note]]] = Ref.of[IO, Map[String, Note]](Map.empty)

  def baseApiEndpoint: Endpoint[Unit, Unit, Unit, Nothing] = endpoint.in("notes")

  val createNote: ServerEndpoint[Note, (StatusCode, String), String, Nothing, IO] =
    baseApiEndpoint.post
      .in("add")
      .in(jsonBody[Note])
      .errorOut(statusCode)
      .errorOut(jsonBody[String])
      .out(jsonBody[String])
      .serverLogic(note => createNoteLogic(note))

  val readNote: ServerEndpoint[String, (StatusCode, String), Json, Nothing, IO] =
    baseApiEndpoint.get
      .in("read")
      .in(jsonBody[String])
      .errorOut(statusCode)
      .errorOut(jsonBody[String])
      .out(jsonBody[Json])
      .serverLogic(id => readNoteLogic(id))

  val updateNote: ServerEndpoint[Note, (StatusCode, String), String, Nothing, IO] =
    baseApiEndpoint.post
      .in("update")
      .in(jsonBody[Note])
      .errorOut(statusCode)
      .errorOut(jsonBody[String])
      .out(jsonBody[String])
      .serverLogic(note => updateNoteLogic(note))

  val deleteNote: ServerEndpoint[String, (StatusCode, String), String, Nothing, IO] =
    baseApiEndpoint.delete
      .in("delete")
      .in(jsonBody[String])
      .errorOut(statusCode)
      .errorOut(jsonBody[String])
      .out(jsonBody[String])
      .serverLogic(id => deleteNoteLogic(id))

  val listNote: ServerEndpoint[Unit, (StatusCode, String), Json, Nothing, IO] =
    baseApiEndpoint.get
      .in("list")
      .errorOut(statusCode)
      .errorOut(jsonBody[String])
      .out(jsonBody[Json])
      .serverLogic(unit => listNoteLogic)

  private def createNoteLogic(note: Note): IO[Either[(StatusCode, String), String]] = {
    def logic(option: Option[Note]): IO[Either[(StatusCode, String), String]] = IO {
      if (option.isDefined)
        Left(statusCode, "This ID is doesn't exists!")
      notes.flatMap(_.update(map => map + (note.id -> note)))
      Right("Aded!")
    }

    notes.flatMap(_.get.map(_.get(note.id)).flatMap(logic))
  }

  def readNoteLogic(id: String): IO[Either[(StatusCode, String), Json]] = {
    def logic(option: Option[Note]): IO[Either[(StatusCode, String), Json]] = IO {
      if (option.isDefined)
        Left(statusCode, "This ID is doesn't exists!")
      Right(option.get.asJson)
    }

    notes.flatMap(_.get.map(_.get(id)).flatMap(logic))
  }

  def updateNoteLogic(note: Note): IO[Either[(StatusCode, String), String]] = {
    def logic(option: Option[Note]):  IO[Either[(StatusCode, String), String]] = IO {
      if (option.isDefined)
        Left(statusCode, "This ID is doesn't exists!")
      notes.flatMap(_.update(map => map.updated(note.id, note)))
      Right("Note was updated!")
    }

    notes.flatMap(_.get.map(_.get(note.id)).flatMap(logic))
  }

  def deleteNoteLogic(id: String): IO[Either[(StatusCode, String), String]] = {
    def logic(option: Option[Note]):  IO[Either[(StatusCode, String), String]] = IO {
      if (option.isDefined)
        Left(statusCode, "This ID is doesn't exists!")
      notes.flatMap(_.update(_.removed(id)))
      Right("Note was removed!")
    }

    notes.flatMap(_.get.map(_.get(id)).flatMap(logic))
  }

  def listNoteLogic: IO[Either[(StatusCode, String), Json]] = {
    def logic(map: Map[String, Note]): IO[Either[(StatusCode, String), Json]] = IO {
      if (map.isEmpty)
        Left(statusCode, "Empty!")
      Right(map.toList.asJson)
    }

    notes.flatMap(_.get.flatMap(logic))
  }

  private val registry = {
    DefaultExports.initialize()
    CollectorRegistry.defaultRegistry
  }

  def noteService: Resource[IO, HttpRoutes[IO]] = {
    val serviceEndpoints = List(createNote, readNote, updateNote, deleteNote, listNote).map(_.tag("Service"))
    val docs             = serviceEndpoints.toOpenAPI("title", "veresion")
    val docsRoute        = new SwaggerHttp4s(docs.toYaml).routes[IO]
    val routes           = List(docsRoute, serviceEndpoints.toRoutes)
    Prometheus.metricsOps[IO](registry, "server").map(ops => Metrics[IO](ops)(routes.foldLeft(Router[IO]())(_ <+> _)))
  }
}
