package com.jorisjonkers.personalstack.common.web

import com.fasterxml.jackson.annotation.JsonInclude
import java.net.URI

/**
 * RFC 7807-flavoured problem payload. The base fields (`type`,
 * `title`, `status`, `detail`, `instance`) match the standard; the
 * remaining fields are extension members that carry context
 * specific to this stack (validation violations, traceId, the
 * upstream Kubernetes API server's verdict, …).
 *
 * Empty/null extension fields are stripped from the JSON so the
 * baseline RFC 7807 payload stays minimal for callers that ignore
 * the extensions.
 */
@JsonInclude(JsonInclude.Include.NON_EMPTY)
data class ProblemDetail(
    val type: URI = URI.create("about:blank"),
    val title: String,
    val status: Int,
    val detail: String? = null,
    val instance: URI? = null,
    val errors: List<FieldError> = emptyList(),
    /** Correlation id from MDC, set by the upstream tracing filter. */
    val traceId: String? = null,
    /** Exception class name — populated for unexpected 5xx so the
     * support workflow can grep logs without guessing. */
    val exception: String? = null,
    /** Kubernetes API server's numeric status code on a 502. */
    val kubernetesCode: Int? = null,
    /** Kubernetes API server's `Status.reason` on a 502 (e.g.
     * `Forbidden`, `Invalid`, `NotFound`). */
    val kubernetesReason: String? = null,
    /** Postgres constraint name on a 422 constraint violation
     * (e.g. `workspaces_github_link_id_fkey`). Lets the UI bind
     * the error to a specific input. */
    val constraint: String? = null,
    /** Column name parsed from the Postgres `Detail` line (e.g.
     * `github_link_id`). The UI's [FieldError] consumer uses this
     * to anchor the inline error to the right input. */
    val column: String? = null,
    /** Referenced table on a foreign-key violation (e.g.
     * `github_links`). Useful when the message needs to read
     * "the repository no longer exists" rather than naming a
     * column the user doesn't think about. */
    val referencedTable: String? = null,
    /** Upstream runner phase on a 503 from the agent-session
     * provisioning path (e.g. `Pending`, `NotReady`,
     * `ConnectionRefused`). Lets the UI render a meaningful
     * "Runner not ready, retry in 5s" inline instead of a bare
     * 5xx. */
    val runnerStatus: String? = null,
    /** Hint to the caller (and to the corresponding `Retry-After`
     * response header) for how many seconds to wait before
     * retrying a 503. */
    val retryAfterSeconds: Int? = null,
)

@JsonInclude(JsonInclude.Include.NON_NULL)
data class FieldError(
    val field: String,
    val message: String,
    val rejectedValue: Any? = null,
)
