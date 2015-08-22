package uy.kohesive.kovert.vertx.internal

import com.fasterxml.jackson.databind.ObjectMapper
import io.netty.handler.codec.http.HttpHeaders
import io.vertx.core.json.Json
import io.vertx.core.logging.Logger
import io.vertx.ext.web.Route
import nl.komponents.kovenant.Promise
import uy.klutter.core.jdk.mustNotStartWith
import uy.kohesive.kovert.core.HttpErrorCode
import uy.kohesive.kovert.core.isSimpleDataType
import uy.kohesive.kovert.vertx.ContextFactory
import uy.kohesive.kovert.vertx.InterceptDispatch
import java.lang.reflect.Method
import java.math.BigDecimal
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZonedDateTime
import java.time.temporal.Temporal
import java.util.*


@suppress("UNCHECKED_CAST")
private fun setHandlerDispatchWithDataBinding(route: Route, logger: Logger,
                                              controller: Any, member: Any, dispatchInstance: Any,
                                              dispatchFunction: Method, returnType: Class<*>,
                                              paramDefs: List<ParamDef>, contextFactory: ContextFactory<*>) {
    val JSON: ObjectMapper = Json.mapper
    route.handler { routeContext ->
        val requestContext = contextFactory.createContext(routeContext)

        val request = routeContext.request()
        val useValues = linkedListOf<Any?>()
        var usedBodyJsonAlready = false

        paramDefs.forEach { param ->
            val paramValue: Any? = if (isSimpleDataType(param.type)) {
                // TODO: how does this handle nulls and missing params?
                JSON.convertValue(request.getParam(param.name), param.type)
            } else {
                // see if parameter has prefixed values in the input parameters that match
                val parmPrefix = param.name + "."
                val tempMap = request.params().entries().filter { it.getKey().startsWith(parmPrefix) }.map { it.getKey().mustNotStartWith(parmPrefix) to it.getValue() }.toMap()

                if (request.isExpectMultipart() || tempMap.isNotEmpty() || routeContext.getBodyAsString().isNullOrBlank()) {
                    if (tempMap.isEmpty()) {
                        routeContext.fail(HttpErrorCode("cannot bind parameter ${param.name} from incoming form, require variables named ${parmPrefix}*"))
                        return@handler
                    }
                    JSON.convertValue(tempMap, param.type)
                } else if (usedBodyJsonAlready) {
                    routeContext.fail(HttpErrorCode("Already consumed JSON Body, and cannot bind parameter ${param.name} from incoming path, query or multipart form parameters"))
                } else if (routeContext.request().getHeader(HttpHeaders.Names.CONTENT_TYPE) != "application/json") {
                    routeContext.fail(HttpErrorCode("No JSON Body obviously present (Content-Type header application/json missing), cannot bind parameter ${param.name} from incoming path, query or multipart form parameters"))
                } else {
                    try {
                        usedBodyJsonAlready = true
                        JSON.readValue(routeContext.getBodyAsString(), param.type)
                    } catch (ex: Throwable) {
                        routeContext.fail(HttpErrorCode("cannot bind parameter ${param.name} from incoming data, expected valid JSON.  Failed due to ${ex.getMessage()}", causedBy = ex))
                        return@handler
                    }
                }
            }
            useValues.add(paramValue)
        }

        fun sendResponse(result: Any?) {
            if (returnType.getName() == "void") {
                routeContext.fail(RuntimeException("Failutre after invocation of route function:  A route without a return type must redirect."))
                return
            } else if (returnType.isAssignableFrom(javaClass<String>()) || result is String) {
                val contentType = routeContext.response().headers().get(HttpHeaders.Names.CONTENT_TYPE)
                        ?: routeContext.getAcceptableContentType()
                        //  ?: producesContentType.nullIfBlank()
                        ?: "text/html"
                if (result == null) {
                    routeContext.fail(RuntimeException("Handler did not return any content, only a null which for HTML doesn't really make sense."))
                    return
                }
                routeContext.response().putHeader(HttpHeaders.Names.CONTENT_TYPE, contentType).end(result as String)
            } else {
                // at this point we really just need to make a JSON object because we have data not text

                // TODO: should we check if getAcceptableContentType() conflicts with application/json
                // TODO: should we check if the produces content type conflicts with application/json
                // TODO: we now return what they want as content type, but we are really creating JSON
                val contentType = routeContext.getAcceptableContentType() ?: /* producesContentType.nullIfBlank() ?: */ "application/json"
                routeContext.response().putHeader(HttpHeaders.Names.CONTENT_TYPE, contentType).end(JSON.writeValueAsString(result))
            }
        }

        useValues.addFirst(requestContext) // put the $receiver on the front

        try {
            // dispatch via intercept, or directly depending on the controller
            val result: Any? = if (controller is InterceptDispatch<*>) {
                (controller as InterceptDispatch<Any>)._internal(requestContext, member, {  dispatchFunction.invoke(dispatchInstance, *useValues.toArray()) })
            } else {
                dispatchFunction.invoke(dispatchInstance, *useValues.toArray())
            }

            // if a promise, need to wait for it to succeed or fail
            if (result != null && result is Promise<*, *>) {
                (result as Promise<Any, Throwable>).success { promisedResult ->
                    sendResponse(promisedResult)
                }.fail { rawEx ->
                    val ex = unwrapInvokeException(rawEx)
                    routeContext.fail(ex)
                }
                return@handler
            } else {
                sendResponse(result)
                return@handler
            }
        } catch (rawEx: Throwable) {
            val ex = unwrapInvokeException(rawEx)
            routeContext.fail(ex)
            return@handler
        }

    }
}





