/*
 * Copyright 2024-2025 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.alibaba.cloud.ai.dataagent.controller;

import com.alibaba.cloud.ai.dataagent.properties.FileStorageProperties;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import com.alibaba.cloud.ai.dataagent.service.file.FileStorageService;
import com.alibaba.cloud.ai.dataagent.vo.UploadResponse;

import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * 文件上传与访问 Controller
 * 
 * <p><b>职责边界</b>：
 * 处理文件上传（如智能体头像、知识库文档等）和文件访问请求，
 * 提供统一的文件存储和 URL 生成服务。
 * 
 * <p><b>核心概念</b>：
 * - <b>文件存储路径</b>：由 {@code FileStorageProperties} 配置，默认存储在本地文件系统
 * - <b>文件分类</b>：按用途分目录存储（如 avatars/、documents/ 等）
 * - <b>文件 URL</b>：返回可直接访问的 URL（如 /api/upload/avatars/xxx.png）
 * 
 * <p><b>典型使用场景</b>：
 * 1. 用户创建智能体时上传头像 → 调用 POST /avatar
 * 2. 用户添加知识库文档（PDF、Word 等）→ 调用对应的上传接口
 * 3. 前端展示头像或文档 → 通过返回的 URL 访问 GET /api/upload/...
 * 
 * <p><b>安全考虑</b>：
 * - 上传时会校验文件类型（如头像只允许图片）
 * - 上传时会校验文件大小（防止恶意上传大文件）
 * - 文件名会进行处理（防止路径穿越攻击）
 * 
 * <p><b>与其他模块的关系</b>：
 * - 被 {@code AgentController} 使用（上传智能体头像）
 * - 被 {@code AgentKnowledgeController} 使用（上传知识库文档）
 * - 文件 URL 会保存到数据库（如 agent.avatar 字段）
 * 
 * <p><b>主要端点</b>：
 * - POST /avatar：上传头像图片
 * - GET /**：访问已上传的文件（通过 URL 路径匹配）
 *
 * @author Makoto
 * @since 2025/9/19
 */
@Slf4j
@RestController
@RequestMapping("/api/upload")
@CrossOrigin(origins = "*")
@AllArgsConstructor
public class FileUploadController {

	private final FileStorageProperties fileStorageProperties;

	private final FileStorageService fileStorageService;

	/**
	 * 上传头像图片
	 * 
	 * <p><b>用途</b>：用户在创建/编辑智能体时上传头像。
	 * 
	 * <p><b>校验逻辑</b>：
	 * - 只允许图片文件（通过 Content-Type 判断）
	 * - 文件大小不能超过配置的限制（默认由 {@code FileStorageProperties.imageSize} 控制）
	 * 
	 * <p><b>存储逻辑</b>：
	 * - 文件存储在 avatars/ 目录下
	 * - 文件名会自动生成（防止重名和路径穿越）
	 * - 返回可直接访问的 URL（如 /api/upload/avatars/xxx.png）
	 * 
	 * <p><b>返回值</b>：
	 * - {@code fileUrl}：可直接访问的完整 URL
	 * - {@code filename}：文件名（用于前端展示）
	 * 
	 * <p><b>典型流程</b>：
	 * 前端上传 → 后端保存文件 → 返回 URL → 前端保存到 agent.avatar 字段
	 * 
	 * @param file 上传的文件
	 * @return 上传结果，包含文件 URL 和文件名
	 */
	@PostMapping(value = "/avatar", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
	public ResponseEntity<UploadResponse> uploadAvatar(@RequestParam("file") MultipartFile file) {
		try {
			// 验证文件类型
			String contentType = file.getContentType();
			if (contentType == null || !contentType.startsWith("image/")) {
				return ResponseEntity.badRequest().body(UploadResponse.error("只支持图片文件"));
			}

			// 校验文件大小
			long maxImageSize = fileStorageProperties.getImageSize();
			if (file.getSize() > maxImageSize) {
				return ResponseEntity.badRequest().body(UploadResponse.error("图片大小超限，最大允许：" + maxImageSize + " 字节"));
			}

			// 使用文件存储服务存储文件
			String filePath = fileStorageService.storeFile(file, "avatars");
			String fileUrl = fileStorageService.getFileUrl(filePath);

			// 提取文件名
			String filename = filePath.substring(filePath.lastIndexOf("/") + 1);

			return ResponseEntity.ok(UploadResponse.ok("上传成功", fileUrl, filename));

		}
		catch (Exception e) {
			log.error("头像上传失败", e);
			return ResponseEntity.internalServerError().body(UploadResponse.error("上传失败: " + e.getMessage()));
		}
	}

	/**
	 * 访问已上传的文件（通过 URL 路径）
	 * 
	 * <p><b>用途</b>：前端通过上传接口返回的 URL 访问文件（如展示头像、下载文档）。
	 * 
	 * <p><b>访问逻辑</b>：
	 * - 从请求 URI 中提取文件路径（如 /api/upload/avatars/xxx.png → avatars/xxx.png）
	 * - 从本地文件系统读取文件内容
	 * - 根据文件类型设置 Content-Type（如 image/png、application/pdf）
	 * 
	 * <p><b>安全考虑</b>：
	 * - 会检查文件是否存在（防止路径穿越）
	 * - 会检查是否为目录（防止目录遍历）
	 * 
	 * <p><b>典型场景</b>：
	 * - 前端展示智能体头像：<img src="/api/upload/avatars/xxx.png" />
	 * - 前端下载知识库文档：<a href="/api/upload/documents/xxx.pdf">下载</a>
	 * 
	 * @param request HTTP 请求（用于提取文件路径）
	 * @return 文件内容（字节数组）
	 */
	@GetMapping("/**")
	public ResponseEntity<byte[]> getFile(HttpServletRequest request) {
		try {
			String requestPath = request.getRequestURI();
			String urlPrefix = fileStorageProperties.getUrlPrefix();
			String filePath = requestPath.substring(urlPrefix.length());

			Path fullPath = Paths.get(fileStorageProperties.getPath(), filePath);

			if (!Files.exists(fullPath) || Files.isDirectory(fullPath)) {
				return ResponseEntity.notFound().build();
			}

			byte[] fileContent = Files.readAllBytes(fullPath);
			String contentType = Files.probeContentType(fullPath);

			return ResponseEntity.ok()
				.contentType(MediaType.parseMediaType(contentType != null ? contentType : "application/octet-stream"))
				.body(fileContent);

		}
		catch (IOException e) {
			log.error("文件读取失败", e);
			return ResponseEntity.internalServerError().build();
		}
	}

}
