package com.jorisjonkers.personalstack.common.web

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.web.bind.annotation.ExceptionHandler
import java.lang.reflect.Modifier

/**
 * Every @ExceptionHandler method must be overridable.
 *
 * GlobalExceptionHandler is @RestControllerAdvice, which the application
 * tracing aspect matches on (@within(RestControllerAdvice)), so Spring wraps it
 * in a CGLIB proxy. CGLIB builds the proxy with Objenesis and never runs a
 * constructor, so the proxy's own fields -- including ProblemDetailSupport.log
 * -- are null. A final method cannot be overridden, so it executes on that
 * uninitialised proxy instead of being delegated to the real instance, and the
 * first log call inside it throws NullPointerException. The handler dies, the
 * original exception escapes as ServletException, and every ProblemDetail
 * response in every consuming service silently becomes a 500.
 *
 * That is not hypothetical: it shipped. The handlers were split out of the
 * annotated GlobalExceptionHandler into these unannotated base classes, and the
 * kotlin-spring allopen plugin only opens members of annotated classes -- so
 * they all became final and roughly twenty integration tests in auth-api failed
 * on missing ProblemDetail bodies and CORS headers.
 *
 * Reflection rather than a context test because this is a property of the
 * bytecode, and it is the property that actually broke.
 */
class ExceptionHandlerProxyabilityTest {
    private val handlerClasses =
        listOf(
            GlobalExceptionHandler::class.java,
            ServerExceptionHandlers::class.java,
            RequestExceptionHandlers::class.java,
            DataExceptionHandlers::class.java,
            ValidationExceptionHandlers::class.java,
            DomainExceptionHandlers::class.java,
            NotFoundExceptionHandlers::class.java,
        )

    @Test
    fun `no exception handler method is final`() {
        val finals =
            handlerClasses
                .flatMap { it.declaredMethods.asList() }
                .filter { it.isAnnotationPresent(ExceptionHandler::class.java) }
                .filter { Modifier.isFinal(it.modifiers) }
                .map { "${it.declaringClass.simpleName}.${it.name}" }

        assertThat(finals)
            .describedAs("final @ExceptionHandler methods cannot be CGLIB-proxied and will NPE on the uninitialised proxy")
            .isEmpty()
    }

    @Test
    fun `the handler hierarchy actually declares handlers`() {
        // Guards the test itself: if the classes are renamed or the handlers
        // move again, the assertion above must not pass by finding nothing.
        val handlers =
            handlerClasses
                .flatMap { it.declaredMethods.asList() }
                .count { it.isAnnotationPresent(ExceptionHandler::class.java) }

        assertThat(handlers).isGreaterThanOrEqualTo(10)
    }

    @Test
    fun `every handler class is open`() {
        val finalClasses =
            handlerClasses
                .filter { Modifier.isFinal(it.modifiers) }
                .map { it.simpleName }

        assertThat(finalClasses).isEmpty()
    }
}
