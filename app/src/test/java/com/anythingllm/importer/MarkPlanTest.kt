package com.anythingllm.importer

import com.anythingllm.importer.data.collect.CollectEntry
import com.anythingllm.importer.data.collect.EntrySource
import com.anythingllm.importer.data.collect.EntryStatus
import com.anythingllm.importer.data.collect.EntryType
import com.anythingllm.importer.data.collect.KnowledgeSnapshot
import com.anythingllm.importer.data.collect.SnapshotFolder
import com.anythingllm.importer.data.collect.SnapshotWorkspace
import com.anythingllm.importer.domain.mark.MarkPlan
import com.anythingllm.importer.domain.mark.MarkValidation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MarkPlanTest {

    private val snapshot = KnowledgeSnapshot(
        snapshotTime = "2026-09-11T10:00:00+08:00",
        folders = listOf(SnapshotFolder("docs"), SnapshotFolder("pdf")),
        workspaces = listOf(SnapshotWorkspace("wsl", "WSL"), SnapshotWorkspace("general", "通用")),
    )

    private fun entry(
        folder: String? = null,
        workspace: String? = null,
        status: EntryStatus = EntryStatus.MARKED,
    ) = CollectEntry(
        id = "id1",
        type = EntryType.FILE,
        source = EntrySource.SAF,
        fileName = "a.txt",
        collectedAt = "2026-09-11T10:00:00+08:00",
        status = status,
        markFolder = folder,
        markWorkspace = workspace,
    )

    @Test
    fun `valid folder and workspace is ok`() {
        assertEquals(MarkValidation.Ok, MarkPlan.validate(entry("docs", "wsl"), snapshot))
    }

    @Test
    fun `missing folder is soft failure`() {
        val v = MarkPlan.validate(entry("gone", "wsl"), snapshot)
        assertTrue(v is MarkValidation.FolderMissing)
        assertEquals("gone", (v as MarkValidation.FolderMissing).folder)
    }

    @Test
    fun `missing workspace is hard failure`() {
        val v = MarkPlan.validate(entry("docs", "deleted_ws"), snapshot)
        assertTrue(v is MarkValidation.WorkspaceMissing)
        assertEquals("deleted_ws", (v as MarkValidation.WorkspaceMissing).slug)
    }

    @Test
    fun `unmarked entry is ok`() {
        assertEquals(MarkValidation.Ok, MarkPlan.validate(entry(null, null, EntryStatus.PENDING), snapshot))
    }

    @Test
    fun `sync mode folder only is ok`() {
        assertEquals(MarkValidation.Ok, MarkPlan.validate(entry("docs", null), snapshot))
        assertTrue(MarkPlan.isSyncOnly(entry("docs", null)))
        assertFalse(MarkPlan.isSyncOnly(entry("docs", "wsl")))
    }

    @Test
    fun `isMarked reflects status`() {
        assertTrue(MarkPlan.isMarked(entry(status = EntryStatus.MARKED)))
        assertTrue(MarkPlan.isMarked(entry(status = EntryStatus.EXECUTING)))
        assertFalse(MarkPlan.isMarked(entry(status = EntryStatus.PENDING)))
        assertFalse(MarkPlan.isMarked(entry(status = EntryStatus.EXECUTED)))
    }
}
