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

package com.alibaba.cloud.ai.dataagent.config;

import com.alibaba.cloud.ai.dataagent.properties.CodeExecutorProperties;
import com.alibaba.cloud.ai.dataagent.properties.DataAgentProperties;
import com.alibaba.cloud.ai.dataagent.properties.FileStorageProperties;
import com.alibaba.cloud.ai.dataagent.util.McpServerToolUtil;
import com.alibaba.cloud.ai.dataagent.util.NodeBeanUtil;
import com.alibaba.cloud.ai.dataagent.service.aimodelconfig.AiModelRegistry;
import com.alibaba.cloud.ai.dataagent.strategy.EnhancedTokenCountBatchingStrategy;
import com.alibaba.cloud.ai.dataagent.workflow.dispatcher.*;
import com.alibaba.cloud.ai.dataagent.workflow.node.*;
import com.alibaba.cloud.ai.graph.GraphRepresentation;
import com.alibaba.cloud.ai.graph.KeyStrategy;
import com.alibaba.cloud.ai.graph.KeyStrategyFactory;
import com.alibaba.cloud.ai.graph.StateGraph;
import com.alibaba.cloud.ai.graph.exception.GraphStateException;
import com.knuddels.jtokkit.api.EncodingType;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.embedding.BatchingStrategy;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.resolution.DelegatingToolCallbackResolver;
import org.springframework.ai.tool.resolution.SpringBeanToolCallbackResolver;
import org.springframework.ai.tool.resolution.StaticToolCallbackResolver;
import org.springframework.ai.tool.resolution.ToolCallbackResolver;
import org.springframework.ai.transformer.splitter.TextSplitter;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.ai.vectorstore.SimpleVectorStore;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.aop.TargetSource;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.http.client.ClientHttpRequestFactoryBuilder;
import org.springframework.boot.web.client.RestClientCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.context.support.GenericApplicationContext;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.netty.http.client.HttpClient;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static com.alibaba.cloud.ai.dataagent.constant.Constant.*;
import static com.alibaba.cloud.ai.graph.StateGraph.END;
import static com.alibaba.cloud.ai.graph.StateGraph.START;
import static com.alibaba.cloud.ai.graph.action.AsyncEdgeAction.edge_async;

/**
 * DataAgent的自动配置类
 *
 * @author vlsmb
 * @since 2025/9/28
 */
@Slf4j
@Configuration
@EnableAsync
@EnableConfigurationProperties({ CodeExecutorProperties.class, DataAgentProperties.class, FileStorageProperties.class })
public class DataAgentConfiguration implements DisposableBean {

	/**
	 * 专用线程池，用于数据库操作的并行处理
	 */
	private ExecutorService dbOperationExecutor;

	@Bean
	@ConditionalOnMissingBean(RestClientCustomizer.class)
	public RestClientCustomizer restClientCustomizer(@Value("${rest.connect.timeout:600}") long connectTimeout,
			@Value("${rest.read.timeout:600}") long readTimeout) {
		return restClientBuilder -> restClientBuilder
			.requestFactory(ClientHttpRequestFactoryBuilder.reactor().withCustomizer(factory -> {
				factory.setConnectTimeout(Duration.ofSeconds(connectTimeout));
				factory.setReadTimeout(Duration.ofSeconds(readTimeout));
			}).build());
	}

	@Bean
	@ConditionalOnMissingBean(WebClient.Builder.class)
	public WebClient.Builder webClientBuilder(@Value("${webclient.response.timeout:600}") long responseTimeout) {

		return WebClient.builder()
			.clientConnector(new ReactorClientHttpConnector(
					HttpClient.create().responseTimeout(Duration.ofSeconds(responseTimeout))));
	}

