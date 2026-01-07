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

import com.alibaba.cloud.ai.dataagent.vo.PageResult;
import com.alibaba.cloud.ai.dataagent.dto.knowledge.agentknowledge.AgentKnowledgeQueryDTO;
import com.alibaba.cloud.ai.dataagent.dto.knowledge.agentknowledge.CreateKnowledgeDTO;
import com.alibaba.cloud.ai.dataagent.dto.knowledge.agentknowledge.UpdateKnowledgeDTO;
import com.alibaba.cloud.ai.dataagent.service.knowledge.AgentKnowledgeService;
import com.alibaba.cloud.ai.dataagent.vo.AgentKnowledgeVO;
import com.alibaba.cloud.ai.dataagent.vo.ApiResponse;
import com.alibaba.cloud.ai.dataagent.vo.PageResponse;
import jakarta.validation.Valid;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 智能体知识库管理 Controller
 * 
 * <p><b>职责边界</b>：
 * 管理智能体的知识库（Agent Knowledge），支持文本、文件（PDF、Word、TXT 等）的上传、
 * 向量化、检索和管理。知识库内容会在工作流的证据召回（Evidence Recall）阶段被检索并注入到 Prompt 中。
 * 
 * <p><b>核心概念</b>：
 * - <b>知识库（AgentKnowledge）</b>：智能体的专属知识，可以是业务文档、FAQ、产品说明等
 * - <b>向量化（Embedding）</b>：将知识内容转换为向量并存储到向量数据库（如 Milvus）
 * - <b>召回状态（isRecall）</b>：控制该知识是否参与检索（可临时禁用某些知识）
 * - <b>向量化状态</b>：
 *   - PENDING：等待向量化
 *   - PROCESSING：向量化中
 *   - COMPLETED：向量化完成
 *   - FAILED：向量化失败
 * 
 * <p><b>典型使用场景</b>：
 * 1. 用户上传产品说明文档 → 系统解析文档 → 向量化 → 存储到向量库
 * 2. 用户查询"产品价格" → 系统从向量库检索相关知识 → 注入到 Prompt → LLM 生成答案
 * 3. 某个知识过期 → 用户禁用召回（isRecall=false）→ 该知识不再参与检索
 * 4. 向量化失败 → 用户点击"重试" → 系统重新解析和向量化
 * 
 * <p><b>与其他模块的关系</b>：
 * - 被 {@code EvidenceRecallNode} 使用（检索相关知识）
 * - 向量化任务由 {@code AgentKnowledgeService} 异步处理
 * - 文件解析使用 {@code FileParserService}（支持 PDF、Word、TXT 等）
 * - 向量存储使用 {@code VectorStore}（如 Milvus）
 * 
 * <p><b>主要端点</b>：
 * - POST /create：创建知识（支持文本或文件上传）
 * - PUT /{id}：更新知识
 * - PUT /recall/{id}：启用/禁用召回
 * - POST /query/page：分页查询知识列表
 * - POST /retry-embedding/{id}：重试向量化（失败时使用）
 * 
 * @author Makoto
 */
@Slf4j
@RestController
@RequestMapping("/api/agent-knowledge")
@CrossOrigin(origins = "*")
@AllArgsConstructor
public class AgentKnowledgeController {

	private final AgentKnowledgeService agentKnowledgeService;

	/**
	 * Query knowledge details by ID
	 */
	@GetMapping("/{id}")
	public ApiResponse<AgentKnowledgeVO> getKnowledgeById(@PathVariable("id") Integer id) {
		try {
			AgentKnowledgeVO knowledge = agentKnowledgeService.getKnowledgeById(id);
			if (knowledge != null) {
				return ApiResponse.success("查询成功", knowledge);
			}
			else {
				return ApiResponse.error("知识不存在");
			}
		}
		catch (Exception e) {
			log.error("查询知识详情失败：{}", e.getMessage());
			return ApiResponse.error("查询知识详情失败：" + e.getMessage());
		}
	}

	/**
	 * 创建知识库条目（支持文本或文件上传）
	 * 
	 * <p><b>用途</b>：用户在前端添加新的知识时调用。
	 * 
	 * <p><b>关键参数</b>：
	 * - {@code createKnowledgeDto.agentId}：所属智能体ID
	 * - {@code createKnowledgeDto.title}：知识标题
	 * - {@code createKnowledgeDto.content}：文本内容（如果是文本知识）
	 * - {@code createKnowledgeDto.file}：文件（如果是文件知识，支持 PDF、Word、TXT 等）
	 * - {@code createKnowledgeDto.isRecall}：是否启用召回（默认 true）
	 * 
	 * <p><b>处理流程</b>：
	 * 1. 保存知识到数据库（状态：PENDING）
	 * 2. 如果是文件，异步解析文件内容
	 * 3. 异步向量化内容并存储到向量库
	 * 4. 更新状态为 COMPLETED 或 FAILED
	 * 
	 * <p><b>副作用</b>：
	 * - 创建后会立即触发异步向量化任务
	 * - 向量化完成前，该知识不会参与检索
	 * - 前端需要轮询或监听状态变化
	 * 
	 * @param createKnowledgeDto 知识数据
	 * @return 创建的知识对象（包含ID和初始状态）
	 */
	@PostMapping("/create")
	public ApiResponse<AgentKnowledgeVO> createKnowledge(@Valid CreateKnowledgeDTO createKnowledgeDto) {
		AgentKnowledgeVO knowledge = agentKnowledgeService.createKnowledge(createKnowledgeDto);
		return ApiResponse.success("创建知识成功，后台向量存储开始更新，请耐心等待...", knowledge);
	}

