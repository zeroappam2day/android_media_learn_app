package com.example

import android.view.MotionEvent
import android.view.InputDevice
import android.view.View
import android.graphics.Rect
import androidx.test.espresso.UiController
import androidx.test.espresso.ViewAction
import org.hamcrest.Matcher
import android.os.SystemClock
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.runner.RunWith
import androidx.test.espresso.Espresso
import androidx.test.platform.app.InstrumentationRegistry
import com.example.data.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.util.UUID

/** Production UI boundary checks. All records belong to this run; no existing records are deleted. */
@RunWith(AndroidJUnit4::class)
class RuleTagCompletionTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val db get() = VideoDatabase.getDatabase(context)
    private val prefix = "RT" + UUID.randomUUID().toString().replace("-", "").take(12)
    private val ownedNames = mutableSetOf<String>()
    private val ownedRuleIds = mutableSetOf<Long>()
    private val ownedTagIds = mutableSetOf<Long>()
    private val ownedMediaIds = mutableSetOf<Long>()

    @Before fun isolatedPreviewOnly() {
        check(context.packageName.endsWith(".qa.preview")) { "Rule/tag tests require the isolated .qa.preview application" }
        PreviewRuntimeGuard.requireApprovedPreviewRuntime()
    }

    @After fun deleteOnlyOwnedRecords() {
        if (!context.packageName.endsWith(".qa.preview")) return
        query {
            db.mediaRuleDao().observeRules().first().filter { it.id in ownedRuleIds || it.name in ownedNames }
                .forEach { db.mediaRuleDao().deleteRule(it.id) }
            ownedMediaIds.forEach { id -> db.videoItemDao().getVideoByIdDirect(id)?.let { db.videoItemDao().deleteVideo(it) } }
            db.customTagDao().observeTags().first().filter { it.id in ownedTagIds || it.name in ownedNames }
                .forEach { db.customTagDao().delete(it) }
        }
    }

    @Test fun t119_emptyRulesAndBack() {
        assertTrue("T119 requires no saved rules in the isolated QA package", ruleRows().isEmpty())
        rules(); compose.onNodeWithText("No rules").assertIsDisplayed(); back()
        compose.onNodeWithTag("open_rules").assertIsDisplayed()
    }
    @Test fun t120_copyCreateSurvivesReturn() {
        rules(); val name = name("copy"); createRule(name)
        back(); compose.onNodeWithTag("open_rules").performClick()
        compose.onNodeWithText(name).assertIsDisplayed()
        assertEquals(1, ruleRows().count { it.name == name })
    }
    @Test fun t121_cancelCreateAndEditDoesNotPersistDraft() {
        rules(); val original = name("original"); createRule(original)
        val rule = ruleRows().single { it.name == original }; ownedRuleIds += rule.id
        ruleAction(rule.id, "Edit"); field("Name").performTextReplacement(name("discard")); icon("Cancel").performClick()
        assertEquals(original, ruleRows().single { it.id == rule.id }.name)
        compose.onNodeWithTag("add_copy_rule").performClick(); field("Name").performTextReplacement(name("cancel")); icon("Cancel").performClick()
        assertEquals(listOf(original), ruleRows().filter { it.name in ownedNames }.map { it.name })
    }
    @Test fun t122_copyEmptyNameRejected() = rejectedRuleName("")
    @Test fun t123_copySpacesNameRejected() = rejectedRuleName("   ")
    @Test fun t124_copyFortyCharacterNameSaved() { rules(); val value = fixedName(40); createRule(value); assertEquals(value, ruleRows().single { it.name == value }.name) }
    @Test fun t125_copyFortyOneCharacterNameRejected() = rejectedRuleName(fixedName(41))
    @Test fun t126_scoreZeroSaved() = validScore("0", 0.0)
    @Test fun t127_scoreOneSaved() = validScore("1", 1.0)
    @Test fun t128_scoreHalfSaved() = validScore("0.5", 0.5)
    @Test fun t129_emptyScoreRejected() = invalidScore("")
    @Test fun t130_periodScoreRejected() = invalidScore(".")
    @Test fun t131_aboveOneScoreRejected() = invalidScore("1.1")
    @Test fun t132_twoPeriodsScoreRejected() = invalidScore("0..5")
    @Test fun t133_editScorePersistsNewRevision() {
        rules(); val value = name("score"); createRule(value, "0.5")
        val rule = ruleRows().single { it.name == value }; ownedRuleIds += rule.id
        ruleAction(rule.id, "Edit"); field("Score").assertTextContains("0.5"); field("Score").performTextReplacement("1")
        icon("Save").performClick(); await { ruleRows().single { it.id == rule.id }.currentRevision == 2 }
        assertEquals(1.0, score(rule.id), 0.0)
        ruleAction(rule.id, "Edit"); field("Score").assertTextContains("1.0"); icon("Cancel").performClick()
    }
    @Test fun t134_disabledRuleStillAllowsPreview() {
        rules(); val value = name("disabled"); createRule(value)
        val rule = ruleRows().single { it.name == value }; ownedRuleIds += rule.id
        compose.onNode(isToggleable() and hasAnyAncestor(hasTestTag("rule_${rule.id}"))).performClick()
        await { !ruleRows().single { it.id == rule.id }.enabled }
        icon("Run", hasAnyAncestor(hasTestTag("rule_${rule.id}"))).assertIsNotEnabled()
        ruleAction(rule.id, "Preview")
        compose.onNodeWithText("0 •", substring = true).assertIsDisplayed()
    }
    @Test fun t141_deleteOwnedRuleRemovesOnlyRule() {
        rules(); val value = name("delete"); createRule(value)
        val row = ruleRows().single { it.name == value }; ownedRuleIds += row.id
        ruleAction(row.id, "Delete"); await { ruleRows().none { it.id == row.id } }
        compose.onNodeWithText(value).assertDoesNotExist()
    }
    @Test fun t142_tagCreateSurvivesReturn() {
        tags(); val value = name("tag"); createTag(value)
        back(); compose.onNodeWithTag("open_tags").performClick(); compose.onNodeWithText(value).assertIsDisplayed()
        assertEquals(1, tagRows().count { it.name == value })
    }
    @Test fun t143_emptyTagRejected() = rejectedTagName("")
    @Test fun t144_spacesTagRejected() = rejectedTagName("   ")
    @Test fun t145_fortyCharacterTagSaved() { tags(); val value = fixedName(40); createTag(value); assertEquals(value, tagRows().single { it.name == value }.name) }
    @Test fun t146_fortyOneCharacterTagRejected() = rejectedTagName(fixedName(41))
    @Test fun t147_duplicateTagRejectedWithFeedback() {
        tags(); val value = name("duplicate"); createTag(value)
        field("Tag").performTextReplacement(value); rejectWithFeedback(icon("Add"), "already", "exists", "duplicate tag", "duplicate name")
        assertEquals(1, tagRows().count { it.name == value })
    }
    @Test fun t150_tagRenameAndCancel() {
        tags(); val original = name("before"); createTag(original); val id = tagRows().single { it.name == original }.id; ownedTagIds += id
        editTag(original); val replacement = name("after"); dialogField().performTextReplacement(replacement); icon("Save").performClick()
        await { tagRows().single { it.id == id }.name == replacement }; compose.onNodeWithText(replacement).assertIsDisplayed()
        editTag(replacement); dialogField().performTextReplacement(name("discard")); icon("Cancel").performClick()
        assertEquals(replacement, tagRows().single { it.id == id }.name)
    }
    @Test fun t151_duplicateRenameKeepsSeparateTags() {
        tags(); val first = name("first"); val second = name("second"); createTag(first); createTag(second)
        val ids = tagRows().filter { it.name == first || it.name == second }.map { it.id }.toSet(); ownedTagIds += ids
        editTag(first); dialogField().performTextReplacement(second); rejectWithFeedback(icon("Save"), "already", "exists", "duplicate tag", "duplicate name")
        assertEquals(setOf(first, second), tagRows().filter { it.id in ids }.map { it.name }.toSet())
    }
    @Test fun t152_deleteOwnedTagPreservesOtherTag() {
        tags(); val first = name("delete"); val other = name("keep"); createTag(first); createTag(other)
        val row = tagRows().single { it.name == first }; ownedTagIds += row.id
        tagAction(first, "Delete"); await { tagRows().none { it.id == row.id } }
        compose.onNodeWithText(first).assertDoesNotExist(); compose.onNodeWithText(other).assertIsDisplayed()
    }
    @Test fun t199_duplicateRuleNamesRemainIndependentlyEditable() {
        rules(); val value = name("same"); createRule(value); createRule(value)
        await { ruleRows().count { it.name == value } == 2 }
        val rows = ruleRows().filter { it.name == value }; assertEquals(2, rows.size); ownedRuleIds += rows.map { it.id }
        val renamed = name("distinct"); ruleAction(rows.first().id, "Edit"); field("Name").performTextReplacement(renamed); icon("Save").performClick()
        await { ruleRows().any { it.id == rows.first().id && it.name == renamed } }
        assertEquals(value, ruleRows().single { it.id == rows.last().id }.name)
        compose.onNodeWithText(renamed).assertIsDisplayed(); compose.onNodeWithText(value).assertIsDisplayed()
    }
    @Test fun t200_scoreFilteringIsVisibleAndCancelKeepsDatabaseUnchanged() {
        rules(); val before = ruleRows(); compose.onNodeWithTag("add_obscured_rule").performClick()
        field("Name").performTextReplacement(name("scoreFilter"))
        field("Score").performTextReplacement("-0.5"); field("Score").assertTextContains("0.5"); icon("Save").assertIsEnabled()
        field("Score").performTextReplacement("abc"); assertEquals("", field("Score").fetchSemanticsNode().config[SemanticsProperties.EditableText].text); icon("Save").assertIsNotEnabled()
        icon("Cancel").performClick(); assertEquals(before, ruleRows())
    }
    @Test fun t202_cancelBlankTagRenameKeepsOriginal() {
        tags(); val value = name("original"); createTag(value); editTag(value); dialogField().performTextReplacement("")
        icon("Save").assertIsNotEnabled(); icon("Cancel").performClick()
        compose.onNodeWithText(value).assertIsDisplayed(); assertNotNull(tagRows().singleOrNull { it.name == value })
    }
    @Test fun t203_arabicEmojiTagTrimsOuterSpacesOnly() {
        tags(); val value = name("عربي🙂"); ownedNames += value
        createTag("  $value  ", value); assertEquals(value, tagRows().single { it.name == value }.name)
    }
    @Test fun t217_overlongRenamePreservesOriginalWithFeedback() {
        tags(); val value = name("original"); createTag(value); val id = tagRows().single { it.name == value }.id; ownedTagIds += id
        editTag(value); dialogField().performTextReplacement(fixedName(41)); rejectWithFeedback(icon("Save"), "40", "long", "length")
        assertEquals(value, tagRows().single { it.id == id }.name)
    }
    @Test fun t228_outsideTapDiscardsOwnedRuleDraft() {
        rules(); val value = name("outside"); compose.onNodeWithTag("add_copy_rule").performClick(); field("Name").performTextReplacement(value)
        dismissQaKeyboard(compose.activity)
        val dialogBounds = Rect()
        Espresso.onView(androidx.test.espresso.matcher.ViewMatchers.isRoot())
            .inRoot(androidx.test.espresso.matcher.RootMatchers.isDialog())
            .perform(object : ViewAction {
                override fun getConstraints(): Matcher<View> = androidx.test.espresso.matcher.ViewMatchers.isRoot()
                override fun getDescription() = "Read the actual rule dialog bounds in screen coordinates"
                override fun perform(uiController: UiController, view: View) {
                    val location = IntArray(2); view.getLocationOnScreen(location)
                    dialogBounds.set(location[0], location[1], location[0] + view.width, location[1] + view.height)
                }
            })
        val appBounds = Rect()
        compose.activityRule.scenario.onActivity { it.window.decorView.getWindowVisibleDisplayFrame(appBounds) }
        val margin = (16 * context.resources.displayMetrics.density).toInt().coerceAtLeast(1)
        val candidates = listOf(
            Rect(appBounds.left, appBounds.top, appBounds.right, minOf(appBounds.bottom, dialogBounds.top)),
            Rect(appBounds.left, maxOf(appBounds.top, dialogBounds.bottom), appBounds.right, appBounds.bottom),
            Rect(appBounds.left, appBounds.top, minOf(appBounds.right, dialogBounds.left), appBounds.bottom),
            Rect(maxOf(appBounds.left, dialogBounds.right), appBounds.top, appBounds.right, appBounds.bottom)
        ).filter { it.width() >= 2 * margin && it.height() >= 2 * margin }
        val outside = candidates.maxByOrNull { it.width().toLong() * it.height() }
            ?: error("No observed dialog scrim inside the app content; T228 outside tap remains unproven")
        val x = outside.centerX().toFloat(); val y = outside.centerY().toFloat()
        check(appBounds.contains(x.toInt(), y.toInt()) && !dialogBounds.contains(x.toInt(), y.toInt()))
        val now = SystemClock.uptimeMillis(); val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP).forEach { action ->
            val event = MotionEvent.obtain(now, SystemClock.uptimeMillis(), action, x, y, 0).apply { source = InputDevice.SOURCE_TOUCHSCREEN }
            try { assertTrue(automation.injectInputEvent(event, true)) } finally { event.recycle() }
        }
        compose.waitForIdle(); compose.onNodeWithText("Add rule").assertDoesNotExist()
        assertTrue(ruleRows().none { it.name == value })
        compose.onNodeWithTag("add_copy_rule").performClick(); field("Name").assertTextContains("Copies"); icon("Cancel").performClick()
    }

    @Test fun t148_t201_assignFilterRemoveAndKeepFinalAssignment() {
        val mediaName = name("item.png")
        val mediaId = query { db.videoItemDao().insertVideo(VideoItem(mediaStoreId = -System.nanoTime(), uriString = "content://qa/$mediaName", displayName = mediaName,
            relativePath = "QA/", mimeType = "image/png", sizeBytes = 10, durationMs = null, dateAdded = null, dateModified = null)) }
        ownedMediaIds += mediaId
        tags(); val value = name("label"); createTag(value); val tagId = tagRows().single { it.name == value }.id
        compose.onNodeWithText(value).performClick()
        mediaTagAction(mediaName, "Add tag"); await { assignment(mediaId, tagId) }
        icon("Tagged").performScrollTo().performClick(); compose.onNodeWithText(mediaName).performScrollTo().assertIsDisplayed()
        mediaTagAction(mediaName, "Remove tag"); await { !assignment(mediaId, tagId) }
        compose.onNodeWithText(mediaName).assertDoesNotExist()
        icon("Show all").performScrollTo().performClick()
        mediaTagAction(mediaName, "Add tag"); await { assignment(mediaId, tagId) }
        mediaTagAction(mediaName, "Remove tag"); await { !assignment(mediaId, tagId) }
        assertEquals(0, query { db.customTagDao().observeAssignments().first().count { it.mediaId == mediaId && it.tagId == tagId } })
    }
    @Test fun t149_hideAndReopenTagMediaPreservesAssignment() {
        val mediaName = name("item.png")
        val mediaId = query { db.videoItemDao().insertVideo(VideoItem(mediaStoreId = -System.nanoTime(), uriString = "content://qa/$mediaName", displayName = mediaName,
            relativePath = "QA/", mimeType = "image/png", sizeBytes = 10, durationMs = null, dateAdded = null, dateModified = null)) }
        ownedMediaIds += mediaId
        tags(); val value = name("label"); createTag(value); val tagId = tagRows().single { it.name == value }.id
        compose.onNodeWithText(value).performClick(); mediaTagAction(mediaName, "Add tag"); await { assignment(mediaId, tagId) }
        compose.onNodeWithText(value).performScrollTo().performClick(); compose.onNodeWithText(mediaName).assertDoesNotExist()
        compose.onNodeWithText(value).performClick(); compose.onNodeWithText(mediaName).performScrollTo().assertIsDisplayed()
        assertTrue(assignment(mediaId, tagId))
    }
    private fun assignment(mediaId: Long, tagId: Long) = query { db.customTagDao().observeAssignments().first().any { it.mediaId == mediaId && it.tagId == tagId } }
    private fun mediaTagAction(mediaName: String, action: String) {
        compose.onNode(hasScrollAction() and !hasSetTextAction() and hasAnyAncestor(hasTestTag("tags_screen"))).performScrollToNode(hasText(mediaName))
        val bounds = compose.onNodeWithText(mediaName).performScrollTo().fetchSemanticsNode().boundsInRoot
        icon(action, SemanticsMatcher("Tag action aligned with owned media") { it.boundsInRoot.center.y in bounds.top..bounds.bottom }).performClick()
    }
    private fun name(suffix: String) = "${prefix}_$suffix".also { ownedNames += it }
    private fun fixedName(length: Int) = (prefix + "x".repeat(length)).take(length).also { ownedNames += it }
    private fun rules() { sources(); compose.onNodeWithTag("open_rules").performClick(); compose.onNodeWithTag("rules_screen").assertIsDisplayed() }
    private fun tags() { sources(); compose.onNodeWithTag("open_tags").performClick(); compose.onNodeWithTag("tags_screen").assertIsDisplayed() }
    private fun sources() {
        if (compose.onAllNodesWithContentDescription("Open menu").fetchSemanticsNodes().isNotEmpty()) compose.onNodeWithContentDescription("Open menu").performClick()
        compose.onNodeWithTag("nav_sources").performClick()
        if (compose.onAllNodesWithContentDescription("Close menu").fetchSemanticsNodes().isNotEmpty()) compose.onNodeWithContentDescription("Close menu").performClick()
    }
    private fun back() { compose.onNodeWithContentDescription("Back").performClick() }
    private fun field(label: String) = compose.onNode(hasSetTextAction() and hasText(label))
    private fun dialogField() = compose.onAllNodes(hasSetTextAction() and hasText("Tag")).onLast()
    private fun icon(label: String, within: SemanticsMatcher? = null): SemanticsNodeInteraction {
        // The merged IconButton owns OnClick/Disabled and inherits its icon's description.
        // Selecting the unmerged Image loses Disabled and makes boundary assertions invalid.
        val button = hasClickAction() and hasContentDescription(label)
        return compose.onNode(if (within == null) button else button and within)
    }
    private fun ruleAction(id: Long, action: String) = icon(action, hasAnyAncestor(hasTestTag("rule_$id"))).performScrollTo().performClick()
    private fun tagAction(value: String, action: String) {
        val bounds = compose.onNodeWithText(value).performScrollTo().fetchSemanticsNode().boundsInRoot
        icon(action, SemanticsMatcher("Action aligned with owned tag") { it.boundsInRoot.center.y in bounds.top..bounds.bottom }).performClick()
    }
    private fun editTag(value: String) { tagAction(value, "Edit") }
    private fun createRule(value: String, score: String? = null) {
        ownedNames += value; compose.onNodeWithTag(if (score == null) "add_copy_rule" else "add_obscured_rule").performClick()
        field("Name").performTextReplacement(value); score?.let { field("Score").performTextReplacement(it) }
        icon("Save").performClick(); await { ruleRows().any { it.name == value } }
        ownedRuleIds += ruleRows().filter { it.name == value }.map { it.id }
        compose.onAllNodesWithText(value).onFirst().assertIsDisplayed()
    }
    private fun createTag(input: String, expected: String = input) {
        ownedNames += input; ownedNames += expected; field("Tag").performTextReplacement(input); icon("Add").performClick()
        dismissQaKeyboard(compose.activity); await { tagRows().any { it.name == expected } }
        ownedTagIds += tagRows().filter { it.name == expected }.map { it.id }; compose.onNodeWithText(expected).assertIsDisplayed()
    }
    private fun rejectedRuleName(value: String) {
        rules(); val before = ruleRows(); compose.onNodeWithTag("add_copy_rule").performClick(); field("Name").performTextReplacement(value)
        rejectWithFeedback(icon("Save"), "40", "name", "length"); assertEquals(before, ruleRows())
    }
    private fun rejectedTagName(value: String) {
        tags(); val before = tagRows(); field("Tag").performTextReplacement(value)
        rejectWithFeedback(icon("Add"), "40", "name", "length"); assertEquals(before, tagRows())
    }
    private fun validScore(input: String, expected: Double) {
        rules(); val value = name("score"); createRule(value, input); val row = ruleRows().single { it.name == value }
        assertEquals(expected, score(row.id), 0.0); ruleAction(row.id, "Edit"); field("Score").assertTextContains(expected.toString()); icon("Cancel").performClick()
    }
    private fun invalidScore(input: String) {
        rules(); val value = name("invalid"); val before = ruleRows()
        compose.onNodeWithTag("add_obscured_rule").performClick(); field("Name").performTextReplacement(value); field("Score").performTextReplacement(input)
        rejectWithFeedback(icon("Save"), "invalid", "0–1", "score"); assertEquals(before, ruleRows())
    }
    private fun rejectWithFeedback(action: SemanticsNodeInteraction, vararg feedback: String) {
        compose.waitForIdle()
        if (action.fetchSemanticsNode().config.contains(SemanticsProperties.Disabled)) { action.assertIsNotEnabled(); return }
        action.performClick(); compose.waitForIdle()
        val matcher = feedback.map { hasText(it, substring = true, ignoreCase = true) }.reduce { a, b -> a or b }
        compose.waitUntil(3_000) { compose.onAllNodes(matcher).fetchSemanticsNodes().isNotEmpty() }
        compose.onAllNodes(matcher).onFirst().assertIsDisplayed()
    }
    private fun score(id: Long): Double = query {
        val row = db.mediaRuleDao().observeRules().first().single { it.id == id }
        val revision = requireNotNull(db.mediaRuleDao().revision(id, row.currentRevision))
        (MediaRuleSchema.parse(revision.definitionJson).getOrThrow().predicate as MediaPredicate.Obscured).threshold
    }
    private fun ruleRows() = query { db.mediaRuleDao().observeRules().first() }
    private fun tagRows() = query { db.customTagDao().observeTags().first() }
    private fun await(block: () -> Boolean) = compose.waitUntil(10_000, block)
    private fun <T> query(block: suspend () -> T): T = runBlocking(Dispatchers.IO) { block() }
}
