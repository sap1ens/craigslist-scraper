package com.sap1ens

import akka.actor.{ActorRef, Props, ActorLogging, Actor}
import akka.pattern.pipe
import scala.concurrent.{ExecutionContext, Future}
import ExecutionContext.Implicits.global
import akka.routing.SmallestMailboxRouter

object ListParser {
    import PageParser._

    case class ListData(listUrl: String)
    case class ListResult(listUrl: String, pageUrls: List[String], nextPage: Option[String] = None)
    case class PageResultsList(results: List[PageResult])
    case class AddPageUrl(listUrl: String, url: String)
    case class RemovePageUrl(listUrl: String, url: String)
}

class ListParser(collectorService: ActorRef) extends Actor with ActorLogging with CollectionImplicits {

    import ListParser._
    import PageParser._
    import CollectorService._

    val pages = context.actorOf(Props(new PageParser(self)).withRouter(SmallestMailboxRouter(10)), name = "Advertisement")

    var pageUrls = Map[String, List[String]]()
    var pageResults = Map[String, List[Option[PageResult]]]()

    def receive = {
        case ListData(listUrl) => {
            val future = Future {
                val (urls, nextPage) = ParserUtil.parseAdvertisementList(listUrl)
                ListResult(listUrl, urls, nextPage)
            }

            future onFailure {
                case e: Exception => {
                    log.warning(s"Can't process $listUrl, cause: ${e.getMessage}")
                    collectorService ! RemoveListUrl(listUrl)
                }
            }

            future pipeTo self
        }
        case AddPageUrl(listUrl, url) => {
            pageUrls = pageUrls.updatedWith(listUrl, List.empty) {url :: _}

            pages ! PageData(listUrl, url)
        }
        case RemovePageUrl(listUrl, url) => {
            pageUrls = pageUrls.updatedWith(listUrl, List.empty) { urls =>
                val updatedUrls = urls.copyWithout(url)

                if(updatedUrls.isEmpty) {
                    pageResults.get(listUrl) map { results =>
                        collectorService ! PageResultsList(results.flatten.toList)
                        collectorService ! RemoveListUrl(listUrl)
                    }
                }

                updatedUrls
            }
        }
        case PageOptionalResult(listUrl, result) => {
            pageResults = pageResults.updatedWith(listUrl, List.empty) {result :: _}
        }
        case ListResult(listUrl, urls, Some(nextPage)) => {
            collectorService ! AddListUrl(nextPage)

            self ! ListResult(listUrl, urls, None)
        }
        case ListResult(listUrl, urls, None) => {
            log.debug(s"${urls.size} pages were extracted")

            if(urls.isEmpty) collectorService ! RemoveListUrl(listUrl)

            urls foreach { url =>
                self ! AddPageUrl(listUrl, url)
            }
        }
    }
}
