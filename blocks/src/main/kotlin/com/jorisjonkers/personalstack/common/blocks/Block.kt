package com.jorisjonkers.personalstack.common.blocks

import com.fasterxml.jackson.annotation.JsonSubTypes
import com.fasterxml.jackson.annotation.JsonTypeInfo

/**
 * Discriminated-union message exchanged between the assistant-api
 * and the browser. Lives in kotlin-common so the assistant-api
 * parser, the assistant-api tests, and any future server-side
 * consumer (e.g. an external integration that wants to replay
 * transcripts) all reach for the same shape.
 *
 * The JSON wire form is a single fenced block in the agent's
 * stdout, like:
 *
 *     ```block
 *     {"kind":"choice","prompt":"approve push?","options":[...]}
 *     ```
 *
 * BlockParser extracts these, leaving plain text outside the fence
 * as `Block.Text`.
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "kind")
@JsonSubTypes(
    JsonSubTypes.Type(value = Block.Text::class, name = "text"),
    JsonSubTypes.Type(value = Block.Choice::class, name = "choice"),
    JsonSubTypes.Type(value = Block.Form::class, name = "form"),
    JsonSubTypes.Type(value = Block.Diff::class, name = "diff"),
    JsonSubTypes.Type(value = Block.ToolCall::class, name = "tool-call"),
    JsonSubTypes.Type(value = Block.Approval::class, name = "approval"),
)
sealed interface Block {
    data class Text(
        val md: String,
    ) : Block

    data class ChoiceOption(
        val id: String,
        val label: String,
    )

    data class Choice(
        val prompt: String,
        val options: List<ChoiceOption>,
    ) : Block

    data class Form(
        val schema: Map<String, Any>,
        val submitLabel: String = "Submit",
    ) : Block

    data class Diff(
        val path: String,
        val patch: String,
    ) : Block

    data class ToolCall(
        val name: String,
        val args: Map<String, Any>,
        val result: Any? = null,
    ) : Block

    data class Approval(
        val action: String,
        val payload: Map<String, Any>,
    ) : Block
}
