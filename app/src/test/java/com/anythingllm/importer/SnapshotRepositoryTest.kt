package com.anythingllm.importer

import com.anythingllm.importer.data.collect.KnowledgeSnapshot
import com.anythingllm.importer.data.collect.SnapshotDiff
import com.anythingllm.importer.data.collect.SnapshotFolder
import com.anythingllm.importer.data.collect.SnapshotRepository
import com.anythingllm.importer.data.collect.SnapshotWorkspace
import java.io.File
import java.nio.file.Files
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class SnapshotRepositoryTest {

    private lateinit var dir: File
    private lateinit var repo: SnapshotRepository

    @Before
    fun setUp() {
        dir = Files.createTempDirectory("snapshot-test").toFile()
        repo = SnapshotRepository(File(dir, "snapshot.json"))
    }

    @After
    fun tearDown() {
        dir.deleteRecursively()
    }

    private fun snapshot(
        folders: List<String> = emptyList(),
        workspaces: List<Pair<String, String>> = emptyList(),
        time: String = "2026-09-11T10:00:00+08:00",
    ) = KnowledgeSnapshot(
        snapshotTime = time,
        folders = folders.map { SnapshotFolder(it) },
        workspaces = workspaces.map { SnapshotWorkspace(it.first, it.second) },
    )

    @Test
    fun `empty file read returns empty snapshot`() {
        val s = repo.read()
        assertFalse(s.hasData)
        assertTrue(s.folders.isEmpty())
        assertTrue(s.workspaces.isEmpty())
    }

    @Test
    fun `write and read roundtrip`() {
        val s = snapshot(listOf("docs", "pdf"), listOf("wsl" to "WSL", "general" to "通用"))
        repo.write(s)
        val read = repo.read()
        assertTrue(read.hasData)
        assertEquals(setOf("docs", "pdf"), read.folderNames)
        assertEquals(setOf("wsl", "general"), read.workspaceSlugs)
        assertEquals("2026-09-11T10:00:00+08:00", read.snapshotTime)
    }

    @Test
    fun `corrupted file falls back to empty`() {
        dir.mkdirs()
        File(dir, "snapshot.json").writeText("{broken")
        assertFalse(repo.read().hasData)
    }

    @Test
    fun `diff detects additions`() {
        val base = snapshot(folders = listOf("docs"), workspaces = listOf("wsl" to "WSL"))
        val now = snapshot(
            folders = listOf("docs", "new_folder"),
            workspaces = listOf("wsl" to "WSL", "new_ws" to "新工作区"),
        )
        val diff: SnapshotDiff = repo.diff(now, base)
        assertEquals(listOf("new_folder"), diff.addedFolders)
        assertEquals(listOf("new_ws"), diff.addedWorkspaces)
        assertTrue(diff.removedFolders.isEmpty())
        assertTrue(diff.removedWorkspaces.isEmpty())
        assertFalse(diff.isEmpty)
    }

    @Test
    fun `diff detects removals`() {
        val base = snapshot(
            folders = listOf("docs", "gone"),
            workspaces = listOf("wsl" to "WSL", "old_ws" to "旧"),
        )
        val now = snapshot(folders = listOf("docs"), workspaces = listOf("wsl" to "WSL"))
        val diff = repo.diff(now, base)
        assertEquals(listOf("gone"), diff.removedFolders)
        assertEquals(listOf("old_ws"), diff.removedWorkspaces)
        assertTrue(diff.addedFolders.isEmpty())
        assertTrue(diff.addedWorkspaces.isEmpty())
    }

    @Test
    fun `diff identical yields empty`() {
        val base = snapshot(listOf("docs"), listOf("wsl" to "WSL"))
        val now = snapshot(listOf("docs"), listOf("wsl" to "WSL"), time = "2026-09-11T12:00:00+08:00")
        assertTrue(repo.diff(now, base).isEmpty)
    }

    @Test
    fun `workspace keyed by slug not name`() {
        val base = snapshot(workspaces = listOf("wsl" to "旧名称"))
        val now = snapshot(workspaces = listOf("wsl" to "新名称"))
        assertTrue(repo.diff(now, base).isEmpty)
    }
}