	/**
	 * Update knowledge
	 */
	@PutMapping("/{id}")
	public ApiResponse<AgentKnowledgeVO> updateKnowledge(@PathVariable("id") Integer id,
			@RequestBody UpdateKnowledgeDTO updateKnowledgeDto) {
		AgentKnowledgeVO knowledge = agentKnowledgeService.updateKnowledge(id, updateKnowledgeDto);
		return ApiResponse.success("更新成功", knowledge);
	}

	/**
	 * 启用/禁用知识召回
	 * 
	 * <p><b>用途</b>：用户希望临时禁用某个知识（不删除）时调用。
	 * 
	 * <p><b>关键逻辑</b>：
	 * - {@code isRecall=true}：该知识会参与向量检索，可能被注入到 Prompt
	 * - {@code isRecall=false}：该知识不参与检索，相当于"软删除"
	 * 
	 * <p><b>典型场景</b>：
	 * - 某个知识过期但不想删除 → 设置 isRecall=false
	 * - 测试时临时禁用某些知识 → 设置 isRecall=false
	 * - 后续需要恢复 → 设置 isRecall=true
	 * 
	 * <p><b>注意</b>：
	 * - 禁用召回不会删除向量数据，只是检索时跳过
	 * - 如果需要彻底删除，应调用 DELETE /{id} 接口
	 * 
	 * @param id 知识ID
	 * @param isRecall 是否启用召回
	 * @return 更新后的知识对象
	 */
	@PutMapping("/recall/{id}")
	public ApiResponse<AgentKnowledgeVO> updateRecallStatus(@PathVariable Integer id,
			@RequestParam(value = "isRecall") Boolean isRecall) {
		AgentKnowledgeVO agentKnowledgeVO = agentKnowledgeService.updateKnowledgeRecallStatus(id, isRecall);
		return ApiResponse.success("更新成功", agentKnowledgeVO);
	}

	/**
	 * Delete knowledge
	 */
	@DeleteMapping("/{id}")
	public ApiResponse<Boolean> deleteKnowledge(@PathVariable("id") Integer id) {
		return agentKnowledgeService.deleteKnowledge(id) ? ApiResponse.success("删除操作已接收，等待后台删除相关资源...")
				: ApiResponse.error("删除失败");
	}

	@PostMapping("/query/page")
	public PageResponse<List<AgentKnowledgeVO>> queryByPage(@Valid @RequestBody AgentKnowledgeQueryDTO queryDTO) {
		try {
			PageResult<AgentKnowledgeVO> pageResult = agentKnowledgeService.queryByConditionsWithPage(queryDTO);
			return PageResponse.success(pageResult.getData(), pageResult.getTotal(), pageResult.getPageNum(),
					pageResult.getPageSize(), pageResult.getTotalPages());
		}
		catch (Exception e) {
			log.error("分页查询知识列表失败：{}", e.getMessage());
			return PageResponse.pageError("分页查询失败：" + e.getMessage());
		}
	}

	/**
	 * 重试向量化（失败时使用）
	 * 
	 * <p><b>用途</b>：当知识向量化失败时，用户点击"重试"按钮调用。
	 * 
	 * <p><b>失败原因</b>：
	 * - 文件解析失败（如 PDF 格式损坏）
	 * - Embedding 模型调用失败（如 API Key 错误、网络超时）
	 * - 向量库连接失败（如 Milvus 服务不可用）
	 * 
	 * <p><b>处理流程</b>：
	 * 1. 重置状态为 PENDING
	 * 2. 重新触发异步向量化任务
	 * 3. 如果是文件，会重新解析文件内容
	 * 
	 * <p><b>注意</b>：
	 * - 重试前应先检查失败原因（如修复 Embedding 模型配置）
	 * - 文件解析可能需要较长时间，前端需要提示用户耐心等待
	 * 
	 * @param id 知识ID
	 * @return 操作结果
	 */
	@PostMapping("/retry-embedding/{id}")
	public ApiResponse<AgentKnowledgeVO> retryEmbedding(@PathVariable Integer id) {
		agentKnowledgeService.retryEmbedding(id);
		return ApiResponse.success("重试向量化操作成功，如果是文件解析需要花费点时间，请耐心等待...");
	}

}
