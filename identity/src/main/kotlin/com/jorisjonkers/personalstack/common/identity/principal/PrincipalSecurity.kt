package com.jorisjonkers.personalstack.common.identity.principal

import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.http.HttpStatus
import org.springframework.security.access.AccessDeniedException
import org.springframework.security.authentication.AbstractAuthenticationToken
import org.springframework.security.authorization.AuthorizationDecision
import org.springframework.security.authorization.AuthorizationDeniedException
import org.springframework.security.authorization.AuthorizationManager
import org.springframework.security.core.Authentication
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.web.access.AccessDeniedHandler
import org.springframework.security.web.access.intercept.RequestAuthorizationContext
import org.springframework.web.filter.OncePerRequestFilter
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.time.Clock
import java.time.Duration
import java.util.function.Supplier

/**
 * An authenticated Principal. Its authorities are the App Roles it holds, as plain strings, so
 * `hasAuthority(...)` and `@PreAuthorize` check the roles deploy-kit generates.
 */
class PrincipalAuthentication(
    private val principal: Principal,
) : AbstractAuthenticationToken(principal.roles.map { SimpleGrantedAuthority(it) }) {
    init {
        isAuthenticated = true
    }

    override fun getPrincipal(): Principal = principal

    override fun getCredentials(): Any? = null

    override fun getName(): String = principal.subject
}

/**
 * Authenticates each request with the Principal it carries. A refused request goes on
 * unauthenticated, for the app's authorization to turn away; one identity could not be asked about
 * is answered 503.
 */
class PrincipalAuthenticationFilter(
    private val reader: PrincipalReader,
) : OncePerRequestFilter() {
    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: FilterChain,
    ) {
        try {
            val principal = reader.read { request.getHeader(it) }
            SecurityContextHolder.getContext().authentication = PrincipalAuthentication(principal)
        } catch (_: PrincipalRefusedException) {
            // An unauthenticated request; the app's authorization answers it.
        } catch (e: IdentityUnavailableException) {
            logger.warn("identity could not be asked about a request's Principal", e)
            response.sendError(HttpStatus.SERVICE_UNAVAILABLE.value())
            return
        }
        filterChain.doFilter(request, response)
    }
}

/** A refusal a fresh two-factor proof would lift: a session whose proof is older than minutes. */
class StepUpRequired(
    val minutes: Int,
) : AuthorizationDecision(false)

/**
 * The policy helpers, each an [AuthorizationManager] for `authorizeHttpRequests { it.access(...) }`.
 * A refused request is answered 403, and one without a Principal 401 by the app's entry point.
 */
object PrincipalPolicies {
    /** Lets through a caller holding role; takes deploy-kit's generated App Roles as plain strings. */
    fun requireRole(role: String): AuthorizationManager<RequestAuthorizationContext> = policy { role in it.roles }

    /** Turns away a tool acting for a User, so a tool can read but never, say, delete. */
    fun refuseTools(): AuthorizationManager<RequestAuthorizationContext> = policy { it.via != Via.TOOL }

    /** Lets through only a person, never a Machine Identity. */
    fun requireUser(): AuthorizationManager<RequestAuthorizationContext> = policy { it.kind == Kind.USER }

    /** Lets through only a Machine Identity, never a person. */
    fun requireMachine(): AuthorizationManager<RequestAuthorizationContext> = policy { it.kind == Kind.MACHINE }

    /**
     * Lets through a session that proved two-factor within minutes. An older or missing proof is a
     * [StepUpRequired], which [PrincipalAccessDeniedHandler] sends to the Gate's step-up; a Personal
     * Token, a tool or a machine, which no browser carries through a step-up, is refused outright.
     */
    fun requireRecentTwoFactor(
        minutes: Int,
        clock: Clock = Clock.systemUTC(),
    ): AuthorizationManager<RequestAuthorizationContext> =
        AuthorizationManager { authentication, _ ->
            val principal = principalOf(authentication)
            val proved = principal?.twoFactorAt
            when {
                principal == null -> AuthorizationDecision(false)
                proved != null && !proved.plus(Duration.ofMinutes(minutes.toLong())).isBefore(clock.instant()) ->
                    AuthorizationDecision(true)
                principal.via == Via.SESSION -> StepUpRequired(minutes)
                else -> AuthorizationDecision(false)
            }
        }

    private fun policy(allow: (Principal) -> Boolean): AuthorizationManager<RequestAuthorizationContext> =
        AuthorizationManager { authentication, _ ->
            AuthorizationDecision(
                principalOf(authentication)?.let(allow) ?: false,
            )
        }

    private fun principalOf(authentication: Supplier<out Authentication?>): Principal? =
        (authentication.get() as? PrincipalAuthentication)?.principal
}

/**
 * Answers a refusal: a [StepUpRequired] by sending the browser to the Gate's step-up on the app's
 * own host and back to the same page, anything else 403.
 */
class PrincipalAccessDeniedHandler : AccessDeniedHandler {
    override fun handle(
        request: HttpServletRequest,
        response: HttpServletResponse,
        accessDeniedException: AccessDeniedException,
    ) {
        val stepUp = (accessDeniedException as? AuthorizationDeniedException)?.authorizationResult as? StepUpRequired
        if (stepUp == null) {
            response.sendError(HttpStatus.FORBIDDEN.value())
            return
        }
        val back = request.requestURI + request.queryString?.let { "?$it" }.orEmpty()
        val target = "$STEP_UP_PATH?minutes=${stepUp.minutes}&rd=" + URLEncoder.encode(back, StandardCharsets.UTF_8)
        response.status = HttpStatus.SEE_OTHER.value()
        response.setHeader("Location", target)
    }

    companion object {
        /** The Gate's step-up on every gated host (contract/principal mapping.json stepUp). */
        const val STEP_UP_PATH = "/.auth/step-up"
    }
}
