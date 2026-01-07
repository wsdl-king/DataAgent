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

import com.alibaba.cloud.ai.dataagent.service.chat.SessionEventPublisher;
import com.alibaba.cloud.ai.dataagent.vo.SessionUpdateEvent;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;

@Slf4j
@RestController
@CrossOrigin(origins = "*")
@RequestMapping("/api")
@RequiredArgsConstructor
public class SessionEventController {

	private final SessionEventPublisher sessionEventPublisher;

	/**
	 * 会话事件推送流（SSE）。
	 *
	 * <p>
	 * 用途：把“会话列表的增量变化”实时推送给前端（目前主要是会话标题 title-updated）。
	 * 前端侧通常在左侧会话列表（sidebar）里建立 EventSource 连接，收到事件后更新 UI。
	 * </p>
	 *
	 * <p>
	 * 事件来源：{@link com.alibaba.cloud.ai.dataagent.service.chat.SessionTitleService} 在后台异步生成标题，
	 * 保存数据库后调用 {@link com.alibaba.cloud.ai.dataagent.service.chat.SessionEventPublisher} 发布事件。
	 * </p>
	 *
	 * @param agentId 智能体 ID（按 agent 维度隔离推送流：不同 agent 的会话更新互不影响）
	 */
	@GetMapping(value = "/agent/{agentId}/sessions/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
	public Flux<ServerSentEvent<SessionUpdateEvent>> streamSessionUpdates(@PathVariable Integer agentId,
			HttpServletResponse response) {
		// SSE 标准响应头：不缓存、保持长连接；浏览器端用 EventSource 自动处理重连
		response.setCharacterEncoding("UTF-8");
		response.setContentType("text/event-stream");
		response.setHeader("Cache-Control", "no-cache");
		response.setHeader("Connection", "keep-alive");
		response.setHeader("Access-Control-Allow-Origin", "*");

		log.debug("Client subscribed to session update stream for agent {}", agentId);
		return sessionEventPublisher.register(agentId)
			.doFinally(
					signal -> log.debug("Session update stream finished for agent {} with signal {}", agentId, signal));
	}

}
