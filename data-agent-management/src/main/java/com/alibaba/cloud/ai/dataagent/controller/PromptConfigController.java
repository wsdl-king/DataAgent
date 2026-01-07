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

import com.alibaba.cloud.ai.dataagent.dto.prompt.PromptConfigDTO;
import com.alibaba.cloud.ai.dataagent.entity.UserPromptConfig;
import com.alibaba.cloud.ai.dataagent.service.prompt.UserPromptService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 提示词优化配置管理 Controller
 * 
 * <p><b>职责边界</b>：
 * 管理用户自定义的提示词优化配置（Prompt Optimization Config），允许用户在不修改代码的情况下，
 * 通过前端界面动态调整各个节点（如 report-generator、planner、sql-generator 等）的提示词行为。
 * 
 * <p><b>核心概念</b>：
 * - <b>提示词类型（promptType）</b>：对应工作流中的不同节点，如 "report-generator"（报告生成）、
 *   "planner"（计划生成）、"sql-generator"（SQL生成）等
 * - <b>优化配置（UserPromptConfig）</b>：用户自定义的提示词增强规则，可以包含额外的指令、约束、
 *   示例等，会在运行时动态注入到对应节点的 Prompt 中
 * - <b>优先级（priority）</b>：当同一类型有多个配置时，按优先级排序应用
 * - <b>智能体级别（agentId）</b>：配置可以是全局的（agentId=null）或针对特定智能体的
 * 
 * <p><b>典型使用场景</b>：
 * 1. 用户希望报告生成时强调某些业务指标 → 创建 "report-generator" 类型的优化配置
 * 2. 用户希望 SQL 生成时避免使用某些表 → 创建 "sql-generator" 类型的约束配置
 * 3. 不同智能体需要不同的提示词风格 → 为每个 agentId 创建独立配置
 * 
 * <p><b>与其他模块的关系</b>：
 * - 被 {@code PromptHelper.buildReportGeneratorPromptWithOptimization()} 在构建报告生成 Prompt 时读取并注入
 * - 主要被 {@code ReportGeneratorNode} 使用（通过 {@code UserPromptService.getOptimizationConfigs()} 获取配置）
 * - 配置的启用/禁用会实时影响报告生成节点的提示词行为
 * - 注意：目前只有 report-generator 类型支持优化配置，其他节点类型（如 planner、sql-generator）暂未实现
 * 
 * <p><b>主要端点</b>：
 * - POST /save：创建或更新配置
 * - GET /list-by-type/{promptType}：按类型和智能体查询配置列表
 * - GET /active/{promptType}：获取当前生效的配置
 * - POST /{id}/enable、/{id}/disable：启用/禁用配置
 * - POST /batch-enable、/batch-disable：批量操作
 * 
 * @author Makoto
 */
@RestController
@RequestMapping("/api/prompt-config")
@CrossOrigin(origins = "*", maxAge = 3600)
public class PromptConfigController {

	private static final Logger logger = LoggerFactory.getLogger(PromptConfigController.class);

	private final UserPromptService promptConfigService;

	public PromptConfigController(UserPromptService promptConfigService) {
		this.promptConfigService = promptConfigService;
	}

	/**
	 * 创建或更新提示词优化配置
	 * 
	 * <p><b>用途</b>：前端用户在"提示词优化"页面创建/编辑配置时调用此接口。
	 * 
	 * <p><b>关键参数</b>：
	 * - {@code configDTO.promptType}：提示词类型（如 "report-generator"）
	 * - {@code configDTO.agentId}：智能体ID（可选，为空表示全局配置）
	 * - {@code configDTO.content}：优化内容（会被注入到对应节点的 Prompt 中）
	 * - {@code configDTO.priority}：优先级（数字越大越优先）
	 * - {@code configDTO.isActive}：是否启用
	 * 
	 * <p><b>副作用</b>：
	 * - 如果 {@code configDTO.id} 存在，则更新现有配置；否则创建新配置
	 * - 配置保存后，下次工作流运行时会立即生效（通过 {@code PromptHelper} 读取）
	 * 
	 * @param configDTO 配置数据
	 * @return 操作结果，包含保存后的配置对象
	 */
	@PostMapping("/save")
	public ResponseEntity<Map<String, Object>> saveConfig(@RequestBody PromptConfigDTO configDTO) {
		logger.info("保存提示词优化配置请求：{}", configDTO);

		UserPromptConfig savedConfig = promptConfigService.saveOrUpdateConfig(configDTO);

		Map<String, Object> response = new HashMap<>();
		response.put("success", true);
		response.put("message", "优化配置保存成功");
		response.put("data", savedConfig);

		return ResponseEntity.ok(response);
	}

