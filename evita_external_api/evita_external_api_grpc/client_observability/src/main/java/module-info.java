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

import io.evitadb.api.observability.trace.TracingContext;

/**
 * Module contains gRPC Java driver (gRPC client) observability extension realized via OpenTelemetry.
 */
module evita.java.driver.observability {
	uses io.evitadb.driver.trace.ClientTracingContext;
	uses TracingContext;

	provides io.evitadb.driver.trace.ClientTracingContext with io.evitadb.driver.observability.trace.DriverTracingContext;

	requires static jsr305;
	requires static lombok;

	requires io.grpc;

	requires evita.api;
	requires evita.java.driver;

	requires io.opentelemetry.context;
	requires io.opentelemetry.api;
	requires evita.common;

	exports io.evitadb.driver.observability.trace;
}
