
/*
 * Copyright 2024-2025 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.alibaba.cloud.ai.dataagent.controller;

import com.alibaba.cloud.ai.dataagent.enums.ModelType;
import com.alibaba.cloud.ai.dataagent.dto.ModelConfigDTO;
import com.alibaba.cloud.ai.dataagent.service.aimodelconfig.ModelConfigDataService;
import com.alibaba.cloud.ai.dataagent.service.aimodelconfig.ModelConfigOpsService;
import com.alibaba.cloud.ai.dataagent.vo.ApiResponse;
import com.alibaba.cloud.ai.dataagent.vo.ModelCheckVo;
import jakarta.validation.Valid;
import lombok.AllArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * AI 模型配置管理 Controller
 * 
 * <p><b>职责边界</b>：
 * 管理系统使用的 LLM（大语言模型）和 Embedding（向量化模型）配置，支持多模型动态切换。
 * 用户可以通过前端界面配置不同的模型提供商（如 OpenAI、Qwen、通义千问等），并在运行时切换。
 * 
 * <p><b>核心概念</b>：
 * - <b>模型类型（ModelType）</b>：
 *   - CHAT：聊天模型（用于生成 SQL、计划、报告等）
 *   - EMBEDDING：嵌入模型（用于向量化 Schema、知识库等）
 * - <b>模型配置（ModelConfigDTO）</b>：包含模型提供商、API Key、Base URL、模型名称等
 * - <b>激活状态（isActive）</b>：同一类型只能有一个配置处于激活状态
 * 
 * <p><b>典型使用场景</b>：
 * 1. 系统初始化时，用户配置第一个 Chat 模型和 Embedding 模型
 * 2. 用户希望切换到更强大的模型 → 添加新配置并激活
 * 3. 测试新模型是否可用 → 调用 /test 接口验证连通性
 * 4. 前端检查模型是否就绪 → 调用 /check-ready 接口
 * 
 * <p><b>与其他模块的关系</b>：
 * - 被 {@code AiModelRegistry} 读取并初始化 {@code ChatModel} 和 {@code EmbeddingModel} Bean
 * - 影响所有需要调用 LLM 的节点（如 {@code SqlGenerateNode}、{@code PlannerNode} 等）
 * - 影响所有需要向量化的模块（如 {@code AgentKnowledgeService}、{@code SchemaRecallNode} 等）
 * 
 * <p><b>主要端点</b>：
 * - POST /add：添加新模型配置
 * - PUT /update：更新现有配置
 * - POST /activate/{id}：激活指定配置（会自动停用同类型的其他配置）
 * - POST /test：测试模型连通性（不保存配置）
 * - GET /check-ready：检查 Chat 和 Embedding 模型是否都已配置
 * 
 * @author Makoto
 */
@AllArgsConstructor
@RestController
@RequestMapping("/api/model-config")
public class ModelConfigController {

	private final ModelConfigDataService modelConfigDataService;

	private final ModelConfigOpsService modelConfigOpsService;

	// 1. 获取列表
	@GetMapping("/list")
	public ApiResponse<List<ModelConfigDTO>> list() {
		try {
			return ApiResponse.success("获取模型配置列表成功", modelConfigDataService.listConfigs());
		}
		catch (Exception e) {
			return ApiResponse.error("获取模型配置列表失败: " + e.getMessage());
		}
	}

	// 2. 新增配置
	@PostMapping("/add")
	public ApiResponse<String> add(@Valid @RequestBody ModelConfigDTO config) {
		try {
			modelConfigDataService.addConfig(config);
			return ApiResponse.success("配置已保存");
		}
		catch (Exception e) {
			return ApiResponse.error("保存失败: " + e.getMessage());
		}
	}

	// 3. 修改配置
	@PutMapping("/update")
	public ApiResponse<String> update(@Valid @RequestBody ModelConfigDTO config) {
		try {
			modelConfigOpsService.updateAndRefresh(config);
			return ApiResponse.success("配置已更新");
		}
		catch (Exception e) {
			return ApiResponse.error("更新失败: " + e.getMessage());
		}
	}

	// 4. 删除配置
	@DeleteMapping("/{id}")
	public ApiResponse<String> delete(@PathVariable Integer id) {
		try {
			modelConfigDataService.deleteConfig(id);
			return ApiResponse.success("配置已删除");
		}
		catch (Exception e) {
			return ApiResponse.error("删除失败: " + e.getMessage());
		}
	}

