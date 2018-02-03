package play.boilerplate.api.client.dsl

import java.net.{InetAddress, URI}
import java.util.concurrent.ThreadLocalRandom

import com.ecwid.consul.v1.catalog.model.CatalogService
import com.ecwid.consul.v1.{ConsulClient, QueryParams}
import com.typesafe.config.Config

import scala.collection.JavaConverters._
import scala.collection.concurrent.{Map, TrieMap}
import scala.concurrent.{ExecutionContext, Future}

final class ConsulServiceLocator(client: ConsulClient, config: ConsulConfig)
                                (implicit cb: CircuitBreakersPanel)
  extends ServiceLocator(cb) {

  import ExecutionContext.Implicits.global

  private val roundRobinIndexFor: Map[String, Int] = TrieMap.empty[String, Int]

  override def locate(name: String): Future[Option[URI]] = {
    Future {
      val services = client.getCatalogService(name, QueryParams.DEFAULT)
        .getValue.asScala.toList
        .filterNot(_ == null)
      services match {
        case Nil => None
        case (head :: Nil) => toURI(head)
        case _ =>
          config.routingPolicy match {
            case RoutingPolicy.First => pickFirstInstance(services)
            case RoutingPolicy.Random => pickRandomInstance(services)
            case RoutingPolicy.RoundRobin => pickRoundRobinInstance(name, services)
          }
      }
    }
  }

  private def pickFirstInstance(services: List[CatalogService]): Option[URI] = {
    services.headOption.flatMap(toURI)
  }

  private def pickRandomInstance(services: List[CatalogService]): Option[URI] = {
    if (services.nonEmpty) {
      val idx = ThreadLocalRandom.current().nextInt(services.size - 1)
      toURI(services(idx))
    } else {
      None
    }
  }

  private def pickRoundRobinInstance(name: String, services: List[CatalogService]): Option[URI] = {
    if (services.nonEmpty) {
      roundRobinIndexFor.putIfAbsent(name, 0)
      val currentIndex = roundRobinIndexFor(name)
      val nextIndex =
        if (services.size > currentIndex + 1) currentIndex + 1
        else 0
      roundRobinIndexFor.replace(name, nextIndex)
      toURI(services(currentIndex))
    } else {
      None
    }
  }

  private def toURI(service: CatalogService): Option[URI] = {
    Option(service.getServiceAddress).map { address =>
      val serviceAddress = if (address.trim.isEmpty || address == "localhost") {
        InetAddress.getLoopbackAddress.getHostAddress
      } else address
      val servicePort = Option(service.getServicePort).getOrElse(Integer.valueOf(80))
      new URI(s"${config.scheme}://$serviceAddress:$servicePort")
    }
  }

}

object ConsulServiceLocator {

  def instance(config: Config)(implicit cb: CircuitBreakersPanel): ServiceLocator = {
    val consulConfig = ConsulConfig.fromConfig(config)
    val consulClient = new ConsulClient(consulConfig.agentHostname, consulConfig.agentPort)
    new ConsulServiceLocator(consulClient, consulConfig)
  }

}