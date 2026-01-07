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

import com.alibaba.cloud.ai.dataagent.dto.schema.SemanticModelAddDTO;
import com.alibaba.cloud.ai.dataagent.entity.SemanticModel;
import com.alibaba.cloud.ai.dataagent.service.semantic.SemanticModelService;
import com.alibaba.cloud.ai.dataagent.vo.ApiResponse;
import jakarta.validation.constraints.NotEmpty;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 语义模型配置管理 Controller
 * 
 * <p><b>职责边界</b>：
 * 管理业务语义模型（Semantic Model），用于将业务术语映射到数据库表字段，
 * 帮助 NL2SQL 节点更准确地理解用户的自然语言查询。
 * 
 * <p><b>核心概念</b>：
 * - <b>语义模型（SemanticModel）</b>：定义业务术语与数据库字段的映射关系
 *   例如："销售额" → "orders.total_amount"、"用户数" → "COUNT(DISTINCT users.id)"
 * - <b>智能体级别（agentId）</b>：每个智能体可以有自己的语义模型配置
 * - <b>启用状态（isActive）</b>：只有启用的语义模型才会在 SQL 生成时被使用
 * 
 * <p><b>典型使用场景</b>：
 * 1. 用户查询"最近一个月的销售额" → 系统通过语义模型知道"销售额"对应 orders.total_amount
 * 2. 用户查询"活跃用户数" → 系统通过语义模型知道需要 COUNT(DISTINCT users.id) 并加上活跃条件
 * 3. 不同行业的智能体使用不同的业务术语 → 为每个智能体配置独立的语义模型
 * 
 * <p><b>与其他模块的关系</b>：
 * - 被 {@code TableRelationNode} 读取并构建为语义模型 Prompt，存储到 state 的 {@code GENEGRATED_SEMANTIC_MODEL_PROMPT} 中
 * - 被 {@code PlannerNode} 在生成计划时使用（从 state 读取并注入到计划生成的 Prompt 中）
 * - 通过计划中的执行描述，间接影响 {@code SqlGenerateNode} 的 SQL 生成
 * - 配置的启用/禁用会实时影响计划生成的准确性
 * 
 * <p><b>主要端点</b>：
 * - GET /?agentId={agentId}：查询指定智能体的语义模型列表
 * - POST /：创建新的语义模型
 * - PUT /{id}：更新语义模型
 * - PUT /enable、/disable：批量启用/禁用语义模型
 * 
 * @author Makoto
 */
@Slf4j
@RestController
@RequestMapping("/api/semantic-model")
@CrossOrigin(origins = "*")
@AllArgsConstructor
public class SemanticModelController {

	private final SemanticModelService semanticModelService;

	/**
	 * 查询语义模型列表
	 * 
	 * <p><b>用途</b>：前端在"语义模型管理"页面展示列表时调用。
	 * 
	 * <p><b>查询逻辑</b>：
	 * - 如果提供 {@code keyword}，按关键词搜索（模糊匹配名称、描述等）
	 * - 如果提供 {@code agentId}，返回该智能体的所有语义模型
	 * - 如果都不提供，返回所有语义模型
	 * 
	 * @param keyword 搜索关键词（可选）
	 * @param agentId 智能体ID（可选）
	 * @return 语义模型列表
	 */
	@GetMapping
	public ApiResponse<List<SemanticModel>> list(@RequestParam(value = "keyword", required = false) String keyword,
			@RequestParam(value = "agentId", required = false) Long agentId) {
		List<SemanticModel> result;
		if (keyword != null && !keyword.trim().isEmpty()) {
			result = semanticModelService.search(keyword);
		}
		else if (agentId != null) {
			result = semanticModelService.getByAgentId(agentId);
		}
		else {
			result = semanticModelService.getAll();
		}
		return ApiResponse.success("success list semanticModel", result);
	}

	@GetMapping("/{id}")
	public ApiResponse<SemanticModel> get(@PathVariable(value = "id") Long id) {
		SemanticModel model = semanticModelService.getById(id);
		return ApiResponse.success("success retrieve semanticModel", model);
	}

	/**
	 * 创建语义模型
	 * 
	 * <p><b>用途</b>：用户在前端添加新的业务术语映射时调用。
	 * 
	 * <p><b>关键参数</b>：
	 * - {@code semanticModelAddDto.agentId}：所属智能体ID
	 * - {@code semanticModelAddDto.termName}：业务术语（如"销售额"）
	 * - {@code semanticModelAddDto.fieldMapping}：对应的数据库字段或表达式（如"orders.total_amount"）
	 * - {@code semanticModelAddDto.description}：说明（可选）
	 * 
	 * <p><b>副作用</b>：
	 * - 创建后，如果启用，下次 SQL 生成时会立即生效
	 * 
	 * @param semanticModelAddDto 语义模型数据
	 * @return 创建结果
	 */
	@PostMapping
	public ApiResponse<Boolean> create(@RequestBody @Validated SemanticModelAddDTO semanticModelAddDto) {
		boolean success = semanticModelService.addSemanticModel(semanticModelAddDto);
		if (success) {
			return ApiResponse.success("Semantic model created successfully", true);
		}
		else {
			return ApiResponse.error("Failed to create semantic model");
		}
	}

	@PutMapping("/{id}")
	public ApiResponse<SemanticModel> update(@PathVariable(value = "id") Long id, @RequestBody SemanticModel model) {
		if (semanticModelService.getById(id) == null) {
			return ApiResponse.error("Semantic model not found");
		}
		model.setId(id);
		semanticModelService.updateSemanticModel(id, model);
		return ApiResponse.success("Semantic model updated successfully", model);
	}

	@DeleteMapping("/{id}")
	public ApiResponse<Boolean> delete(@PathVariable(value = "id") Long id) {
		if (semanticModelService.getById(id) == null) {
			return ApiResponse.error("Semantic model not found");
		}
		semanticModelService.deleteSemanticModel(id);
		return ApiResponse.success("Semantic model deleted successfully", true);
	}

	/**
	 * 批量启用语义模型
	 * 
	 * <p><b>用途</b>：用户在前端勾选多个语义模型并点击"启用"时调用。
	 * 
	 * <p><b>副作用</b>：
	 * - 启用后，这些语义模型会在下次 SQL 生成时被注入到 Prompt 中
	 * - 帮助 LLM 更准确地理解用户的业务术语
	 * 
	 * @param ids 语义模型ID列表
	 * @return 操作结果
	 */
	@PutMapping("/enable")
	public ApiResponse<Boolean> enableFields(@RequestBody @NotEmpty(message = "ID列表不能为空") List<Long> ids) {
		semanticModelService.enableSemanticModels(ids);
		return ApiResponse.success("Semantic models enabled successfully", true);
	}

	// Disable
	@PutMapping("/disable")
	public ApiResponse<Boolean> disableFields(@RequestBody @NotEmpty(message = "ID列表不能为空") List<Long> ids) {
		ids.forEach(semanticModelService::disableSemanticModel);
		return ApiResponse.success("Semantic models disabled successfully", true);
	}

}
