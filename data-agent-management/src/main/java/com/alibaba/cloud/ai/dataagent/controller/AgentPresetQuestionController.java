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

import com.alibaba.cloud.ai.dataagent.entity.AgentPresetQuestion;
import com.alibaba.cloud.ai.dataagent.service.agent.AgentPresetQuestionService;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 智能体预设问题管理 Controller
 * 
 * <p><b>职责边界</b>：
 * 管理智能体的预设问题（Preset Questions），即前端展示的"快捷提问"或"示例问题"，
 * 帮助用户快速了解智能体的能力并开始对话。
 * 
 * <p><b>核心概念</b>：
 * - <b>预设问题（AgentPresetQuestion）</b>：智能体预先配置的示例问题，如：
 *   - "查询最近一个月的销售额"
 *   - "分析用户购买行为"
 *   - "生成销售报表"
 * - <b>激活状态（isActive）</b>：控制该问题是否在前端展示
 * 
 * <p><b>典型使用场景</b>：
 * 1. 用户创建智能体时，配置几个典型的示例问题
 * 2. 用户进入对话页面，看到预设问题列表，点击即可快速提问
 * 3. 管理员禁用某些过时的问题（isActive=false）
 * 
 * <p><b>与其他模块的关系</b>：
 * - 被前端对话页面使用（展示快捷提问按钮）
 * - 点击预设问题后，会调用 {@code GraphController} 的 /stream/search 接口
 * - 不影响工作流逻辑，纯粹是前端交互优化
 * 
 * <p><b>主要端点</b>：
 * - GET /{agentId}/preset-questions：查询指定智能体的预设问题列表
 * - POST /{agentId}/preset-questions：批量保存预设问题（会替换现有问题）
 * - DELETE /{agentId}/preset-questions/{questionId}：删除单个预设问题
 * 
 * @author Makoto
 */
@Slf4j
@RestController
@RequestMapping("/api/agent")
@CrossOrigin(origins = "*")
@AllArgsConstructor
// todo: 部分返回值和参数需要定义DTO
public class AgentPresetQuestionController {

	private final AgentPresetQuestionService presetQuestionService;

	/**
	 * 查询智能体的预设问题列表
	 * 
	 * <p><b>用途</b>：前端在对话页面加载时调用，展示快捷提问按钮。
	 * 
	 * <p><b>返回值</b>：
	 * - 返回该智能体的所有预设问题（包括已禁用的）
	 * - 前端可根据 {@code isActive} 字段过滤显示
	 * 
	 * <p><b>典型场景</b>：
	 * 用户进入对话页面 → 前端调用此接口 → 展示预设问题按钮 → 用户点击 → 自动填充到输入框
	 * 
	 * @param agentId 智能体ID
	 * @return 预设问题列表
	 */
	@GetMapping("/{agentId}/preset-questions")
	public ResponseEntity<List<AgentPresetQuestion>> getPresetQuestions(@PathVariable(value = "agentId") Long agentId) {
		try {
			List<AgentPresetQuestion> questions = presetQuestionService.findAllByAgentId(agentId);
			return ResponseEntity.ok(questions);
		}
		catch (Exception e) {
			log.error("Error getting preset questions for agent {}", agentId, e);
			return ResponseEntity.internalServerError().build();
		}
	}

	/**
	 * 批量保存智能体的预设问题（会替换现有问题）
	 * 
	 * <p><b>用途</b>：用户在前端编辑智能体时，批量保存预设问题。
	 * 
	 * <p><b>关键逻辑</b>：
	 * - 会先删除该智能体的所有现有预设问题
	 * - 然后批量插入新的预设问题
	 * - 支持设置每个问题的 {@code isActive} 状态
	 * 
	 * <p><b>典型场景</b>：
	 * 用户在编辑智能体页面 → 添加/删除/修改预设问题 → 点击"保存" → 调用此接口
	 * 
	 * @param agentId 智能体ID
	 * @param questionsData 预设问题列表（包含 question 和 isActive 字段）
	 * @return 操作结果
	 */
	@PostMapping("/{agentId}/preset-questions")
	public ResponseEntity<Map<String, String>> savePresetQuestions(@PathVariable(value = "agentId") Long agentId,
			@RequestBody List<Map<String, Object>> questionsData) {
		try {
			List<AgentPresetQuestion> questions = questionsData.stream().map(data -> {
				AgentPresetQuestion question = new AgentPresetQuestion();
				question.setQuestion((String) data.get("question"));
				Object isActiveObj = data.get("isActive");
				if (isActiveObj instanceof Boolean) {
					question.setIsActive((Boolean) isActiveObj);
				}
				else if (isActiveObj != null) {
					question.setIsActive(Boolean.parseBoolean(isActiveObj.toString()));
				}
				else {
					question.setIsActive(true);
				}
				return question;
			}).toList();

			presetQuestionService.batchSave(agentId, questions);
			return ResponseEntity.ok(Map.of("message", "预设问题保存成功"));
		}
		catch (Exception e) {
			log.error("Error saving preset questions for agent {}", agentId, e);
			return ResponseEntity.internalServerError().body(Map.of("error", "保存预设问题失败: " + e.getMessage()));
		}
	}

	/**
	 * Delete preset question
	 */
	@DeleteMapping("/{agentId}/preset-questions/{questionId}")
	public ResponseEntity<Map<String, String>> deletePresetQuestion(@PathVariable(value = "agentId") Long agentId,
			@PathVariable Long questionId) {
		try {
			presetQuestionService.deleteById(questionId);
			return ResponseEntity.ok(Map.of("message", "预设问题删除成功"));
		}
		catch (Exception e) {
			log.error("Error deleting preset question {} for agent {}", questionId, agentId, e);
			return ResponseEntity.internalServerError().body(Map.of("error", "删除预设问题失败: " + e.getMessage()));
		}
	}

}