	@Bean
	public StateGraph nl2sqlGraph(NodeBeanUtil nodeBeanUtil, CodeExecutorProperties codeExecutorProperties)
			throws GraphStateException {

		// ========================================================================
		// KeyStrategyFactory: 状态更新策略工厂
		// ========================================================================
		// 作用：定义 OverAllState 中每个 key 的状态更新策略
		//
		// 为什么需要这个配置？
		// 1. 控制状态更新行为：当节点返回 Map 更新 state 时，决定如何合并新值
		// 2. 避免数据污染：防止节点意外覆盖重要的状态数据
		// 3. 明确数据语义：明确每个 key 的数据特性（是否可替换、是否可累加等）
		//
		// KeyStrategy.REPLACE 的含义：
		// - 当节点返回包含该 key 的 Map 时，新值会完全替换旧值
		// - 例如：SQL_GENERATE_OUTPUT = "SELECT * FROM users"
		//   如果后续节点再次返回 SQL_GENERATE_OUTPUT = "SELECT * FROM orders"
		//   则 state[SQL_GENERATE_OUTPUT] 会被替换为新的 SQL，旧值丢失
		//
		// 为什么所有 key 都使用 REPLACE？
		// - 在这个项目中，每个节点的输出都是独立的，不应该累加
		// - 例如：如果 SQL 生成失败重试，应该用新的 SQL 替换旧的，而不是追加
		// - 如果需要保留历史，会使用其他 key（如 SQL_EXECUTE_NODE_OUTPUT 按步骤存储）
		// ========================================================================
		KeyStrategyFactory keyStrategyFactory = () -> {
			HashMap<String, KeyStrategy> keyStrategyHashMap = new HashMap<>();

			// ==================== 用户输入与控制参数 ====================
			// 用户输入的原始查询
			keyStrategyHashMap.put(INPUT_KEY, KeyStrategy.REPLACE);
			// Agent ID：标识当前使用的智能体
			keyStrategyHashMap.put(AGENT_ID, KeyStrategy.REPLACE);
			// 多轮对话上下文：用于多轮对话场景，包含历史对话信息
			keyStrategyHashMap.put(MULTI_TURN_CONTEXT, KeyStrategy.REPLACE);

			// ==================== 第一阶段：意图识别与查询增强 ====================
			// 意图识别节点输出：判断是否需要数据分析
			keyStrategyHashMap.put(INTENT_RECOGNITION_NODE_OUTPUT, KeyStrategy.REPLACE);
			// 查询增强节点输出：增强后的用户查询
			keyStrategyHashMap.put(QUERY_ENHANCE_NODE_OUTPUT, KeyStrategy.REPLACE);
			// 语义模型提示词：用于SQL生成的语义上下文
			keyStrategyHashMap.put(GENEGRATED_SEMANTIC_MODEL_PROMPT, KeyStrategy.REPLACE);

			// ==================== 第二阶段：证据召回与Schema检索 ====================
			// 证据召回节点输出：从向量数据库检索到的业务知识和术语
			keyStrategyHashMap.put(EVIDENCE, KeyStrategy.REPLACE);
			// Schema 召回节点输出：召回的表文档
			keyStrategyHashMap.put(TABLE_DOCUMENTS_FOR_SCHEMA_OUTPUT, KeyStrategy.REPLACE);
			// Schema 召回节点输出：召回的列文档
			keyStrategyHashMap.put(COLUMN_DOCUMENTS__FOR_SCHEMA_OUTPUT, KeyStrategy.REPLACE);

			// ==================== 第三阶段：表关系构建 ====================
			// 表关系节点输出：构建好的表关系 Schema（包含主外键关系）
			keyStrategyHashMap.put(TABLE_RELATION_OUTPUT, KeyStrategy.REPLACE);
			// 表关系节点异常输出：构建失败时的异常信息
			keyStrategyHashMap.put(TABLE_RELATION_EXCEPTION_OUTPUT, KeyStrategy.REPLACE);
			// 表关系重试计数：记录重试次数，用于防止无限重试
			keyStrategyHashMap.put(TABLE_RELATION_RETRY_COUNT, KeyStrategy.REPLACE);
			// 数据库方言类型：MySQL、PostgreSQL 等
			keyStrategyHashMap.put(DB_DIALECT_TYPE, KeyStrategy.REPLACE);

			// ==================== 第四阶段：可行性评估 ====================
			// 可行性评估节点输出：评估查询是否可以在当前数据库上执行
			keyStrategyHashMap.put(FEASIBILITY_ASSESSMENT_NODE_OUTPUT, KeyStrategy.REPLACE);

			// ==================== 第五阶段：计划生成与执行 ====================
			// 计划生成节点输出：包含所有执行步骤的 JSON 格式计划
			keyStrategyHashMap.put(PLANNER_NODE_OUTPUT, KeyStrategy.REPLACE);
			// 计划执行相关：当前执行的步骤编号（从1开始）
			keyStrategyHashMap.put(PLAN_CURRENT_STEP, KeyStrategy.REPLACE);
			// 计划执行相关：下一个要执行的节点名称
			keyStrategyHashMap.put(PLAN_NEXT_NODE, KeyStrategy.REPLACE);
			// 计划执行相关：计划验证状态（true/false）
			keyStrategyHashMap.put(PLAN_VALIDATION_STATUS, KeyStrategy.REPLACE);
			// 计划执行相关：计划验证错误信息（用于重新生成计划）
			keyStrategyHashMap.put(PLAN_VALIDATION_ERROR, KeyStrategy.REPLACE);
			// 计划执行相关：计划修复计数（最多重试3次）
			keyStrategyHashMap.put(PLAN_REPAIR_COUNT, KeyStrategy.REPLACE);

			// ==================== 第六阶段：SQL 生成与执行 ====================
			// SQL 生成相关：Schema 缺失建议（当需要的表不存在时的提示）
			keyStrategyHashMap.put(SQL_GENERATE_SCHEMA_MISSING_ADVICE, KeyStrategy.REPLACE);
			// SQL 生成节点输出：生成的 SQL 语句
			keyStrategyHashMap.put(SQL_GENERATE_OUTPUT, KeyStrategy.REPLACE);
			// SQL 生成相关：SQL 生成重试计数
			keyStrategyHashMap.put(SQL_GENERATE_COUNT, KeyStrategy.REPLACE);
			// SQL 生成相关：SQL 重新生成的原因（语义错误、执行失败等）
			keyStrategyHashMap.put(SQL_REGENERATE_REASON, KeyStrategy.REPLACE);
			// 语义一致性节点输出：语义一致性检查结果（true/false）
			keyStrategyHashMap.put(SEMANTIC_CONSISTENCY_NODE_OUTPUT, KeyStrategy.REPLACE);
			// SQL 执行节点输出：SQL 执行结果（按步骤存储，格式：{"step_1": "结果1", "step_2": "结果2"}）
			keyStrategyHashMap.put(SQL_EXECUTE_NODE_OUTPUT, KeyStrategy.REPLACE);

			// ==================== 第七阶段：Python 分析与报告 ====================
			// SQL 结果内存：存储 SQL 查询结果的原始数据列表（供 Python 节点使用）
			keyStrategyHashMap.put(SQL_RESULT_LIST_MEMORY, KeyStrategy.REPLACE);
			// Python 执行相关：Python 代码执行是否成功
			keyStrategyHashMap.put(PYTHON_IS_SUCCESS, KeyStrategy.REPLACE);
			// Python 执行相关：Python 执行重试计数
			keyStrategyHashMap.put(PYTHON_TRIES_COUNT, KeyStrategy.REPLACE);
			// Python 执行相关：是否进入降级模式（超过最大重试次数后）
			keyStrategyHashMap.put(PYTHON_FALLBACK_MODE, KeyStrategy.REPLACE);
			// Python 执行节点输出：Python 代码执行结果
			keyStrategyHashMap.put(PYTHON_EXECUTE_NODE_OUTPUT, KeyStrategy.REPLACE);
			// Python 生成节点输出：生成的 Python 代码
			keyStrategyHashMap.put(PYTHON_GENERATE_NODE_OUTPUT, KeyStrategy.REPLACE);
			// Python 分析节点输出：Python 结果分析输出
			keyStrategyHashMap.put(PYTHON_ANALYSIS_NODE_OUTPUT, KeyStrategy.REPLACE);

			// ==================== 模式控制 ====================
			// 是否仅为 NL2SQL 模式（不执行完整计划，只生成 SQL）
			keyStrategyHashMap.put(IS_ONLY_NL2SQL, KeyStrategy.REPLACE);

			// ==================== 人工反馈机制 ====================
			// 是否启用人工审核：true 表示在计划生成后等待人工审核
			keyStrategyHashMap.put(HUMAN_REVIEW_ENABLED, KeyStrategy.REPLACE);
			// 人工反馈数据：包含用户是否批准计划、反馈意见等
			keyStrategyHashMap.put(HUMAN_FEEDBACK_DATA, KeyStrategy.REPLACE);

			// ==================== 最终结果 ====================
			// 最终结果：报告生成节点的输出（HTML/Markdown 格式的报告）
			keyStrategyHashMap.put(RESULT, KeyStrategy.REPLACE);

			return keyStrategyHashMap;
		};

		// ========================================================================
		// 创建 StateGraph 状态图，定义整个工作流的节点和边的连接关系
		// ========================================================================
		StateGraph stateGraph = new StateGraph(NL2SQL_GRAPH_NAME, keyStrategyFactory)
			// ==================== 第一阶段：意图识别与查询增强 ====================
			// 意图识别节点：判断用户查询是否需要数据分析，还是普通对话
			.addNode(INTENT_RECOGNITION_NODE, nodeBeanUtil.getNodeBeanAsync(IntentRecognitionNode.class))
			// 证据召回节点：从向量数据库中检索相关的业务知识和术语
			.addNode(EVIDENCE_RECALL_NODE, nodeBeanUtil.getNodeBeanAsync(EvidenceRecallNode.class))
			// 查询增强节点：基于检索到的证据，增强用户查询的准确性
			.addNode(QUERY_ENHANCE_NODE, nodeBeanUtil.getNodeBeanAsync(QueryEnhanceNode.class))
			
			// ==================== 第二阶段：Schema 召回与关系构建 ====================
			// Schema 召回节点：从业务数据库中召回相关的表结构信息
			.addNode(SCHEMA_RECALL_NODE, nodeBeanUtil.getNodeBeanAsync(SchemaRecallNode.class))
			// 表关系节点：分析并构建表之间的关联关系（主外键关系）
			.addNode(TABLE_RELATION_NODE, nodeBeanUtil.getNodeBeanAsync(TableRelationNode.class))
			// 可行性评估节点：评估用户查询是否可以在当前数据库上执行
			.addNode(FEASIBILITY_ASSESSMENT_NODE, nodeBeanUtil.getNodeBeanAsync(FeasibilityAssessmentNode.class))
			
			// ==================== 第三阶段：计划生成与执行 ====================
			// 计划生成节点：生成多步骤的执行计划（可能包含多个 SQL、Python、报告步骤）
			.addNode(PLANNER_NODE, nodeBeanUtil.getNodeBeanAsync(PlannerNode.class))
			// 计划执行节点：负责验证计划并决定下一个要执行的步骤类型
			.addNode(PLAN_EXECUTOR_NODE, nodeBeanUtil.getNodeBeanAsync(PlanExecutorNode.class))
			
			// ==================== 第四阶段：SQL 生成与执行 ====================
			// SQL 生成节点：根据用户查询、Schema、证据等信息生成 SQL 语句
			.addNode(SQL_GENERATE_NODE, nodeBeanUtil.getNodeBeanAsync(SqlGenerateNode.class))
			// 语义一致性节点：校验生成的 SQL 是否与用户意图一致，是否存在幻觉字段等
			// 作用：1. 检查 SQL 是否查询了正确的表和字段
			//      2. 验证 WHERE 条件是否正确
			//      3. 检查聚合函数是否符合要求
			//      4. 检测是否存在 Schema 中不存在的幻觉字段
			//      5. 验证 SQL 语法是否符合数据库方言
			.addNode(SEMANTIC_CONSISTENCY_NODE, nodeBeanUtil.getNodeBeanAsync(SemanticConsistencyNode.class))
			// SQL 执行节点：执行 SQL 查询并返回结果
			.addNode(SQL_EXECUTE_NODE, nodeBeanUtil.getNodeBeanAsync(SqlExecuteNode.class))
			
			// ==================== 第五阶段：Python 分析与报告 ====================
			// Python 代码生成节点：基于 SQL 查询结果生成 Python 分析代码
			.addNode(PYTHON_GENERATE_NODE, nodeBeanUtil.getNodeBeanAsync(PythonGenerateNode.class))
			// Python 代码执行节点：在 Docker 或本地环境中执行 Python 代码
			.addNode(PYTHON_EXECUTE_NODE, nodeBeanUtil.getNodeBeanAsync(PythonExecuteNode.class))
			// Python 结果分析节点：分析 Python 执行结果，提取关键信息
			.addNode(PYTHON_ANALYZE_NODE, nodeBeanUtil.getNodeBeanAsync(PythonAnalyzeNode.class))
			// 报告生成节点：将所有步骤的执行结果汇总为 HTML/Markdown 报告
			.addNode(REPORT_GENERATOR_NODE, nodeBeanUtil.getNodeBeanAsync(ReportGeneratorNode.class))
			
			// ==================== 人工反馈机制 ====================
			// 人工反馈节点：支持用户在计划生成后进行人工审核和干预
			.addNode(HUMAN_FEEDBACK_NODE, nodeBeanUtil.getNodeBeanAsync(HumanFeedbackNode.class));

		// ========================================================================
		// 定义节点之间的边（连接关系）和执行流程
		// ========================================================================
		
		// 工作流入口：从 START 开始，首先进入意图识别节点
		stateGraph.addEdge(START, INTENT_RECOGNITION_NODE)
			// 意图识别节点后的路由：
			// - 如果需要数据分析 → EVIDENCE_RECALL_NODE（证据召回）
			// - 如果不需要 → END（结束）
			.addConditionalEdges(INTENT_RECOGNITION_NODE, edge_async(new IntentRecognitionDispatcher()),
					Map.of(EVIDENCE_RECALL_NODE, EVIDENCE_RECALL_NODE, END, END))
			
			// 证据召回 → 查询增强（顺序执行）
			.addEdge(EVIDENCE_RECALL_NODE, QUERY_ENHANCE_NODE)
			// 查询增强后的路由：
			// - 增强成功 → SCHEMA_RECALL_NODE（Schema 召回）
			// - 增强失败 → END
			.addConditionalEdges(QUERY_ENHANCE_NODE, edge_async(new QueryEnhanceDispatcher()),
					Map.of(SCHEMA_RECALL_NODE, SCHEMA_RECALL_NODE, END, END))
			// Schema 召回后的路由：
			// - 召回成功 → TABLE_RELATION_NODE（表关系构建）
			// - 召回失败 → END
			.addConditionalEdges(SCHEMA_RECALL_NODE, edge_async(new SchemaRecallDispatcher()),
					Map.of(TABLE_RELATION_NODE, TABLE_RELATION_NODE, END, END))

			// 表关系节点后的路由（支持重试）：
			// - 关系构建成功 → FEASIBILITY_ASSESSMENT_NODE（可行性评估）
			// - 关系构建失败 → 重试 TABLE_RELATION_NODE 或 END
			.addConditionalEdges(TABLE_RELATION_NODE, edge_async(new TableRelationDispatcher()),
					Map.of(FEASIBILITY_ASSESSMENT_NODE, FEASIBILITY_ASSESSMENT_NODE, END, END, TABLE_RELATION_NODE,
							TABLE_RELATION_NODE)) // retry: 如果关系构建失败，可以重试
			// 可行性评估后的路由：
			// - 评估通过 → PLANNER_NODE（生成执行计划）
			// - 评估不通过 → END
			.addConditionalEdges(FEASIBILITY_ASSESSMENT_NODE, edge_async(new FeasibilityAssessmentDispatcher()),
					Map.of(PLANNER_NODE, PLANNER_NODE, END, END))

			// 计划生成完成后，进入计划执行节点进行验证和路由
			.addEdge(PLANNER_NODE, PLAN_EXECUTOR_NODE)
			
			// ==================== Python 执行链路 ====================
			// Python 生成 → Python 执行（顺序执行）
			.addEdge(PYTHON_GENERATE_NODE, PYTHON_EXECUTE_NODE)
			// Python 执行后的路由：
			// - 执行成功 → PYTHON_ANALYZE_NODE（分析结果）
			// - 执行失败 → 重试 PYTHON_GENERATE_NODE 或 END
			.addConditionalEdges(PYTHON_EXECUTE_NODE, edge_async(new PythonExecutorDispatcher(codeExecutorProperties)),
					Map.of(PYTHON_ANALYZE_NODE, PYTHON_ANALYZE_NODE, END, END, PYTHON_GENERATE_NODE,
							PYTHON_GENERATE_NODE))
			// Python 分析完成后，回到计划执行节点，继续执行下一个步骤
			.addEdge(PYTHON_ANALYZE_NODE, PLAN_EXECUTOR_NODE)
			
			// ==================== 计划执行节点的路由决策 ====================
			// 计划执行节点根据当前步骤类型和状态，决定下一个执行的节点：
			.addConditionalEdges(PLAN_EXECUTOR_NODE, edge_async(new PlanExecutorDispatcher()), Map.of(
					// 计划验证失败 → 回到 PLANNER_NODE 重新生成计划
					PLANNER_NODE, PLANNER_NODE,
					// 计划验证通过，根据当前步骤类型路由到对应的执行节点
					SQL_GENERATE_NODE, SQL_GENERATE_NODE,      // SQL 步骤
					PYTHON_GENERATE_NODE, PYTHON_GENERATE_NODE, // Python 步骤
					REPORT_GENERATOR_NODE, REPORT_GENERATOR_NODE, // 报告步骤
					// 如果开启了人工审核，先进入人工反馈节点
					HUMAN_FEEDBACK_NODE, HUMAN_FEEDBACK_NODE,
					// 所有步骤执行完成或达到最大重试次数 → END
					END, END))
			
			// ==================== 人工反馈节点的路由 ====================
			// 人工反馈后的路由：
			.addConditionalEdges(HUMAN_FEEDBACK_NODE, edge_async(new HumanFeedbackDispatcher()), Map.of(
					// 用户拒绝计划 → 回到 PLANNER_NODE 重新生成
					PLANNER_NODE, PLANNER_NODE,
					// 用户批准计划 → 继续执行 PLAN_EXECUTOR_NODE
					PLAN_EXECUTOR_NODE, PLAN_EXECUTOR_NODE,
					// 达到最大修复次数 → END
					END, END))
			
			// 报告生成完成后，工作流结束
			.addEdge(REPORT_GENERATOR_NODE, END)
			
			// ==================== SQL 执行链路（含语义一致性检查） ====================
			// SQL 生成节点后的路由：
			.addConditionalEdges(SQL_GENERATE_NODE, nodeBeanUtil.getEdgeBeanAsync(SqlGenerateDispatcher.class),
					Map.of(
							SQL_GENERATE_NODE, SQL_GENERATE_NODE, // SQL 生成失败，重试
							END, END,                              // 达到最大重试次数，结束
							SEMANTIC_CONSISTENCY_NODE, SEMANTIC_CONSISTENCY_NODE)) // SQL 生成成功，进入语义一致性检查
			
			// 语义一致性检查节点后的路由：
			// 作用：验证生成的 SQL 是否与用户意图一致，检查是否存在幻觉字段、逻辑错误等
			.addConditionalEdges(SEMANTIC_CONSISTENCY_NODE, edge_async(new SemanticConsistenceDispatcher()),
					Map.of(
							// 语义检查不通过 → 回到 SQL_GENERATE_NODE 重新生成
							SQL_GENERATE_NODE, SQL_GENERATE_NODE,
							// 语义检查通过 → 进入 SQL_EXECUTE_NODE 执行 SQL
							SQL_EXECUTE_NODE, SQL_EXECUTE_NODE))
			
			// SQL 执行节点后的路由：
			.addConditionalEdges(SQL_EXECUTE_NODE, edge_async(new SQLExecutorDispatcher()),
					Map.of(
							// SQL 执行失败 → 回到 SQL_GENERATE_NODE 重新生成
							SQL_GENERATE_NODE, SQL_GENERATE_NODE,
							// SQL 执行成功 → 回到 PLAN_EXECUTOR_NODE 继续执行下一个步骤
							PLAN_EXECUTOR_NODE, PLAN_EXECUTOR_NODE));

		GraphRepresentation graphRepresentation = stateGraph.getGraph(GraphRepresentation.Type.PLANTUML,
				"workflow graph");

		log.info("workflow in PlantUML format as follows \n\n" + graphRepresentation.content() + "\n\n");

		return stateGraph;
	}

