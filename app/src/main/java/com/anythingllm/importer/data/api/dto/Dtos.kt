package com.anythingllm.importer.data.api.dto

import kotlinx.serialization.Serializable

// ===== 探活与认证 =====

@Serializable
data class PingResponse(val online: Boolean)

@Serializable
data class AuthResponse(val authenticated: Boolean)

// ===== 工作区 =====

@Serializable
data class WorkspacesResponse(val workspaces: List<WorkspaceDto> = emptyList())

@Serializable
data class WorkspaceDto(
    val id: Long = 0,
    val name: String = "",
    val slug: String = "",
    val vectorTag: String? = null,
    val createdAt: String? = null,
    val lastUpdatedAt: String? = null,
    val similarityThreshold: Double? = null,
    val topN: Int? = null,
    val chatMode: String? = null,
    val threads: List<ChatThreadDto> = emptyList(),
)

@Serializable
data class ChatThreadDto(
    val id: Long = 0,
    val name: String? = null,
    val slug: String? = null,
)

@Serializable
data class CreateWorkspaceRequest(val name: String)

// ===== 工作区详情(验证轮询用) =====
// 实测:GET /api/v1/workspace/{slug} 返回 {"workspace":[{...}]} —— 数组结构

@Serializable
data class WorkspaceDetailResponse(val workspace: List<WorkspaceDetailDto> = emptyList())

@Serializable
data class WorkspaceDetailDto(
    val id: Long = 0,
    val name: String = "",
    val slug: String = "",
    val documents: List<WorkspaceDocumentDto> = emptyList(),
)

@Serializable
data class WorkspaceDocumentDto(
    val id: Long = 0,
    val docId: String? = null,
    val filename: String? = null,
    val docpath: String? = null,
    /** 实测(03-API实测修订):真实服务器返回 JSON 字符串(如 "{\"id\":\"...\",\"title\":\"...\"}") */
    val metadata: String? = null,
    val pinned: Boolean = false,
    val watched: Boolean = false,
)

// ===== 文档树 / 文件夹 =====

@Serializable
data class DocumentsResponse(val localFiles: FileNodeDto? = null)

@Serializable
data class FileNodeDto(
    val name: String = "",
    val type: String = "file",
    val items: List<FileNodeDto>? = null,
    val id: String? = null,
    val url: String? = null,
    val title: String? = null,
    val cached: Boolean? = null,
    val pinnedWorkspaces: List<String>? = null,
)

@Serializable
data class CreateFolderRequest(val name: String)

@Serializable
data class RemoveFolderRequest(val name: String)

@Serializable
data class SimpleSuccessResponse(val success: Boolean = true, val message: String? = null)

// ===== 上传 =====
// 实测:POST upload 返回 {"success":true,"error":null,"documents":[{...,"location":"{folder}/{storageName}-{uuid}.json"}]}
// location 即工作区详情 documents[].docpath 的匹配基准(验证轮询)

@Serializable
data class UploadResponse(
    val success: Boolean = true,
    val error: String? = null,
    val documents: List<UploadedDocumentDto> = emptyList(),
)

@Serializable
data class UploadedDocumentDto(
    val id: String? = null,
    val url: String? = null,
    val title: String? = null,
    val location: String? = null,
    val docAuthor: String? = null,
    val description: String? = null,
    val docSource: String? = null,
    val published: String? = null,
    val wordCount: Long? = null,
    val token_count_estimate: Long? = null,
    val isDirectUpload: Boolean? = null,
)

// ===== 嵌入触发 =====

@Serializable
data class UpdateEmbeddingsRequest(
    val adds: List<String> = emptyList(),
    val deletes: List<String> = emptyList(),
)

@Serializable
data class UpdateEmbeddingsResponse(
    val success: Boolean = true,
    val message: String? = null,
    val error: String? = null,
)

// ===== 物理删除(替换场景) =====
// 实测:names 必须带文件夹前缀,如 "custom-documents/xxx.json",否则静默假成功

@Serializable
data class RemoveDocumentsRequest(val names: List<String> = emptyList())

// ===== 测试检索(P1,chat query) =====
@Serializable
data class ChatRequest(
    val message: String,
    val mode: String = "query",
    val stream: Boolean = false,
)

@Serializable
data class ChatResponse(
    val id: String? = null,
    val type: String? = null,
    val textResponse: String? = null,
    val sources: List<ChatSourceDto> = emptyList(),
    val error: String? = null,
)

@Serializable
data class ChatSourceDto(
    val text: String? = null,
    val title: String? = null,
    val docpath: String? = null,
)

// ===== 链接抓取(v1.2 FR-19) =====
// 实测(阶段0):POST /api/v1/document/upload-link
//   body {"link": "url" | ["url1","url2"], "addToWorkspaces": "ws1,ws2"}
//   → {"success":true, "documents":[{title,location,wordCount,...}]}
//   title 形如 "{域名}_.html";location 形如 "custom-documents/url-{title}-{uuid}.json"
//   无效 URL → success:false + error 字段

@Serializable
data class UploadLinkRequest(
    val link: List<String> = emptyList(),
    val addToWorkspaces: String? = null,
)