	/**
	 * Get configuration by ID
	 * @param id configuration ID
	 * @return configuration information
	 */
	@GetMapping("/{id}")
	public ResponseEntity<Map<String, Object>> getConfig(@PathVariable(value = "id") String id) {
		UserPromptConfig config = promptConfigService.getConfigById(id);

		Map<String, Object> response = new HashMap<>();
		if (config != null) {
			response.put("success", true);
			response.put("data", config);
		}
		else {
			response.put("success", false);
			response.put("message", "配置不存在");
		}

		return ResponseEntity.ok(response);
	}

	/**
	 * Get all configuration list
	 * @return configuration list
	 */
	@GetMapping("/list")
	public ResponseEntity<Map<String, Object>> getAllConfigs() {
		List<UserPromptConfig> configs = promptConfigService.getAllConfigs();

		Map<String, Object> response = new HashMap<>();
		response.put("success", true);
		response.put("data", configs);
		response.put("total", configs.size());

		return ResponseEntity.ok(response);
	}

	/**
	 * 按提示词类型和智能体查询配置列表
	 * 
	 * <p><b>用途</b>：前端在"提示词优化"页面展示某个类型的所有配置时调用。
	 * 
	 * <p><b>查询逻辑</b>：
	 * - 如果提供 {@code agentId}，返回该智能体的专属配置 + 全局配置
	 * - 如果不提供 {@code agentId}，只返回全局配置
	 * 
	 * <p><b>典型场景</b>：
	 * 用户在编辑智能体时，查看"报告生成"节点可用的所有优化配置。
	 * 
	 * @param promptType 提示词类型（如 "report-generator"、"planner"）
	 * @param agentId 智能体ID（可选）
	 * @return 配置列表，按优先级排序
	 */
	@GetMapping("/list-by-type/{promptType}")
	public ResponseEntity<Map<String, Object>> getConfigsByType(@PathVariable(value = "promptType") String promptType,
			@RequestParam(value = "agentId", required = false) Long agentId) {
		List<UserPromptConfig> configs = promptConfigService.getConfigsByType(promptType, agentId);

		Map<String, Object> response = new HashMap<>();
		response.put("success", true);
		response.put("data", configs);
		response.put("total", configs.size());

		return ResponseEntity.ok(response);
	}

	/**
	 * Get currently enabled configuration by prompt type and agent
	 * @param promptType prompt type
	 * @param agentId agent id, optional
	 * @return currently enabled configuration
	 */
	@GetMapping("/active/{promptType}")
	public ResponseEntity<Map<String, Object>> getActiveConfig(@PathVariable(value = "promptType") String promptType,
			@RequestParam(value = "agentId", required = false) Long agentId) {
		UserPromptConfig config = promptConfigService.getActiveConfigByType(promptType, agentId);

		Map<String, Object> response = new HashMap<>();
		response.put("success", true);
		response.put("data", config);
		response.put("hasCustomConfig", config != null);

		return ResponseEntity.ok(response);
	}

	/**
	 * 获取某个类型和智能体的所有启用的优化配置
	 * 
	 * <p><b>用途</b>：工作流节点（如 {@code ReportGeneratorNode}）在运行时调用，
	 * 获取当前生效的所有优化配置，并注入到 Prompt 中。
	 * 
	 * <p><b>关键逻辑</b>：
	 * - 只返回 {@code isActive=true} 的配置
	 * - 按优先级排序（高优先级的配置会先被应用）
	 * - 智能体级别配置优先于全局配置
	 * 
	 * <p><b>调用链</b>：
	 * {@code ReportGeneratorNode.generateReport()} 
	 * → {@code PromptHelper.buildReportGeneratorPromptWithOptimization()} 
	 * → {@code UserPromptService.getOptimizationConfigs()} 
	 * → 本接口对应的 Service 方法
	 * 
	 * @param promptType 提示词类型
	 * @param agentId 智能体ID（可选）
	 * @return 启用的优化配置列表，按优先级排序
	 */
	@GetMapping("/active-all/{promptType}")
	public ResponseEntity<Map<String, Object>> getActiveConfigs(@PathVariable(value = "promptType") String promptType,
			@RequestParam(value = "agentId", required = false) Long agentId) {
		List<UserPromptConfig> configs = promptConfigService.getActiveConfigsByType(promptType, agentId);

		Map<String, Object> response = new HashMap<>();
		response.put("success", true);
		response.put("data", configs);
		response.put("total", configs.size());
		response.put("hasOptimizationConfigs", !configs.isEmpty());

		return ResponseEntity.ok(response);
	}

