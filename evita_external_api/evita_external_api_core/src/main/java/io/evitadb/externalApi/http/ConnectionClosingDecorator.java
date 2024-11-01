/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2024
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

package io.evitadb.externalApi.http;


import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.RpcRequest;
import com.linecorp.armeria.common.RpcResponse;
import com.linecorp.armeria.server.DecoratingHttpServiceFunction;
import com.linecorp.armeria.server.DecoratingRpcServiceFunction;
import com.linecorp.armeria.server.HttpService;
import com.linecorp.armeria.server.RpcService;
import com.linecorp.armeria.server.ServiceRequestContext;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;

import javax.annotation.Nonnull;

/**
 * This decorator closes the connection after each request.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2024
 */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public class ConnectionClosingDecorator implements DecoratingHttpServiceFunction, DecoratingRpcServiceFunction {
	public static final ConnectionClosingDecorator INSTANCE = new ConnectionClosingDecorator();

	@Nonnull
	@Override
	public HttpResponse serve(HttpService delegate, ServiceRequestContext ctx, @Nonnull HttpRequest req) throws Exception {
		ctx.initiateConnectionShutdown();
		return delegate.serve(ctx, req);
	}

	@Nonnull
	@Override
	public RpcResponse serve(RpcService delegate, ServiceRequestContext ctx, @Nonnull RpcRequest req) throws Exception {
		ctx.initiateConnectionShutdown();
		return delegate.serve(ctx, req);
	}

}
