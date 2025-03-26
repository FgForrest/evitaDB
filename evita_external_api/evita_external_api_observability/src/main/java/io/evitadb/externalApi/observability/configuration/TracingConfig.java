/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2024-2025
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

package io.evitadb.externalApi.observability.configuration;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Tracing endpoint configuration.
 *
 * @param serviceName name of the service. Default is `evitaDB`.
 * @param endpoint tracing endpoint, should look like this:
 *                 gRPC: `http://localhost:4317`
 *                 HTTP: `http://localhost:4318/v1/traces`
 * @param protocol protocol used for tracing - options are: HTTP,GRPC. Default is GRPC.
 * @author Tomáš Pozler, FG Forrest a.s. (c) 2024
 */
public record TracingConfig(
	@Nonnull String serviceName,
	@Nullable String endpoint,
	@Nullable String protocol
) {
	public static final String DEFAULT_SERVICE_NAME = "evitaDB";
	public static final String SPAN_HTTP_PROTOCOL = "HTTP";
	public static final String SPAN_GRPC_PROTOCOL = "GRPC";
	public static final String DEFAULT_PROTOCOL = SPAN_GRPC_PROTOCOL;

	public TracingConfig() {
		this(DEFAULT_SERVICE_NAME, null, DEFAULT_PROTOCOL);
	}

	@JsonCreator
	public TracingConfig(
		@Nullable @JsonProperty("serviceName") String serviceName,
		@Nullable @JsonProperty("endpoint") String endpoint,
		@Nullable @JsonProperty("protocol") String protocol
	) {
		this.serviceName = serviceName == null ? DEFAULT_SERVICE_NAME : serviceName;
		this.endpoint = endpoint;
		this.protocol = protocol == null ? DEFAULT_PROTOCOL : protocol;
	}
}