	/**
	 * Delete configuration
	 * @param id configuration ID
	 * @return operation result
	 */
	@DeleteMapping("/{id}")
	public ResponseEntity<Map<String, Object>> deleteConfig(@PathVariable(value = "id") String id) {
		boolean deleted = promptConfigService.deleteConfig(id);

		Map<String, Object> response = new HashMap<>();
		if (deleted) {
			response.put("success", true);
			response.put("message", "配置删除成功");
		}
		else {
			response.put("success", false);
			response.put("message", "配置不存在或删除失败");
		}

		return ResponseEntity.ok(response);
	}

	/**
	 * Enable specified configuration
	 * @param id configuration ID
	 * @return operation result
	 */
	@PostMapping("/{id}/enable")
	public ResponseEntity<Map<String, Object>> enableConfig(@PathVariable(value = "id") String id) {
		boolean enabled = promptConfigService.enableConfig(id);

		Map<String, Object> response = new HashMap<>();
		if (enabled) {
			response.put("success", true);
			response.put("message", "配置启用成功");
		}
		else {
			response.put("success", false);
			response.put("message", "配置不存在或启用失败");
		}

		return ResponseEntity.ok(response);
	}

	/**
	 * Disable specified configuration
	 * @param id configuration ID
	 * @return operation result
	 */
	@PostMapping("/{id}/disable")
	public ResponseEntity<Map<String, Object>> disableConfig(@PathVariable(value = "id") String id) {
		boolean disabled = promptConfigService.disableConfig(id);

		Map<String, Object> response = new HashMap<>();
		if (disabled) {
			response.put("success", true);
			response.put("message", "配置禁用成功");
		}
		else {
			response.put("success", false);
			response.put("message", "配置不存在或禁用失败");
		}

		return ResponseEntity.ok(response);
	}

	/**
	 * Get supported prompt type list
	 * @return prompt type list
	 */
	@GetMapping("/types")
	public ResponseEntity<Map<String, Object>> getSupportedPromptTypes() {
		// Supported prompt types
		String[] types = { "report-generator", "planner", "sql-generator", "python-generator", "rewrite" };

		Map<String, Object> response = new HashMap<>();
		response.put("success", true);
		response.put("data", types);

		return ResponseEntity.ok(response);
	}

	/**
	 * 批量启用配置
	 * @param ids 配置ID列表
	 * @return 操作结果
	 */
	@PostMapping("/batch-enable")
	public ResponseEntity<Map<String, Object>> batchEnableConfigs(@RequestBody List<String> ids) {
		boolean success = promptConfigService.enableConfigs(ids);

		Map<String, Object> response = new HashMap<>();
		if (success) {
			response.put("success", true);
			response.put("message", "批量启用配置成功");
		}
		else {
			response.put("success", false);
			response.put("message", "批量启用配置失败");
		}

		return ResponseEntity.ok(response);
	}

	/**
	 * 批量禁用配置
	 * @param ids 配置ID列表
	 * @return 操作结果
	 */
	@PostMapping("/batch-disable")
	public ResponseEntity<Map<String, Object>> batchDisableConfigs(@RequestBody List<String> ids) {
		boolean success = promptConfigService.disableConfigs(ids);

		Map<String, Object> response = new HashMap<>();
		if (success) {
			response.put("success", true);
			response.put("message", "批量禁用配置成功");
		}
		else {
			response.put("success", false);
			response.put("message", "批量禁用配置失败");
		}

		return ResponseEntity.ok(response);
	}

	/**
	 * 更新配置优先级
	 * @param id 配置ID
	 * @param requestBody 包含优先级的请求体
	 * @return 操作结果
	 */
	@PostMapping("/{id}/priority")
	public ResponseEntity<Map<String, Object>> updatePriority(@PathVariable(value = "id") String id,
			@RequestBody Map<String, Object> requestBody) {
		Integer priority = (Integer) requestBody.get("priority");
		boolean success = promptConfigService.updatePriority(id, priority);

		Map<String, Object> response = new HashMap<>();
		if (success) {
			response.put("success", true);
			response.put("message", "更新优先级成功");
		}
		else {
			response.put("success", false);
			response.put("message", "更新优先级失败");
		}

		return ResponseEntity.ok(response);
	}

	/**
	 * 更新配置显示顺序
	 * @param id 配置ID
	 * @param requestBody 包含显示顺序的请求体
	 * @return 操作结果
	 */
	@PostMapping("/{id}/display-order")
	public ResponseEntity<Map<String, Object>> updateDisplayOrder(@PathVariable(value = "id") String id,
			@RequestBody Map<String, Object> requestBody) {
		Integer displayOrder = (Integer) requestBody.get("displayOrder");
		boolean success = promptConfigService.updateDisplayOrder(id, displayOrder);

		Map<String, Object> response = new HashMap<>();
		if (success) {
			response.put("success", true);
			response.put("message", "更新显示顺序成功");
		}
		else {
			response.put("success", false);
			response.put("message", "更新显示顺序失败");
		}

		return ResponseEntity.ok(response);
	}

}
