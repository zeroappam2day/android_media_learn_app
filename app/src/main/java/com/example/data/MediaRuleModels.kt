package com.example.data

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index

@Entity(tableName = "media_rules")
data class MediaRule(
    @androidx.room.PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val kind: String,
    val enabled: Boolean = true,
    val currentRevision: Int = 1,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)

@Entity(
    tableName = "media_rule_revisions",
    foreignKeys = [ForeignKey(
        entity = MediaRule::class,
        parentColumns = ["id"],
        childColumns = ["ruleId"],
        onDelete = ForeignKey.CASCADE
    )],
    indices = [Index("ruleId"), Index(value = ["ruleId", "revision"], unique = true)]
)
data class MediaRuleRevision(
    @androidx.room.PrimaryKey(autoGenerate = true) val id: Long = 0,
    val ruleId: Long,
    val revision: Int,
    val definitionJson: String,
    val createdAt: Long = System.currentTimeMillis()
)

@Entity(tableName = "custom_tags", indices = [Index(value = ["name"], unique = true)])
data class CustomTag(
    @androidx.room.PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val createdAt: Long = System.currentTimeMillis()
)

@Entity(
    tableName = "media_tag_cross_ref",
    primaryKeys = ["mediaId", "tagId"],
    foreignKeys = [
        ForeignKey(entity = VideoItem::class, parentColumns = ["id"], childColumns = ["mediaId"], onDelete = ForeignKey.CASCADE),
        ForeignKey(entity = CustomTag::class, parentColumns = ["id"], childColumns = ["tagId"], onDelete = ForeignKey.CASCADE)
    ],
    indices = [Index("tagId")]
)
data class MediaTagCrossRef(val mediaId: Long, val tagId: Long)

@Entity(
    tableName = "rule_runs",
    foreignKeys = [ForeignKey(entity = MediaRule::class, parentColumns = ["id"], childColumns = ["ruleId"], onDelete = ForeignKey.SET_NULL)],
    indices = [Index("ruleId")]
)
data class RuleRun(
    @androidx.room.PrimaryKey(autoGenerate = true) val id: Long = 0,
    val ruleId: Long?,
    val ruleName: String,
    val ruleRevision: Int,
    val mode: String,
    val startedAt: Long = System.currentTimeMillis(),
    val finishedAt: Long? = null,
    val status: String = "running",
    val matchCount: Int = 0,
    val matchBytes: Long = 0,
    val errorMessage: String? = null
)

@Entity(
    tableName = "rule_matches",
    primaryKeys = ["runId", "mediaId"],
    foreignKeys = [
        ForeignKey(entity = RuleRun::class, parentColumns = ["id"], childColumns = ["runId"], onDelete = ForeignKey.CASCADE),
        ForeignKey(entity = VideoItem::class, parentColumns = ["id"], childColumns = ["mediaId"], onDelete = ForeignKey.CASCADE)
    ],
    indices = [Index("mediaId")]
)
data class RuleMatch(
    val runId: Long,
    val mediaId: Long,
    val score: Double? = null,
    val evaluator: String? = null,
    val evidence: String
)

@Entity(
    tableName = "bin_entries",
    foreignKeys = [ForeignKey(entity = VideoItem::class, parentColumns = ["id"], childColumns = ["mediaId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("addedAt")]
)
data class BinEntry(
    @androidx.room.PrimaryKey val mediaId: Long,
    val priorReviewDecision: String,
    val sourceRunId: Long? = null,
    val addedAt: Long = System.currentTimeMillis()
)

data class BinMedia(val entry: BinEntry, val media: VideoItem)

data class MediaRuleCard(
    @androidx.room.Embedded val rule: MediaRule,
    val definitionJson: String
)
