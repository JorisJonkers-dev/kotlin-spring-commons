package com.jorisjonkers.personalstack.common.observability

import io.opentelemetry.api.GlobalOpenTelemetry
import io.opentelemetry.api.trace.StatusCode
import io.opentelemetry.api.trace.Tracer
import org.aspectj.lang.ProceedingJoinPoint
import org.aspectj.lang.annotation.Around
import org.aspectj.lang.annotation.Aspect
import org.aspectj.lang.reflect.MethodSignature
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass
import org.springframework.context.annotation.Bean

// Wraps every method on every Spring stereotype bean under
// `com.jorisjonkers.personalstack..*` with an OTel span. Earlier
// revisions of this aspect used Micrometer's Observation API and
// relied on micrometer-tracing-bridge-otel to convert observations
// into spans; with the OTel Java agent attached the bridge silently
// fails to produce spans (the agent installs its own Tracer
// independently of Spring Boot's MicrometerTracingAutoConfiguration,
// and the bridge handler ends up writing to a no-op SDK).
//
// Going straight at the agent's `GlobalOpenTelemetry.getTracer(...)`
// is unambiguous: the span lives in the agent's context, becomes a
// child of whatever SERVER span is current (e.g. the Tomcat span
// the agent created), and ships through the agent's exporter.
@AutoConfiguration
@ConditionalOnClass(Tracer::class, Aspect::class)
class ApplicationTracingAspectAutoConfiguration {
    @Bean
    fun applicationTracingAspect(): ApplicationTracingAspect =
        ApplicationTracingAspect(GlobalOpenTelemetry.getTracer(INSTRUMENTATION_SCOPE))

    private companion object {
        // Tempo will show this as the InstrumentationScope on every
        // span emitted by the aspect — keep it unambiguous.
        private const val INSTRUMENTATION_SCOPE =
            "com.jorisjonkers.personalstack.application"
    }
}

@Aspect
class ApplicationTracingAspect(
    private val tracer: Tracer,
) {
    // Catching Throwable is correct for an AOP @Around — the wrapped
    // call can throw anything and the span must record + re-throw it.
    @Suppress("TooGenericExceptionCaught")
    @Around(POINTCUT)
    fun trace(pjp: ProceedingJoinPoint): Any? {
        val sig = pjp.signature as? MethodSignature
        val cls =
            sig?.declaringType?.simpleName ?: pjp.target?.javaClass?.simpleName ?: "Unknown"
        val method = sig?.name ?: pjp.signature.name
        val argsRepr = renderArgs(cls, pjp.args)
        val spanName = if (argsRepr.isEmpty()) "$cls.$method" else "$cls.$method($argsRepr)"
        val span = tracer.spanBuilder(spanName).startSpan()
        if (argsRepr.isNotEmpty()) {
            span.setAttribute("code.args", argsRepr)
        }
        return try {
            span.makeCurrent().use { pjp.proceed() }
        } catch (t: Throwable) {
            span.recordException(t)
            span.setStatus(StatusCode.ERROR, t.message ?: t.javaClass.simpleName)
            throw t
        } finally {
            span.end()
        }
    }

    // Render call arguments for the span name + the `code.args`
    // attribute. Each arg is `toString`-d, capped at 32 chars; the
    // joined string is capped at 80 chars total. Classes whose
    // simple name contains a likely-sensitive token are redacted
    // wholesale — a coarse but safe default given Kotlin doesn't
    // compile parameter names into bytecode without `-parameters`,
    // so per-parameter filtering isn't available cheaply.
    private fun renderArgs(
        className: String,
        args: Array<Any?>?,
    ): String {
        if (args.isNullOrEmpty()) return ""
        if (SENSITIVE_CLASS_TOKENS.any { className.contains(it, ignoreCase = true) }) {
            return "redacted"
        }
        val out = StringBuilder()
        for ((i, arg) in args.withIndex()) {
            if (i > 0) out.append(", ")
            val raw = arg?.toString() ?: "null"
            val safe =
                if (raw.length > MAX_ARG_CHARS) raw.take(MAX_ARG_CHARS - 3) + "..." else raw
            out.append(safe)
            if (out.length > MAX_TOTAL_CHARS) {
                out.setLength(MAX_TOTAL_CHARS)
                out.append("…")
                break
            }
        }
        return out.toString()
    }

    private companion object {
        private const val MAX_ARG_CHARS = 32
        private const val MAX_TOTAL_CHARS = 80

        private val SENSITIVE_CLASS_TOKENS =
            listOf(
                "Token",
                "Password",
                "Vault",
                "Credential",
                "Jwt",
                "Cipher",
                "Crypto",
                "Secret",
                "Session",
            )

        // Targets Spring stereotype-annotated classes in our packages.
        // kotlin-spring's allopen plugin opens these by default, which
        // means CGLIB can proxy them; non-stereotyped @Bean factories
        // (RequestTimingFilter, JooqQueryTimingListener, ...) remain
        // Kotlin-final and would fail CGLIB.
        //
        // Restricting to stereotypes is also semantically right: those
        // are our *business* beans — controllers, services, repositories
        // — which is exactly the layer we want to find slowness in.
        // Configuration classes are filtered out (only run at startup).
        //
        // The aspect's own classes are excluded by FQN to prevent
        // recursion; the test source (also in observability package)
        // is *not* excluded so the unit test can drive a proxy.
        private const val OBS_PKG = "com.jorisjonkers.personalstack.common.observability"
        private const val POINTCUT =
            "execution(* com.jorisjonkers.personalstack..*.*(..)) " +
                "&& (" +
                "@within(org.springframework.stereotype.Component) " +
                "|| @within(org.springframework.stereotype.Service) " +
                "|| @within(org.springframework.stereotype.Repository) " +
                "|| @within(org.springframework.stereotype.Controller) " +
                "|| @within(org.springframework.web.bind.annotation.RestController) " +
                "|| @within(org.springframework.web.bind.annotation.RestControllerAdvice) " +
                "|| @within(org.springframework.web.bind.annotation.ControllerAdvice) " +
                ") " +
                "&& !@within(org.springframework.context.annotation.Configuration) " +
                "&& !within($OBS_PKG.ApplicationTracingAspect) " +
                "&& !within($OBS_PKG.ApplicationTracingAspectAutoConfiguration) " +
                "&& !within($OBS_PKG.ActuatorObservationFilterAutoConfiguration) " +
                "&& !within($OBS_PKG.ActuatorObservationPredicate)"
    }
}
