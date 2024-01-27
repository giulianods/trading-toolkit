import zio.*
import zio.http.*
object Main extends ZIOAppDefault{
  private def getSymbols(): ZIO[Any, Throwable, Seq[String]] = {
    ZIO.succeed(Seq("a", "b", "c"))(Trace.empty)
  }
  private val app: HttpApp[Any] = {
    implicit val trace = Trace.empty
    Routes(
      // This line works fine
      Method.GET / "symbols" -> Handler.fromZIO(ZIO.succeed(Seq("a", "b", "c")).map(r => Response.text(r.mkString("\n")))),

      // this line causes a crash in the compiler (commenting this line will pass the compilation) !

      Method.GET / "symbols2" -> Handler.fromZIO(getSymbols().map(r => Response.text(r.mkString("\n")))),
    ).toHttpApp
  }
  override val run =
    Server.serve(app).provide(Server.default)
}
