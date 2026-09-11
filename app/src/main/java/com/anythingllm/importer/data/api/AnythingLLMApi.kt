package com.anythingllm.importer.data.api

import com.anythingllm.importer.data.api.dto.AuthResponse
import com.anythingllm.importer.data.api.dto.ChatRequest
import com.anythingllm.importer.data.api.dto.ChatResponse
import com.anythingllm.importer.data.api.dto.CreateFolderRequest
import com.anythingllm.importer.data.api.dto.CreateWorkspaceRequest
import com.anythingllm.importer.data.api.dto.DocumentsResponse
import com.anythingllm.importer.data.api.dto.PingResponse
import com.anythingllm.importer.data.api.dto.RemoveDocumentsRequest
import com.anythingllm.importer.data.api.dto.RemoveFolderRequest
import com.anythingllm.importer.data.api.dto.SimpleSuccessResponse
import com.anythingllm.importer.data.api.dto.UpdateEmbeddingsRequest
import com.anythingllm.importer.data.api.dto.UpdateEmbeddingsResponse
import com.anythingllm.importer.data.api.dto.UploadResponse
import com.anythingllm.importer.data.api.dto.UploadLinkRequest
import com.anythingllm.importer.data.api.dto.WorkspaceDetailResponse
import com.anythingllm.importer.data.api.dto.WorkspaceDto
import com.anythingllm.importer.data.api.dto.WorkspacesResponse
import okhttp3.RequestBody
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.HTTP
import retrofit2.http.POST
import retrofit2.http.Path

/**
 * AnythingLLM REST API 客户端接口。
 * 与开发文档 §5.2 的 13 个端点一一对应;
 * 字段结构以 2026-09-10 实测本机实例为准。
 */
interface AnythingLLMApi {

    // 1. 探活(无需 Key)
    @GET("api/ping")
    suspend fun ping(): PingResponse

    // 2. 验证 Key 有效性
    @GET("api/v1/auth")
    suspend fun auth(): AuthResponse

    // 3. 工作区列表
    @GET("api/v1/workspaces")
    suspend fun workspaces(): WorkspacesResponse

    // 4. 创建工作区(可选)
    @POST("api/v1/workspace/new")
    suspend fun createWorkspace(@Body body: CreateWorkspaceRequest): WorkspaceDto

    // 5. 工作区详情(验证轮询用;实测返回 workspace 数组)
    @GET("api/v1/workspace/{slug}")
    suspend fun workspaceDetail(@Path("slug") slug: String): WorkspaceDetailResponse

    // 6. 文档树/查重/文件夹列表
    @GET("api/v1/documents")
    suspend fun documents(): DocumentsResponse

    // 7. 创建文档文件夹
    @POST("api/v1/document/create-folder")
    suspend fun createFolder(@Body body: CreateFolderRequest): SimpleSuccessResponse

    // 8. 删除空文件夹
    @HTTP(method = "DELETE", path = "api/v1/document/remove-folder", hasBody = true)
    suspend fun removeFolder(@Body body: RemoveFolderRequest): SimpleSuccessResponse

    // 9. 上传并进工作区(手动 MultipartBody:metadata 在前,file 在后)
    @POST("api/v1/workspace/{slug}/upload")
    suspend fun uploadToWorkspace(
        @Path("slug") slug: String,
        @Body body: RequestBody,
    ): UploadResponse

    // 10. 上传到文档文件夹(folder 需 URL 编码)
    @POST("api/v1/document/upload/{folder}")
    suspend fun uploadToFolder(
        @Path("folder") folder: String,
        @Body body: RequestBody,
    ): UploadResponse

    // 11. 触发嵌入/解关联
    @POST("api/v1/workspace/{slug}/update-embeddings")
    suspend fun updateEmbeddings(
        @Path("slug") slug: String,
        @Body body: UpdateEmbeddingsRequest,
    ): UpdateEmbeddingsResponse

    // 12. 物理删除(替换场景;names 需带文件夹前缀)
    @HTTP(method = "DELETE", path = "api/v1/system/remove-documents", hasBody = true)
    suspend fun removeDocuments(@Body body: RemoveDocumentsRequest): SimpleSuccessResponse

    // 13. 测试检索(query 模式)
    @POST("api/v1/workspace/{slug}/chat")
    suspend fun chat(
        @Path("slug") slug: String,
        @Body body: ChatRequest,
    ): ChatResponse

    // 14. 链接抓取(v1.2 FR-19):服务端 scrape 网页正文,可批量,可直入工作区
    @POST("api/v1/document/upload-link")
    suspend fun uploadLink(@Body body: UploadLinkRequest): UploadResponse
}
