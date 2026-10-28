package com.example.data

/** Boundary for an integrated LLM. Generated output is data, never executable code. */
class RuleDraftService {
    fun validateGeneratedJson(raw: String): Result<MediaRuleDefinition> = MediaRuleSchema.parse(raw)
}

