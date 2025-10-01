/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2025
 *
 *   Licensed under the Business Source License, Version 1.1 (the "License");
 *   you may not use this file except in compliance with the License.
 *   You may obtain a copy of the License at
 *
 *   https://github.com/FgForrest/evitaDB/blob/master/LICENSE
 *
 *   Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *   See the License for the specific language governing permissions and
 *   limitations under the License.
 */

package io.evitadb.externalApi.utils.path.routing;

import com.linecorp.armeria.server.HttpService;
import io.evitadb.externalApi.http.WebSocketHandler;
import io.evitadb.utils.Assert;
import lombok.Data;
import lombok.Getter;
import lombok.ToString;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * TODO lho docs
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2025
 */
public class PathHandlerDescriptor {

	@Nonnull
	private final Object handler;

	@Nullable
	private Boolean ofTypeHttpService;
	@Nullable
	private Boolean ofTypeWebSocketHandler;

	public PathHandlerDescriptor(@Nonnull Object handler) {
		Assert.isPremiseValid(
			handler instanceof HttpService || handler instanceof WebSocketHandler,
			() -> "Handler is not HttpService nor WebSocketHandler"
		);
		this.handler = handler;
	}

	public boolean isHttpService() {
		if (this.ofTypeHttpService == null) {
			this.ofTypeHttpService = this.handler instanceof HttpService;
		}
		return this.ofTypeHttpService;
	}

	@Nonnull
	public HttpService asHttpService() {
		Assert.isPremiseValid(
			this.handler instanceof HttpService,
			() -> "Handler is not HttpService"
		);
		return (HttpService) this.handler;
	}

	public boolean isWebSocketHandler() {
		if (this.ofTypeWebSocketHandler == null) {
			this.ofTypeWebSocketHandler = this.handler instanceof WebSocketHandler;
		}
		return this.ofTypeWebSocketHandler;
	}

	@Nonnull
	public WebSocketHandler asWebSocketHandler() {
		Assert.isPremiseValid(
			this.handler instanceof WebSocketHandler,
			() -> "Handler is not WebSocketHandler"
		);
		return (WebSocketHandler) this.handler;
	}
}
