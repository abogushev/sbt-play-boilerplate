package play.boilerplate.api.server.dsl

object Compat extends AbstractCompat {
  override type Routes = play.api.routing.Router
}