	/**
	 * 为了不必要的重复手动配置，不要在此添加其他向量的手动配置，如果扩展其他向量，请阅读spring ai文档
	 * <a href="https://springdoc.cn/spring-ai/api/vectordbs.html">...</a>
	 * 根据自己想要的向量，在pom文件引入 Boot Starter 依赖即可。此处配置使用内存向量作为兜底配置
	 */
	@Primary
	@Bean
	@ConditionalOnMissingBean(VectorStore.class)
	@ConditionalOnProperty(name = "spring.ai.vectorstore.type", havingValue = "simple", matchIfMissing = true)
	public VectorStore simpleVectorStore(EmbeddingModel embeddingModel) {
		return SimpleVectorStore.builder(embeddingModel).build();
	}

	@Bean
	@ConditionalOnMissingBean(BatchingStrategy.class)
	public BatchingStrategy customBatchingStrategy(DataAgentProperties properties) {
		// 使用增强的批处理策略，同时考虑token数量和文本数量限制
		EncodingType encodingType;
		try {
			Optional<EncodingType> encodingTypeOptional = EncodingType
				.fromName(properties.getEmbeddingBatch().getEncodingType());
			encodingType = encodingTypeOptional.orElse(EncodingType.CL100K_BASE);
		}
		catch (Exception e) {
			log.warn("Unknown encodingType '{}', falling back to CL100K_BASE",
					properties.getEmbeddingBatch().getEncodingType());
			encodingType = EncodingType.CL100K_BASE;
		}

		return new EnhancedTokenCountBatchingStrategy(encodingType, properties.getEmbeddingBatch().getMaxTokenCount(),
				properties.getEmbeddingBatch().getReservePercentage(),
				properties.getEmbeddingBatch().getMaxTextCount());
	}

