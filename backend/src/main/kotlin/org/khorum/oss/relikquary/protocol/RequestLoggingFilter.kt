package org.khorum.oss.relikquary.protocol

import io.github.oshai.kotlinlogging.KotlinLogging
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.core.Ordered
import org.springframework.core.annotation.Order
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter

// Named `log`, not `logger`: GenericFilterBean (a superclass of OncePerRequestFilter) declares a
// `protected Log logger` (commons-logging) field that would shadow a `logger` here, causing
// `logger.info { }` to bind to Log.info(Object) and print the lambda instead of evaluating it.
private val log = KotlinLogging.logger {}

/**
 * Logs every HTTP request that reaches the backend: the method/URI on arrival (DEBUG) and the response
 * status + elapsed time on completion (INFO).
 *
 * This is the first thing to check when a request "isn't coming through": if no completion line appears
 * for it, the request never reached the server at all (a proxy/port problem, not the app). Registered at
 * [Ordered.HIGHEST_PRECEDENCE] so it runs before the Spring Security chain — requests that are rejected
 * with 401/403, or that never match a controller (404), are still logged.
 *
 * The `Authorization` header and request/response bodies are deliberately never logged.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
class RequestLoggingFilter : OncePerRequestFilter() {

    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: FilterChain,
    ) {
        val start = System.nanoTime()
        val method = request.method
        val uri = request.requestURI + (request.queryString?.let { "?$it" } ?: "")
        val from = request.remoteAddr
        log.debug { "--> $method $uri from $from" }
        try {
            filterChain.doFilter(request, response)
        } finally {
            val millis = (System.nanoTime() - start) / NANOS_PER_MILLI
            log.info { "<-- ${response.status} $method $uri (${millis}ms)" }
        }
    }

    private companion object {
        const val NANOS_PER_MILLI = 1_000_000
    }
}
