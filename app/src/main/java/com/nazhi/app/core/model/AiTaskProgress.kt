package com.nazhi.app.core.model

data class AiTaskProgress(
    val status: AiTaskStatus,
    val stage: AiTaskStage,
    val progress: Int,
    val message: String
) {
    val isRunning: Boolean
        get() = status == AiTaskStatus.RUNNING
}

enum class AiTaskStatus {
    RUNNING,
    SUCCEEDED,
    FAILED
}

enum class AiTaskStage {
    ACCEPTED,
    PREPARING_NOTES,
    LOCAL_RETRIEVAL,
    CONTEXT_READY,
    CALLING_MODEL,
    PARSING_RESULT,
    SAVING_RESULT,
    FALLBACK_DRAFTS,
    DONE,
    FAILED,
    UNKNOWN
}
