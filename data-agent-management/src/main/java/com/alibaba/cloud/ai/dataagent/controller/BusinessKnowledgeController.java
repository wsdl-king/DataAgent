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

import com.alibaba.cloud.ai.dataagent.dto.knowledge.businessknowledge.CreateBusinessKnowledgeDTO;
import com.alibaba.cloud.ai.dataagent.dto.knowledge.businessknowledge.UpdateBusinessKnowledgeDTO;
import com.alibaba.cloud.ai.dataagent.service.business.BusinessKnowledgeService;
import com.alibaba.cloud.ai.dataagent.vo.ApiResponse;
import com.alibaba.cloud.ai.dataagent.vo.BusinessKnowledgeVO;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.util.StringUtils;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 业务知识管理 Controller
 * 
 * <p><b>职责边界</b>：
 * 管理业务知识（Business Knowledge），与智能体知识库（Agent Knowledge）类似，
 * 但侧重于业务规则、术语定义、计算逻辑等结构化知识。
 * 
 * <p><b>核心概念</b>：
 * - <b>业务知识（BusinessKnowledge）</b>：业务领域的专业知识，如：
 *   - 业务术语定义（如"活跃用户"的定义）
 *   - 计算规则（如"销售额 = 订单金额 - 退款金额"）
 *   - 业务约束（如"查询时间范围不超过1年"）
 * - <b>召回状态（isRecall）</b>：控制该知识是否参与检索
 * - <b>向量化</b>：与 AgentKnowledge 相同，会向量化并存储到向量库
 * 
 * <p><b>与 AgentKnowledge 的区别</b>：
 * - AgentKnowledge：通用知识，如文档、FAQ、产品说明
 * - BusinessKnowledge：业务知识，如术语定义、计算规则、业务约束
 * - 两者在技术实现上相似，但在业务语义上有区分
 * 
 * <p><b>典型使用场景</b>：
 * 1. 定义"活跃用户" → 创建业务知识 → 用户查询时自动注入定义
 * 2. 定义"销售额"计算规则 → SQL 生成时参考该规则
 * 3. 定义业务约束 → LLM 生成计划时遵守约束
 * 
 * <p><b>与其他模块的关系</b>：
 * - 被 {@code EvidenceRecallNode} 使用（与 AgentKnowledge 一起检索，通过向量库检索）
 * - 检索结果作为 evidence 存储到 state 的 {@code EVIDENCE} 中
 * - 被 {@code QueryEnhanceNode} 间接使用（从 state 读取 EVIDENCE 并注入到查询增强 Prompt 中）
 * - 向量化逻辑与 AgentKnowledge 共享（都使用 {@code AgentVectorStoreService}）
 * 
 * <p><b>主要端点</b>：
 * - GET /?agentId={agentId}：查询指定智能体的业务知识列表
 * - POST /：创建业务知识
 * - PUT /{id}：更新业务知识
 * - POST /recall/{id}：启用/禁用召回
 * - POST /refresh-vector-store：刷新向量库（重新向量化所有知识）
 * 
 * @author Makoto
 */
@Slf4j
@RestController
@RequestMapping("/api/business-knowledge")
@CrossOrigin(origins = "*")
@AllArgsConstructor
public class BusinessKnowledgeController {

	private final BusinessKnowledgeService businessKnowledgeService;

	@GetMapping
	public ApiResponse<List<BusinessKnowledgeVO>> list(@RequestParam(value = "agentId") String agentIdStr,
			@RequestParam(value = "keyword", required = false) String keyword) {
		List<BusinessKnowledgeVO> result;
		Long agentId = Long.parseLong(agentIdStr);

		if (StringUtils.hasText(keyword)) {
			result = businessKnowledgeService.searchKnowledge(agentId, keyword);
		}
		else {
			result = businessKnowledgeService.getKnowledge(agentId);
		}
		return ApiResponse.success("success list businessKnowledge", result);
	}

