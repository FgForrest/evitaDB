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

package io.evitadb.externalApi.grpc.metric.event;

import io.evitadb.api.observability.annotation.ExportDurationMetric;
import io.evitadb.api.observability.annotation.ExportInvocationMetric;
import io.evitadb.core.metric.event.CatalogRelatedEvent;
import io.grpc.MethodDescriptor.MethodType;
import jdk.jfr.Description;
import jdk.jfr.Label;
import jdk.jfr.Name;
import lombok.Getter;

import javax.annotation.Nonnull;

/**
 * Event that is fired when a gRPC session procedure is called.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2024
 */
@Name(AbstractGrpcApiEvent.PACKAGE_NAME + ".GrpcSessionProcedureCalled")
@Description("Event that is fired when a session gRPC procedure is called.")
@ExportInvocationMetric(label = "gRPC session procedure called total")
@ExportDurationMetric(label = "gRPC session procedure called duration")
@Label("gRPC session procedure called")
@Getter
public class SessionProcedureCalledEvent extends AbstractProcedureCalledEvent implements CatalogRelatedEvent {

	/**
	 * The name of the catalog the procedure call relates to (if any).
	 */
	@Label("Catalog")
	@Description("The name of the catalog to which this event/metric is associated.")
	private final String catalogName;

	public SessionProcedureCalledEvent(
		@Nonnull String catalogName,
		@Nonnull String serviceName,
		@Nonnull String procedureName,
		@Nonnull MethodType methodType
	) {
		super(serviceName, procedureName, methodType);
		this.catalogName = catalogName;
	}
}


