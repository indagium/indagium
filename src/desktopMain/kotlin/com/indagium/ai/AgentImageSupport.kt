package com.indagium.ai

import com.indagium.model.AiProviderKind
import java.nio.file.Files
import java.nio.file.Path
import java.util.Base64

/**
 * Whether an agent of this kind can be handed images WITH its first message, so a model that has to judge a screen has the screen
 * before its first reply instead of asking for it with a tool call. The one place that says so; a caller that wants images checks it
 * and otherwise tells the model to fetch them with its tools.
 *
 * - The in-app providers carry user-role images: Anthropic as image content blocks, the OpenAI-compatible ones as `image_url` parts.
 * - Codex takes `localImage` items in `turn/start` (see [CodexAppServerClient.startTurn]); the files are written to the run's workspace.
 * - Claude Code takes them as content blocks of a stream-json user message on standard input (see [ClaudeCodeRequest.images]).
 *
 * That a kind CAN carry images says nothing about the chosen model: a model without vision rejects them, and the provider reports it.
 */
internal fun AiProviderKind.acceptsImagesUpFront(): Boolean = when (this) {
    AiProviderKind.OPENAI_COMPATIBLE, AiProviderKind.OPENAI_API, AiProviderKind.ANTHROPIC_API -> true
    AiProviderKind.CODEX_ACCOUNT, AiProviderKind.CLAUDE_CODE_ACCOUNT -> true
}

private const val ATTACHED_IMAGE_PREFIX = "evidence-"

/** The file extension of an attached image of [mimeType]; PNG for anything unknown. */
internal fun attachedImageExtension(mimeType: String): String = when (mimeType.lowercase()) {
    "image/jpeg", "image/jpg" -> "jpg"
    "image/webp" -> "webp"
    "image/gif" -> "gif"
    else -> "png"
}

/** Writes [images] as numbered files in [directory], in order, and returns their paths. Throws when a file cannot be written. */
internal fun writeAttachedImages(directory: Path, images: List<LlmImage>): List<Path> = images.mapIndexed { index, image ->
    val file = directory.resolve("$ATTACHED_IMAGE_PREFIX${index + 1}.${attachedImageExtension(image.mimeType)}")
    Files.write(file, Base64.getDecoder().decode(image.base64))
    file
}