	/**
	 * 激活指定模型配置（切换模型）
	 * 
	 * <p><b>用途</b>：用户在前端选择某个模型配置并点击"激活"时调用。
	 * 
	 * <p><b>关键逻辑</b>：
	 * - 将指定配置的 {@code isActive} 设为 true
	 * - 同时将同类型（CHAT 或 EMBEDDING）的其他配置设为 false
	 * - 调用 {@code AiModelRegistry} 重新初始化对应的模型 Bean
	 * 
	 * <p><b>副作用</b>：
	 * - 激活后，所有后续的 LLM 调用都会使用新模型
	 * - 如果新模型配置有误（如 API Key 错误），会导致后续调用失败
	 * - 建议激活前先调用 /test 接口验证
	 * 
	 * <p><b>调用链</b>：
	 * 本接口 → {@code ModelConfigOpsService.activateConfig()} 
	 * → {@code AiModelRegistry.refreshChatModel()} 或 {@code refreshEmbeddingModel()}
	 * → 重新创建 Spring Bean
	 * 
	 * @param id 模型配置ID
	 * @return 操作结果
	 */
	@PostMapping("/activate/{id}")
	public ApiResponse<String> activate(@PathVariable Integer id) {
		try {
			modelConfigOpsService.activateConfig(id);
			return ApiResponse.success("模型切换成功！");
		}
		catch (Exception e) {
			return ApiResponse.error("切换失败，请检查配置是否正确: " + e.getMessage());
		}
	}

	/**
	 * 测试模型连通性（不保存配置）
	 * 
	 * <p><b>用途</b>：用户在前端填写模型配置后，点击"测试连接"按钮时调用。
	 * 
	 * <p><b>关键逻辑</b>：
	 * - 使用前端传入的临时配置（不保存到数据库）
	 * - 根据模型类型发起一次真实调用：
	 *   - CHAT 模型：发送简单的测试消息（如 "Hello"）
	 *   - EMBEDDING 模型：对测试文本进行向量化
	 * - 如果调用成功，返回成功；如果失败，返回具体错误（如 401 Invalid Key、404 Not Found）
	 * 
	 * <p><b>典型错误</b>：
	 * - "401 Unauthorized"：API Key 错误
	 * - "404 Not Found"：Base URL 或模型名称错误
	 * - "Connection timeout"：网络不通或代理配置错误
	 * 
	 * <p><b>推荐流程</b>：
	 * 用户填写配置 → 点击"测试连接" → 测试通过 → 点击"保存" → 点击"激活"
	 * 
	 * @param config 临时模型配置（不会保存到数据库）
	 * @return 测试结果，成功或具体错误信息
	 */
	@PostMapping("/test")
	public ApiResponse<String> testConnection(@Valid @RequestBody ModelConfigDTO config) {
		try {
			modelConfigOpsService.testConnection(config);
			return ApiResponse.success("连接测试成功！模型可用。");
		}
		catch (Exception e) {
			// 捕获具体的错误信息（如 401 Invalid Key, 404 Not Found 等）返回给前端
			return ApiResponse.error("连接测试失败: " + e.getMessage());
		}
	}

	/**
	 * 检查模型配置是否就绪（前端初始化检查）
	 * 
	 * <p><b>用途</b>：前端在进入系统时调用，检查是否已完成模型配置。
	 * 
	 * <p><b>检查逻辑</b>：
	 * - 检查是否存在激活的 CHAT 模型配置
	 * - 检查是否存在激活的 EMBEDDING 模型配置
	 * - 只有两者都配置且激活，系统才能正常运行
	 * 
	 * <p><b>返回值</b>：
	 * - {@code chatModelReady}：Chat 模型是否就绪
	 * - {@code embeddingModelReady}：Embedding 模型是否就绪
	 * - {@code ready}：整体是否就绪（两者都为 true）
	 * 
	 * <p><b>典型场景</b>：
	 * - 用户首次使用系统，前端检测到 {@code ready=false}，引导用户配置模型
	 * - 用户切换模型后，前端重新检查确保配置生效
	 * 
	 * @return 模型就绪状态
	 */
	@GetMapping("/check-ready")
	public ApiResponse<ModelCheckVo> checkReady() {
		// 检查聊天模型是否已配置且启用
		ModelConfigDTO chatModel = modelConfigDataService.getActiveConfigByType(ModelType.CHAT);
		// 检查嵌入模型是否已配置且启用
		ModelConfigDTO embeddingModel = modelConfigDataService.getActiveConfigByType(ModelType.EMBEDDING);

		boolean chatModelReady = chatModel != null;
		boolean embeddingModelReady = embeddingModel != null;
		boolean ready = chatModelReady && embeddingModelReady;

		return ApiResponse.success("模型配置检查完成",
				ModelCheckVo.builder()
					.chatModelReady(chatModelReady)
					.embeddingModelReady(embeddingModelReady)
					.ready(ready)
					.build());
	}

}
