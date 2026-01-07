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

import com.alibaba.cloud.ai.dataagent.dto.datasource.ToggleDatasourceDTO;
import com.alibaba.cloud.ai.dataagent.dto.datasource.UpdateDatasourceTablesDTO;
import com.alibaba.cloud.ai.dataagent.entity.AgentDatasource;
import com.alibaba.cloud.ai.dataagent.service.datasource.AgentDatasourceService;
import com.alibaba.cloud.ai.dataagent.vo.ApiResponse;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Optional;

/**
 * 智能体-数据源绑定与 Schema 初始化接口。
 *
 * <p>
 * 这部分把“智能体（agent）”与“数据源（datasource）”关联起来，并提供把 Schema/表结构写入向量库的能力：
 * - 选择当前 agent 使用哪个 datasource（以及选哪些表）
 * - 将选中表的 schema 信息初始化到向量存储（用于后续 schema recall / NL2SQL）
 * </p>
 *
 * <p>
 * 前端对应场景：Agent 配置页里“选择数据源/选择表/初始化信息源（向量化 schema）”等功能。
 * </p>
 */
@Slf4j
@RestController
@RequestMapping("/api/agent/{agentId}/datasources")
@CrossOrigin(origins = "*")
@AllArgsConstructor
public class AgentDatasourceController {

	private final AgentDatasourceService agentDatasourceService;

	/**
	 * 初始化（向量化）Schema：把 agent 当前选择的数据源、表结构写入向量库。
	 *
	 * <p>
	 * 这样后续 {@code SchemaRecallNode} 才能通过检索找到相关表/字段信息，为 SQL 生成提供上下文。
	 * </p>
	 */
	@PostMapping("/init")
	public ResponseEntity<ApiResponse> initSchema(@PathVariable(value = "agentId") Long agentId) {
		// 防止前端恶意请求，dto数据应该在后端获取
		try {
			AgentDatasource agentDatasource = agentDatasourceService.getCurrentAgentDatasource(agentId.intValue());
			log.info("Initializing schema for agent: {}", agentId);

			// Extract data source ID and table list from request
			Integer datasourceId = agentDatasource.getDatasourceId();
			List<String> tables = Optional.ofNullable(agentDatasource.getSelectTables()).orElse(List.of());

			// Validate request parameters
			if (datasourceId == null) {
				return ResponseEntity.badRequest().body(ApiResponse.error("数据源ID不能为空"));
			}

			if (tables.isEmpty()) {
				return ResponseEntity.badRequest().body(ApiResponse.error("表列表不能为空"));
			}

			// Execute Schema initialization
			Boolean result = agentDatasourceService.initializeSchemaForAgentWithDatasource(agentId, datasourceId,
					tables);

			if (result) {
				log.info("Successfully initialized schema for agent: {}, tables: {}", agentId, tables.size());
				return ResponseEntity.ok(ApiResponse.success("Schema初始化成功"));
			}
			else {
				return ResponseEntity.internalServerError().body(ApiResponse.error("Schema初始化失败"));
			}
		}
		catch (Exception e) {
			log.error("Failed to initialize schema for agent: {}", agentId, e);
			return ResponseEntity.internalServerError().body(ApiResponse.error("Schema初始化失败：" + e.getMessage()));
		}
	}

	/**
	 * Get list of data sources configured for agent
	 */
	@GetMapping
	public ResponseEntity<ApiResponse> getAgentDatasource(@PathVariable(value = "agentId") Long agentId) {
		try {
			log.info("Getting datasources for agent: {}", agentId);
			// 返回该 agent 绑定的数据源列表（可能包含历史绑定），用于配置页展示
			List<AgentDatasource> datasources = agentDatasourceService.getAgentDatasource(Math.toIntExact(agentId));
			log.info("Successfully retrieved {} datasources for agent: {}", datasources.size(), agentId);
			return ResponseEntity.ok(ApiResponse.success("操作成功", datasources));
		}
		catch (Exception e) {
			log.error("Failed to get datasources for agent: {}", agentId, e);
			return ResponseEntity.badRequest().body(ApiResponse.error("获取数据源失败：" + e.getMessage(), List.of()));
		}
	}

