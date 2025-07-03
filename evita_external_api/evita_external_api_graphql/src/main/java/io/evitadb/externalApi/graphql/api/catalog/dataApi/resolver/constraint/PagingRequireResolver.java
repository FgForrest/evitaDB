/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2023-2025
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

package io.evitadb.externalApi.graphql.api.catalog.dataApi.resolver.constraint;

import graphql.schema.SelectedField;
import io.evitadb.api.query.RequireConstraint;
import io.evitadb.api.query.require.Spacing;
import io.evitadb.api.requestResponse.schema.EntitySchemaContract;
import io.evitadb.externalApi.api.catalog.dataApi.constraint.ManagedEntityTypePointer;
import io.evitadb.externalApi.api.catalog.dataApi.constraint.SegmentDataLocator;
import io.evitadb.externalApi.api.catalog.dataApi.model.ResponseDescriptor;
import io.evitadb.externalApi.graphql.api.catalog.dataApi.model.PaginatedListFieldHeaderDescriptor;
import io.evitadb.externalApi.graphql.api.catalog.dataApi.model.RecordPageFieldHeaderDescriptor;
import io.evitadb.externalApi.graphql.api.catalog.dataApi.model.RecordStripFieldHeaderDescriptor;
import io.evitadb.externalApi.graphql.exception.GraphQLInternalError;
import lombok.RequiredArgsConstructor;

import javax.annotation.Nonnull;
import java.util.Optional;

import static io.evitadb.api.query.QueryConstraints.page;
import static io.evitadb.api.query.QueryConstraints.strip;

/**
 * Custom constraint resolver which resolves additional constraints from output fields defined by client, rather
 * than using main query.
 * Resolves {@link io.evitadb.api.query.require.Page} or {@link io.evitadb.api.query.require.Strip} based on which fields client specified.
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2023
 */
@RequiredArgsConstructor
public class PagingRequireResolver {

	@Nonnull private final EntitySchemaContract entitySchema;
	@Nonnull private final RequireConstraintResolver requireConstraintResolver;

	@Nonnull
	public RequireConstraint resolve(@Nonnull SelectedField recordField) {
		if (recordField.getName().equals(ResponseDescriptor.RECORD_PAGE.name())) {
			final Integer pageNumber = (Integer) recordField.getArguments().getOrDefault(
				PaginatedListFieldHeaderDescriptor.NUMBER.name(), 1);
			final Integer pageSize = (Integer) recordField.getArguments().getOrDefault(
				PaginatedListFieldHeaderDescriptor.SIZE.name(), 20);
			final Spacing spacing = (Spacing) Optional.ofNullable(recordField.getArguments().get(RecordPageFieldHeaderDescriptor.SPACING.name()))
				.map(it -> this.requireConstraintResolver.resolve(
					new SegmentDataLocator(new ManagedEntityTypePointer(this.entitySchema.getName())),
					RecordPageFieldHeaderDescriptor.SPACING.name(),
					it
				))
				.orElse(null);
			return page(pageNumber, pageSize, spacing);
		} else if (recordField.getName().equals(ResponseDescriptor.RECORD_STRIP.name())) {
			final Integer offset = (Integer) recordField.getArguments().getOrDefault(RecordStripFieldHeaderDescriptor.OFFSET.name(), 0);
			final Integer limit = (Integer) recordField.getArguments().getOrDefault(RecordStripFieldHeaderDescriptor.LIMIT.name(), 20);
			return strip(offset, limit);
		} else {
			throw new GraphQLInternalError(
				"Expected field `" + ResponseDescriptor.RECORD_PAGE + "` or `" + ResponseDescriptor.RECORD_STRIP + "` but was `" + recordField.getName() + "`."
			);
		}
	}
}
