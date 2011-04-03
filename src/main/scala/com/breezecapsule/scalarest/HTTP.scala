package com.breezecapsule.scalarest

import org.mortbay.jetty.handler.{AbstractHandler, DefaultHandler}
import javax.servlet.http.{HttpServletRequest, HttpServletResponse}
import org.mortbay.jetty.{Request, Server}
import xml.Elem
import scala.collection.JavaConverters._
import io.Source
;


/**
 * HTTP companion object for accessing the HTTP Server API.
 */
object HTTP {

    /**
     * Create a new HTTPServer.
     */
    def createServer(port: Int = 8080) = new HTTPServer(port);


}


/**
 * Class representing a Response.
 */
sealed abstract class ResourceRepresentation();

case class ResourceNotRepresentable(httpCode: Int, details: Option[MimeResource]) extends ResourceRepresentation();

case class ResourceNotFound() extends ResourceNotRepresentable(404, None);

case class ResourceUnauthorized() extends ResourceNotRepresentable(401, None);

case class MimeResource(mimeType: String) extends ResourceRepresentation;

case class Text(text: String) extends MimeResource("text/plain");

case class Xml(xml: Elem) extends MimeResource("text/xml");

case class CharacterStream(override val mimeType:String, source: Source) extends MimeResource(mimeType);

case class ByteStream(override val mimeType:String, stream: Stream[Byte]) extends MimeResource(mimeType);


/**
 * Case class encapsulating a Request.
 */
abstract class ResourceRequest(path: String = ".*", parameters: Map[String, String] = Map());

/**
 * Case class representing HTTP GET
 */
case class Get(path: String = ".*", parameters: Map[String, String] = Map()) extends ResourceRequest(path, parameters);

/**
 * Case class representing HTTP POST
 */
case class Post(path: String = ".*", parameters: Map[String, String] = Map()) extends ResourceRequest(path, parameters);


/**
 * Companion class for Request case class.
 */
object ResourceRequest {

    /**
     * Convert a path representation in String into a List[String].
     */
    def path(path: String): List[String] = path.split("\\/").filter(_ != "").map(_.toLowerCase).toList;

}


/**
 * Lifecycle trait
 */
trait Lifecycle {

    /**
     * Start the object.
     */
    def start();

    /**
     * Stop the object.
     */
    def stop();

}

/**
 * HTTP server.
 */
class HTTPServer(port: Int) extends Lifecycle {

    /**
     * Jetty server.
     */
    private val server = new Server(port);
    private val defaultHandler = new DefaultHandler();
    private var requestRouter = new RequestRouterHandler();

    // register the request router with Jetty.
    server.addHandler(requestRouter);

    /**
     * Start the server.
     */
    def start = server.start();

    /**
     * Stop the server.
     */
    def stop = server.stop();


    /**
     * Add a reactor to the HTTPServer that will handle matching requests
     */
    def react(reactor: PartialFunction[ResourceRequest, ResourceRepresentation]) = {
        requestRouter.registerReactor(reactor);
    }


}

/**
 * Jetty handler for providing the routing logic.
 */
protected class RequestRouterHandler extends AbstractHandler {

    /**
     * List of reactors.
     */
    var reactors: List[PartialFunction[ResourceRequest, ResourceRepresentation]] = List();

    /**
     * Register a reactor.
     */
    def registerReactor(reactor: PartialFunction[ResourceRequest, ResourceRepresentation]) = {
        reactors ::= reactor;
    }

    /**
     * Handle the request from Jetty.
     */
    override def handle(target: String, request: HttpServletRequest, response: HttpServletResponse, dispatch: Int) = {

        val parameters: scala.collection.mutable.Map[String, Array[String]] =
            request.getParameterMap.asScala.asInstanceOf[scala.collection.mutable.Map[String, Array[String]]];
        val resourceRequest = Get(request.getRequestURI, parameters.mapValues(_(0)).toMap);
        reactors.find(_.isDefinedAt(resourceRequest)) match {
            case Some(reactor) => routeRequestToReactor(reactor, resourceRequest, response);
            case _ => handle404(request, response);
        }
        (request.asInstanceOf[Request]).setHandled(true);
    }


    /**
     * Route a request to the reactor and handle its response.
     */
    private def routeRequestToReactor(reactor: PartialFunction[ResourceRequest, ResourceRepresentation],
                                      request: ResourceRequest,
                                      response: HttpServletResponse) = {
        def mimeResponseHandler(r: MimeResource): Any = {
            response.setHeader("Content-Type", r.mimeType);
            r match {
                case Text(s) => response.getWriter.print(s);
                case Xml(xml) => response.getWriter.print(xml.toString);
                case ByteStream(_, stream) => {
                    val outputStream = response.getOutputStream;
                    stream.foreach(outputStream.write(_));
                }
                case CharacterStream(_, source) => {
                    val writer = response.getWriter;
                    source.foreach(writer.write(_));
                }
            }
        }
        reactor(request) match {
            case r: MimeResource =>
                response.setStatus(HttpServletResponse.SC_OK);
                mimeResponseHandler(r);
            case ResourceNotRepresentable(httpCode, None) =>
                response.setStatus(httpCode);
            case ResourceNotRepresentable(httpCode, Some(mimeDetails)) =>
                response.setStatus(httpCode);
                mimeResponseHandler(mimeDetails);
        }
    }

    /**
     * Handle the case where no reactor matches the request.
     */
    private def handle404(request: HttpServletRequest, response: HttpServletResponse) {
        response.setStatus(HttpServletResponse.SC_NOT_FOUND);
    }

}