	@Bean
	public ToolCallbackResolver toolCallbackResolver(GenericApplicationContext context) {
		List<ToolCallback> allFunctionAndToolCallbacks = new ArrayList<>(
				McpServerToolUtil.excludeMcpServerTool(context, ToolCallback.class));
		McpServerToolUtil.excludeMcpServerTool(context, ToolCallbackProvider.class)
			.stream()
			.map(pr -> List.of(pr.getToolCallbacks()))
			.forEach(allFunctionAndToolCallbacks::addAll);

		var staticToolCallbackResolver = new StaticToolCallbackResolver(allFunctionAndToolCallbacks);

		var springBeanToolCallbackResolver = SpringBeanToolCallbackResolver.builder()
			.applicationContext(context)
			.build();

		return new DelegatingToolCallbackResolver(List.of(staticToolCallbackResolver, springBeanToolCallbackResolver));
	}

	/**
	 * 动态生成 EmbeddingModel 的代理 Bean。 原理： 1. 这是一个 Bean，Milvus/PgVector Starter 能看到它，启动不会报错。
	 * 2. 它是动态代理，内部没有写死任何方法。 3. 每次被调用时，它会执行 getTarget() -> registry.getEmbeddingModel()。
	 */
	@Bean
	@Primary
	public EmbeddingModel embeddingModel(AiModelRegistry registry) {

		// 1. 定义目标源 (TargetSource)
		TargetSource targetSource = new TargetSource() {
			@Override
			public Class<?> getTargetClass() {
				return EmbeddingModel.class;
			}

			@Override
			public boolean isStatic() {
				// 关键：声明是动态的，每次都要重新获取目标
				return false;
			}

			@Override
			public Object getTarget() {
				// 每次方法调用，都去注册表拿最新的
				return registry.getEmbeddingModel();
			}

			@Override
			public void releaseTarget(Object target) {
				// 无需释放
			}
		};

		// 2. 创建代理工厂
		ProxyFactory proxyFactory = new ProxyFactory();
		proxyFactory.setTargetSource(targetSource);
		// 代理接口
		proxyFactory.addInterface(EmbeddingModel.class);

		// 3. 返回动态生成的代理对象
		return (EmbeddingModel) proxyFactory.getProxy();
	}