	@GetMapping("/active")
	public ResponseEntity<ApiResponse> getActiveAgentDatasource(@PathVariable(value = "agentId") Long agentId) {
		try {
			log.info("Getting active datasource for agent: {}", agentId);
			// 返回当前生效的数据源（运行时通常使用它来查询 schema / 执行 SQL）
			AgentDatasource datasource = agentDatasourceService.getCurrentAgentDatasource(Math.toIntExact(agentId));
			return ResponseEntity.ok(ApiResponse.success("操作成功", datasource));
		}
		catch (Exception e) {
			log.error("Failed to get active datasource for agent: {}", agentId, e);
			return ResponseEntity.badRequest().body(ApiResponse.error("获取数据源失败：" + e.getMessage()));
		}
	}

	/**
	 * Add data source for agent
	 */
	@PostMapping("/{datasourceId}")
	public ResponseEntity<ApiResponse> addDatasourceToAgent(@PathVariable(value = "agentId") Integer agentId,
			@PathVariable(value = "datasourceId") Integer datasourceId) {
		try {
			if (datasourceId == null) {
				return ResponseEntity.badRequest().body(ApiResponse.error("数据源ID不能为空"));
			}

			AgentDatasource agentDatasource = agentDatasourceService.addDatasourceToAgent(agentId, datasourceId);
			return ResponseEntity.ok(ApiResponse.success("数据源添加成功", agentDatasource));
		}
		catch (Exception e) {
			return ResponseEntity.badRequest().body(ApiResponse.error("添加失败：" + e.getMessage()));
		}
	}

	// 更新选择的数据表
	@PostMapping("/tables")
	public ResponseEntity<ApiResponse> updateDatasourceTables(@PathVariable("agentId") String agentId,
			@RequestBody @Validated UpdateDatasourceTablesDTO dto) {
		try {
			// 前端“选择表”配置：决定初始化 schema / SQL 生成时可用的表范围
			dto.setTables(Optional.ofNullable(dto.getTables()).orElse(List.of()));
			agentDatasourceService.updateDatasourceTables(Integer.parseInt(agentId), dto.getDatasourceId(),
					dto.getTables());
			return ResponseEntity.ok(ApiResponse.success("更新成功"));
		}
		catch (Exception e) {
			log.error("Error: ", e);
			return ResponseEntity.badRequest().body(ApiResponse.error("更新失败：" + e.getMessage()));
		}
	}

	/**
	 * Remove data source association from agent
	 */
	@DeleteMapping("/{datasourceId}")
	public ResponseEntity<ApiResponse> removeDatasourceFromAgent(@PathVariable("agentId") Integer agentId,
			@PathVariable("datasourceId") Integer datasourceId) {
		try {
			agentDatasourceService.removeDatasourceFromAgent(agentId, datasourceId);
			return ResponseEntity.ok(ApiResponse.success("数据源已移除"));
		}
		catch (Exception e) {
			return ResponseEntity.badRequest().body(ApiResponse.error("移除失败：" + e.getMessage()));
		}
	}

	/**
	 * 启用/禁用智能体的数据源
	 */
	@PutMapping("/toggle")
	public ResponseEntity<ApiResponse> toggleDatasourceForAgent(@PathVariable("agentId") Integer agentId,
			@RequestBody ToggleDatasourceDTO dto) {
		try {
			Boolean isActive = dto.getIsActive();
			Integer datasourceId = dto.getDatasourceId();
			if (isActive == null || datasourceId == null) {
				return ResponseEntity.badRequest().body(ApiResponse.error("激活状态不能为空"));
			}
			AgentDatasource agentDatasource = agentDatasourceService.toggleDatasourceForAgent(agentId,
					dto.getDatasourceId(), isActive);
			return ResponseEntity.ok(ApiResponse.success(isActive ? "数据源已启用" : "数据源已禁用", agentDatasource));
		}
		catch (Exception e) {
			return ResponseEntity.badRequest().body(ApiResponse.error("操作失败：" + e.getMessage()));
		}
	}

}
