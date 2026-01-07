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

import com.alibaba.cloud.ai.dataagent.entity.Agent;
import com.alibaba.cloud.ai.dataagent.service.agent.AgentService;
import com.alibaba.cloud.ai.dataagent.vo.ApiKeyResponse;
import com.alibaba.cloud.ai.dataagent.vo.ApiResponse;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 智能体（Agent）管理接口。
 *
 * <p>
 * Agent 是 DataAgent 的“运行配置单元”，典型包含：提示词、分类、状态、关联数据源、API Key 开关等。
 * 前端的 Agent 列表/详情页/发布下线/Key 管理，都通过本 Controller 调用。
 * </p>
 *
 * <p>
 * 与运行时的关系：Graph 执行时会传入 agentId（见 {@link GraphController}），后端会按 agent 维度加载配置，
 * 如：数据源、提示词优化配置、知识库召回策略、模型配置等。
 * </p>
 */
@Slf4j
@RestController
@RequestMapping("/api/agent")
@CrossOrigin(origins = "*")
@AllArgsConstructor
public class AgentController {

	private final AgentService agentService;

	/**
	 * 获取智能体列表（可按状态/关键词过滤）。
	 */
	@GetMapping("/list")
	public ResponseEntity<List<Agent>> list(@RequestParam(value = "status", required = false) String status,
			@RequestParam(value = "keyword", required = false) String keyword) {
		List<Agent> result;
		if (keyword != null && !keyword.trim().isEmpty()) {
			result = agentService.search(keyword);
		}
		else if (status != null && !status.trim().isEmpty()) {
			result = agentService.findByStatus(status);
		}
		else {
			result = agentService.findAll();
		}
		return ResponseEntity.ok(result);
	}

	/**
	 * Get agent details by ID
	 */
	@GetMapping("/{id}")
	public ResponseEntity<Agent> get(@PathVariable(value = "id") Long id) {
		Agent agent = agentService.findById(id);
		if (agent == null) {
			return ResponseEntity.notFound().build();
		}
		return ResponseEntity.ok(agent);
	}

	/**
	 * Create agent
	 */
	@PostMapping
	public ResponseEntity<Agent> create(@RequestBody Agent agent) {
		// Set default status
		if (agent.getStatus() == null || agent.getStatus().trim().isEmpty()) {
			agent.setStatus("draft");
		}
		Agent saved = agentService.save(agent);
		return ResponseEntity.ok(saved);
	}

	/**
	 * Update agent
	 */
	@PutMapping("/{id}")
	public ResponseEntity<Agent> update(@PathVariable(value = "id") Long id, @RequestBody Agent agent) {
		if (agentService.findById(id) == null) {
			return ResponseEntity.notFound().build();
		}
		agent.setId(id);
		Agent updated = agentService.save(agent);
		return ResponseEntity.ok(updated);
	}

	/**
	 * Delete agent
	 */
	@DeleteMapping("/{id}")
	public ResponseEntity<Void> delete(@PathVariable(value = "id") Long id) {
		if (agentService.findById(id) == null) {
			return ResponseEntity.notFound().build();
		}
		agentService.deleteById(id);
		return ResponseEntity.ok().build();
	}

	/**
	 * 发布智能体（状态变为 published）。
	 *
	 * <p>
	 * 通常表示可以在运行页面被选择并执行（具体约束由前端/服务层决定）。
	 * </p>
	 */
	@PostMapping("/{id}/publish")
	public ResponseEntity<Agent> publish(@PathVariable(value = "id") Long id) {
		Agent agent = agentService.findById(id);
		if (agent == null) {
			return ResponseEntity.notFound().build();
		}
		agent.setStatus("published");
		Agent updated = agentService.save(agent);
		return ResponseEntity.ok(updated);
	}

	/**
	 * 下线智能体（状态变为 offline）。
	 */
	@PostMapping("/{id}/offline")
	public ResponseEntity<Agent> offline(@PathVariable(value = "id") Long id) {
		Agent agent = agentService.findById(id);
		if (agent == null) {
			return ResponseEntity.notFound().build();
		}
		agent.setStatus("offline");
		Agent updated = agentService.save(agent);
		return ResponseEntity.ok(updated);
	}

	/**
	 * 获取 API Key 的脱敏展示与启用状态。
	 *
	 * <p>
	 * 用于前端“接入 API”页面展示：是否启用、以及脱敏后的 key（避免泄露）。
	 * </p>
	 */
	@GetMapping("/{id}/api-key")
	public ResponseEntity<ApiResponse<ApiKeyResponse>> getApiKey(@PathVariable("id") Long id) {
		Agent agent = agentService.findById(id);
		if (agent == null) {
			return ResponseEntity.notFound().build();
		}
		String masked = agentService.getApiKeyMasked(id);
		return ResponseEntity.ok(buildApiKeyResponse(masked, agent.getApiKeyEnabled(), "获取 API Key 成功"));
	}

	/**
	 * Generate API Key
	 */
	@PostMapping("/{id}/api-key/generate")
	public ResponseEntity<ApiResponse<ApiKeyResponse>> generateApiKey(@PathVariable("id") Long id) {
		Agent existing = agentService.findById(id);
		if (existing == null) {
			return ResponseEntity.notFound().build();
		}
		Agent agent = agentService.generateApiKey(id);
		return ResponseEntity.ok(buildApiKeyResponse(agent.getApiKey(), agent.getApiKeyEnabled(), "生成 API Key 成功"));
	}

	/**
	 * Reset API Key
	 */
	@PostMapping("/{id}/api-key/reset")
	public ResponseEntity<ApiResponse<ApiKeyResponse>> resetApiKey(@PathVariable("id") Long id) {
		Agent existing = agentService.findById(id);
		if (existing == null) {
			return ResponseEntity.notFound().build();
		}
		Agent agent = agentService.resetApiKey(id);
		return ResponseEntity.ok(buildApiKeyResponse(agent.getApiKey(), agent.getApiKeyEnabled(), "重置 API Key 成功"));
	}

	/**
	 * Delete API Key
	 */
	@DeleteMapping("/{id}/api-key")
	public ResponseEntity<ApiResponse<ApiKeyResponse>> deleteApiKey(@PathVariable("id") Long id) {
		Agent existing = agentService.findById(id);
		if (existing == null) {
			return ResponseEntity.notFound().build();
		}
		Agent agent = agentService.deleteApiKey(id);
		return ResponseEntity.ok(buildApiKeyResponse(agent.getApiKey(), agent.getApiKeyEnabled(), "删除 API Key 成功"));
	}

	/**
	 * 启用/禁用 API Key 校验开关。
	 *
	 * <p>
	 * enabled=true：表示外部调用 API 时需要带正确 key（由 filter/interceptor 等实现校验，非本 Controller 负责）。
	 * </p>
	 */
	@PostMapping("/{id}/api-key/enable")
	public ResponseEntity<ApiResponse<ApiKeyResponse>> toggleApiKey(@PathVariable("id") Long id,
			@RequestParam("enabled") boolean enabled) {
		Agent existing = agentService.findById(id);
		if (existing == null) {
			return ResponseEntity.notFound().build();
		}
		Agent agent = agentService.toggleApiKey(id, enabled);
		return ResponseEntity.ok(buildApiKeyResponse(agent.getApiKey() == null ? null : "****",
				agent.getApiKeyEnabled(), "更新 API Key 状态成功"));
	}

	private ApiResponse<ApiKeyResponse> buildApiKeyResponse(String apiKey, Integer apiKeyEnabled, String message) {
		return ApiResponse.success(message, new ApiKeyResponse(apiKey, apiKeyEnabled));
	}

}
