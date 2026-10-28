package com.example.data

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface MediaRuleDao {
    @Query("SELECT * FROM media_rules ORDER BY updatedAt DESC, id DESC")
    fun observeRules(): Flow<List<MediaRule>>

    @Query("SELECT r.*, v.definitionJson AS definitionJson FROM media_rules r INNER JOIN media_rule_revisions v ON v.ruleId = r.id AND v.revision = r.currentRevision ORDER BY r.updatedAt DESC, r.id DESC")
    fun observeRuleCards(): Flow<List<MediaRuleCard>>

    @Insert
    suspend fun insertRule(rule: MediaRule): Long

    @Insert
    suspend fun insertRevision(revision: MediaRuleRevision): Long

    @Update
    suspend fun updateRule(rule: MediaRule)

    @Query("SELECT * FROM media_rule_revisions WHERE ruleId = :ruleId AND revision = :revision LIMIT 1")
    suspend fun revision(ruleId: Long, revision: Int): MediaRuleRevision?

    @Query("DELETE FROM media_rules WHERE id = :id")
    suspend fun deleteRule(id: Long)

    @Transaction
    suspend fun create(name: String, kind: String, definitionJson: String): Long {
        val id = insertRule(MediaRule(name = name, kind = kind))
        insertRevision(MediaRuleRevision(ruleId = id, revision = 1, definitionJson = definitionJson))
        return id
    }

    @Transaction
    suspend fun revise(rule: MediaRule, name: String, definitionJson: String) {
        val next = rule.currentRevision + 1
        insertRevision(MediaRuleRevision(ruleId = rule.id, revision = next, definitionJson = definitionJson))
        updateRule(rule.copy(name = name, currentRevision = next, updatedAt = System.currentTimeMillis()))
    }
}

@Dao
interface CustomTagDao {
    @Query("SELECT * FROM custom_tags ORDER BY name COLLATE NOCASE")
    fun observeTags(): Flow<List<CustomTag>>

    @Query("SELECT * FROM media_tag_cross_ref")
    fun observeAssignments(): Flow<List<MediaTagCrossRef>>

    @Insert
    suspend fun insert(tag: CustomTag): Long

    @Query("SELECT * FROM custom_tags WHERE name = :name COLLATE NOCASE LIMIT 1")
    suspend fun byName(name: String): CustomTag?

    @Update
    suspend fun update(tag: CustomTag)

    @Delete
    suspend fun delete(tag: CustomTag)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun assign(ref: MediaTagCrossRef)

    @Delete
    suspend fun unassign(ref: MediaTagCrossRef)

    @Query("SELECT v.* FROM video_items v INNER JOIN media_tag_cross_ref r ON r.mediaId = v.id WHERE r.tagId = :tagId ORDER BY v.dateModified DESC")
    fun mediaWithTag(tagId: Long): Flow<List<VideoItem>>
}

@Dao
interface RuleRunDao {
    @Insert suspend fun insert(run: RuleRun): Long
    @Update suspend fun update(run: RuleRun)
    @Insert suspend fun insertMatches(matches: List<RuleMatch>)
}

@Dao
interface BinDao {
    @Query("SELECT b.* FROM bin_entries b ORDER BY b.addedAt DESC")
    fun observeEntries(): Flow<List<BinEntry>>

    @Query("SELECT v.* FROM video_items v INNER JOIN bin_entries b ON b.mediaId = v.id ORDER BY b.addedAt DESC")
    fun observeMedia(): Flow<List<VideoItem>>

    @Query("SELECT * FROM bin_entries WHERE mediaId = :mediaId LIMIT 1")
    suspend fun entry(mediaId: Long): BinEntry?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun add(entry: BinEntry)

    @Query("DELETE FROM bin_entries WHERE mediaId = :mediaId")
    suspend fun remove(mediaId: Long)

    @Query("DELETE FROM bin_entries WHERE mediaId IN (:mediaIds)")
    suspend fun removeAll(mediaIds: List<Long>)
}
