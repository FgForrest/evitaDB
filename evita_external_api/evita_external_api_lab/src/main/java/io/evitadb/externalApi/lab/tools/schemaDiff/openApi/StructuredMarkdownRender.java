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

package io.evitadb.externalApi.lab.tools.schemaDiff.openApi;

import io.evitadb.externalApi.lab.tools.schemaDiff.openApi.SchemaDiff.ActionType;
import io.evitadb.externalApi.lab.tools.schemaDiff.openApi.SchemaDiff.Change;
import org.openapitools.openapidiff.core.model.ChangedOpenApi;
import org.openapitools.openapidiff.core.model.ChangedOperation;
import org.openapitools.openapidiff.core.model.Endpoint;
import org.openapitools.openapidiff.core.output.MarkdownRender;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.LinkedList;
import java.util.List;

import static org.openapitools.openapidiff.core.model.Changed.result;

/**
 * Extension of {@link MarkdownRender} that renders individual endpoint changes into a list of categorized MarkDowns.
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2024
 */
public class StructuredMarkdownRender extends MarkdownRender {

	@Nonnull
	public List<Change> renderStructured(@Nonnull ChangedOpenApi diff) {
		this.diff = diff;
		this.handledSchemas.clear();

		final List<Change> descriptors = new LinkedList<>();
		descriptors.addAll(listEndpointsStructured(ActionType.ADDITION, false, diff.getNewEndpoints()));
		descriptors.addAll(listEndpointsStructured(ActionType.REMOVAL, true, diff.getMissingEndpoints()));
		descriptors.addAll(listEndpointsStructured(diff.getChangedOperations()));
		return descriptors;
	}

	@Nonnull
	private List<Change> listEndpointsStructured(@Nonnull ActionType actionType,
	                                               boolean breaking,
	                                               @Nullable List<Endpoint> endpoints) {
		if (null == endpoints || endpoints.isEmpty()) {
			return List.of();
		}
		return endpoints.stream()
			.map(endpoint -> new Change(
				actionType,
				breaking,
				endpoint.getMethod().toString(),
				endpoint.getPathUrl(),
				metadata(endpoint.getSummary())
			))
			.toList();
	}

	@Nonnull
	private List<Change> listEndpointsStructured(@Nullable List<ChangedOperation> changedOperations) {
		if (null == changedOperations || changedOperations.isEmpty()) {
			return List.of();
		}

		return changedOperations.stream()
			.map(operation -> {
				final StringBuilder detail = new StringBuilder(128);
				detail.append(metadata("summary", operation.getSummary()) + "\n");
				if (result(operation.getParameters()).isDifferent()) {
					detail
						.append(titleH5("Parameters:"))
						.append(parameters(operation.getParameters()));
				}
				if (operation.resultRequestBody().isDifferent()) {
					detail
						.append(titleH5("Request:"))
						.append(metadata("Description", operation.getRequestBody().getDescription()))
						.append(bodyContent(operation.getRequestBody().getContent()));
				}
				if (operation.resultApiResponses().isDifferent()) {
					detail
						.append(titleH5("Return Type:"))
						.append(responses(operation.getApiResponses()));
				}

				return new Change(
					ActionType.MODIFICATION,
					operation.isIncompatible(),
					operation.getHttpMethod().toString(),
					operation.getPathUrl(),
					detail.toString()
				);
			})
			.toList();
	}
}
