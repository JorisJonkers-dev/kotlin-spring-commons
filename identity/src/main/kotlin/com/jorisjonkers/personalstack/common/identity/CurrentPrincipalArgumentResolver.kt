package com.jorisjonkers.personalstack.common.identity

import org.springframework.core.MethodParameter
import org.springframework.web.bind.support.WebDataBinderFactory
import org.springframework.web.context.request.NativeWebRequest
import org.springframework.web.context.request.RequestAttributes
import org.springframework.web.method.support.HandlerMethodArgumentResolver
import org.springframework.web.method.support.ModelAndViewContainer

class CurrentPrincipalArgumentResolver : HandlerMethodArgumentResolver {
    override fun supportsParameter(parameter: MethodParameter): Boolean =
        ForwardAuthPrincipal::class.java.isAssignableFrom(parameter.parameterType) ||
            parameter.hasParameterAnnotation(CurrentPrincipal::class.java)

    override fun resolveArgument(
        parameter: MethodParameter,
        mavContainer: ModelAndViewContainer?,
        webRequest: NativeWebRequest,
        binderFactory: WebDataBinderFactory?,
    ): Any? =
        webRequest.getAttribute(
            ForwardAuthIdentityFilter.ATTRIBUTE_NAME,
            RequestAttributes.SCOPE_REQUEST,
        )
}