	@Bean(name = "dbOperationExecutor")
	public ExecutorService dbOperationExecutor() {
		// 初始化专用线程池，用于数据库操作
		// 线程数量设置为CPU核心数的2倍，但不少于4个，不超过16个
		int corePoolSize = Math.max(4, Math.min(Runtime.getRuntime().availableProcessors() * 2, 16));
		log.info("Database operation executor initialized with {} threads", corePoolSize);

		// 自定义线程工厂
		ThreadFactory threadFactory = new ThreadFactory() {
			private final AtomicInteger threadNumber = new AtomicInteger(1);

			@Override
			public Thread newThread(Runnable r) {
				Thread t = new Thread(r, "db-operation-" + threadNumber.getAndIncrement());
				t.setDaemon(false);
				if (t.getPriority() != Thread.NORM_PRIORITY) {
					t.setPriority(Thread.NORM_PRIORITY);
				}
				return t;
			}
		};

		// 创建原生线程池
		this.dbOperationExecutor = new ThreadPoolExecutor(corePoolSize, corePoolSize, 60L, TimeUnit.SECONDS,
				new LinkedBlockingQueue<>(500), threadFactory, new ThreadPoolExecutor.CallerRunsPolicy());

		return dbOperationExecutor;
	}

	@Override
	public void destroy() {
		if (dbOperationExecutor != null && !dbOperationExecutor.isShutdown()) {
			log.info("Shutting down database operation executor...");

			// 记录关闭前的状态，便于排查问题
			if (dbOperationExecutor instanceof ThreadPoolExecutor tpe) {
				log.info("Executor Status before shutdown: [Queue Size: {}], [Active Count: {}], [Completed Tasks: {}]",
						tpe.getQueue().size(), tpe.getActiveCount(), tpe.getCompletedTaskCount());
			}

			// 1. 停止接收新任务
			dbOperationExecutor.shutdown();

			try {
				// 2. 等待现有任务完成（包括队列中的）
				if (!dbOperationExecutor.awaitTermination(60, TimeUnit.SECONDS)) {
					log.warn("Executor did not terminate in 60s. Forcing shutdown...");

					// 3. 超时强行关闭
					dbOperationExecutor.shutdownNow();

					// 4. 再次确认是否关闭
					if (!dbOperationExecutor.awaitTermination(10, TimeUnit.SECONDS)) {
						log.error("Executor failed to terminate completely.");
					}
				}
				else {
					log.info("Database operation executor terminated gracefully.");
				}
			}
			catch (InterruptedException e) {
				log.warn("Interrupted during executor shutdown. Forcing immediate shutdown.");
				dbOperationExecutor.shutdownNow();
				Thread.currentThread().interrupt();
			}
		}
	}

	@Bean
	public TextSplitter textSplitter(DataAgentProperties properties) {
		DataAgentProperties.TextSplitter textSplitterProps = properties.getTextSplitter();
		return new TokenTextSplitter(textSplitterProps.getChunkSize(), textSplitterProps.getMinChunkSizeChars(),
				textSplitterProps.getMinChunkLengthToEmbed(), textSplitterProps.getMaxNumChunks(),
				textSplitterProps.isKeepSeparator());
	}

}