	@GetMapping("/{id}")
	public ApiResponse<BusinessKnowledgeVO> get(@PathVariable(value = "id") Long id) {
		BusinessKnowledgeVO vo = businessKnowledgeService.getKnowledgeById(id);
		if (vo == null) {
			return ApiResponse.error("businessKnowledge not found");
		}
		return ApiResponse.success("success get businessKnowledge", vo);
	}

	@PostMapping
	public ApiResponse<BusinessKnowledgeVO> create(@RequestBody @Validated CreateBusinessKnowledgeDTO knowledge) {
		return ApiResponse.success("success create businessKnowledge",
				businessKnowledgeService.addKnowledge(knowledge));
	}

	@PutMapping("/{id}")
	public ApiResponse<BusinessKnowledgeVO> update(@PathVariable(value = "id") Long id,
			@RequestBody UpdateBusinessKnowledgeDTO knowledge) {

		return ApiResponse.success("success update businessKnowledge",
				businessKnowledgeService.updateKnowledge(id, knowledge));
	}

	@DeleteMapping("/{id}")
	public ApiResponse<Boolean> delete(@PathVariable(value = "id") Long id) {
		if (businessKnowledgeService.getKnowledgeById(id) == null) {
			return ApiResponse.error("businessKnowledge not found");
		}
		businessKnowledgeService.deleteKnowledge(id);
		return ApiResponse.success("success delete businessKnowledge");
	}

	@PostMapping("/recall/{id}")
	public ApiResponse<Boolean> recallKnowledge(@PathVariable(value = "id") Long id,
			@RequestParam(value = "isRecall") Boolean isRecall) {
		businessKnowledgeService.recallKnowledge(id, isRecall);
		return ApiResponse.success("success update recall businessKnowledge");
	}

	/**
	 * 刷新向量库（重新向量化所有业务知识）
	 * 
	 * <p><b>用途</b>：当 Embedding 模型切换或向量库数据损坏时，批量重新向量化。
	 * 
	 * <p><b>处理流程</b>：
	 * 1. 查询指定智能体的所有业务知识
	 * 2. 删除向量库中的旧数据
	 * 3. 重新向量化所有知识并存储到向量库
	 * 
	 * <p><b>典型场景</b>：
	 * - 切换了 Embedding 模型（如从 text-embedding-ada-002 切换到 text-embedding-3-large）
	 * - 向量库数据损坏或丢失
	 * - 批量更新知识后，需要重新向量化
	 * 
	 * <p><b>注意</b>：
	 * - 此操作耗时较长，建议在后台异步执行
	 * - 刷新期间，检索可能返回不完整的结果
	 * 
	 * @param agentId 智能体ID
	 * @return 操作结果
	 */
	@PostMapping("/refresh-vector-store")
	public ApiResponse<Boolean> refreshAllKnowledgeToVectorStore(@RequestParam(value = "agentId") String agentId) {
		// 校验 agentId 不为空和空字符串
		if (!StringUtils.hasText(agentId)) {
			return ApiResponse.error("agentId cannot be empty");
		}

		try {
			businessKnowledgeService.refreshAllKnowledgeToVectorStore(agentId);
			return ApiResponse.success("success refresh vector store");
		}
		catch (Exception e) {
			log.error("Failed to refresh vector store for agentId: {}", agentId, e);
			return ApiResponse.error("Failed to refresh vector store");
		}
	}

	/**
	 * 重试向量化（失败时使用）
	 * 
	 * <p><b>用途</b>：当业务知识向量化失败时，用户点击"重试"按钮调用。
	 * 
	 * <p><b>处理流程</b>：
	 * 1. 重置状态为 PENDING
	 * 2. 重新触发异步向量化任务
	 * 
	 * <p><b>失败原因</b>：
	 * - Embedding 模型调用失败（如 API Key 错误、网络超时）
	 * - 向量库连接失败（如 Milvus 服务不可用）
	 * - 知识内容为空或格式错误
	 * 
	 * @param id 业务知识ID
	 * @return 操作结果
	 */
	@PostMapping("/retry-embedding/{id}")
	public ApiResponse<Boolean> retryEmbedding(@PathVariable(value = "id") Long id) {
		businessKnowledgeService.retryEmbedding(id);
		return ApiResponse.success("success retry embedding");
	}

}
