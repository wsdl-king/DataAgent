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

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 健康检查 Controller
 * 
 * <p><b>职责边界</b>：
 * 提供简单的健康检查端点，用于监控系统是否正常运行。
 * 
 * <p><b>典型使用场景</b>：
 * 1. 负载均衡器（如 Nginx）定期调用此接口检查后端服务是否存活
 * 2. 监控系统（如 Prometheus）调用此接口收集健康状态
 * 3. 运维人员快速检查服务是否启动成功
 * 
 * <p><b>主要端点</b>：
 * - GET /echo/ok：返回 "ok" 字符串，表示服务正常
 * 
 * @author yingzi
 * @since 2025/9/16
 */
@RestController
@RequestMapping("/echo")
public class EchoController {

	/**
	 * 心跳检测（健康检查）
	 * 
	 * <p><b>用途</b>：快速检查服务是否正常运行。
	 * 
	 * <p><b>返回值</b>：固定返回 "ok" 字符串，HTTP 状态码 200。
	 * 
	 * <p><b>典型场景</b>：
	 * - 负载均衡器配置：health_check_url = "http://backend:8080/echo/ok"
	 * - 运维检查：curl http://localhost:8080/echo/ok
	 * - 容器编排（如 Kubernetes）的 liveness probe
	 * 
	 * @return "ok" 字符串
	 */
	@GetMapping("ok")
	public String ok() {
		return "ok";
	}

}
