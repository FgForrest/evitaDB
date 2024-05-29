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

package io.evitadb.externalApi.observability.configuration;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Getter;

import javax.annotation.Nullable;

/**
 * Tracing endpoint configuration.
 *
 * @author Tomáš Pozler, FG Forrest a.s. (c) 2024
 */
public class TracingConfig {
	/**
	 * Tracing endpoint. Should look like this:
	 *
	 * gRPC: `http://localhost:4317`
	 * HTTP: `http://localhost:4318/v1/traces`
	 */

	@Getter private final String endpoint;
	/**
	 * Protocol used for tracing - options are: HTTP,GRPC. Default is GRPC.
	 */
	@Getter private final String protocol;

	public TracingConfig() {
		this.endpoint = null;
		this.protocol = null;
	}

	@JsonCreator
	public TracingConfig(@Nullable @JsonProperty("endpoint") String endpoint,
	                     @Nullable @JsonProperty("protocol") String protocol) {
		this.endpoint = endpoint;
		this.protocol = protocol;
	}
}
