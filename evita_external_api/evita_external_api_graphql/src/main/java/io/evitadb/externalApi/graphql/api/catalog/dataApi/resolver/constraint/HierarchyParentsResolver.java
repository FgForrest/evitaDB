/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2023
 *
 *   Licensed under the Business Source License, Version 1.1 (the "License");
 *   you may not use this file except in compliance with the License.
 *   You may obtain a copy of the License at
 *
 *   https://github.com/FgForrest/evitaDB/blob/main/LICENSE
 *
 *   Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *   See the License for the specific language governing permissions and
 *   limitations under the License.
 */

package io.evitadb.externalApi.graphql.api.catalog.dataApi.resolver.constraint;

import graphql.schema.DataFetchingFieldSelectionSet;
import graphql.schema.SelectedField;
import io.evitadb.api.query.RequireConstraint;
import io.evitadb.api.query.require.EntityFetch;
import io.evitadb.api.requestResponse.schema.EntitySchemaContract;
import io.evitadb.api.requestResponse.schema.ReferenceSchemaContract;
import io.evitadb.externalApi.api.catalog.dataApi.model.extraResult.ExtraResultsDescriptor;
import io.evitadb.externalApi.api.catalog.dataApi.model.extraResult.HierarchyDescriptor;
import io.evitadb.externalApi.api.catalog.dataApi.model.extraResult.HierarchyParentsDescriptor;
import io.evitadb.externalApi.api.catalog.dataApi.model.extraResult.HierarchyParentsDescriptor.ParentsOfEntityDescriptor;
import io.evitadb.externalApi.api.catalog.dataApi.model.extraResult.HierarchyParentsDescriptor.ParentsOfEntityDescriptor.ParentsOfReferenceDescriptor;
import io.evitadb.externalApi.graphql.api.resolver.SelectionSetWrapper;
import io.evitadb.externalApi.graphql.exception.GraphQLQueryResolvingInternalError;
import lombok.RequiredArgsConstructor;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

import static io.evitadb.api.query.QueryConstraints.hierarchyParentsOfReference;
import static io.evitadb.api.query.QueryConstraints.hierarchyParentsOfSelf;
import static io.evitadb.externalApi.api.ExternalApiNamingConventions.PROPERTY_NAME_NAMING_CONVENTION;
import static io.evitadb.utils.CollectionUtils.createHashMap;

/**
 * TODO lho docs
 *
 * @author Lukáš Hornych, 2023
 */
@RequiredArgsConstructor
public class HierarchyParentsResolver {

	@Nonnull private final EntitySchemaContract entitySchema;
	/**
	 * Entity schemas for references of {@link #entitySchema} by field-formatted names.
	 */
	@Nonnull private final Map<String, EntitySchemaContract> referencedEntitySchemas;
	@Nonnull private final EntityFetchRequireResolver entityFetchRequireResolver;

	@Nonnull
	public List<RequireConstraint> resolve(@Nonnull SelectionSetWrapper extraResultsSelectionSet,
	                                       @Nullable Locale desiredLocale) {
		final List<SelectedField> parentsFields = extraResultsSelectionSet.getFields(ExtraResultsDescriptor.HIERARCHY_PARENTS.name());
		if (parentsFields.isEmpty()) {
			return List.of();
		}

		final Map<String, List<DataFetchingFieldSelectionSet>> parentsContentFields = createHashMap(20);
		parentsFields.stream()
			.flatMap(f -> SelectionSetWrapper.from(f.getSelectionSet()).getFields("*").stream())
			.forEach(f -> {
				final String referenceName = f.getName();
				final String originalReferenceName;
				if (referenceName.equals(HierarchyDescriptor.SELF.name())) {
					originalReferenceName = HierarchyDescriptor.SELF.name();
				} else {
					final ReferenceSchemaContract reference = entitySchema.getReferenceByName(referenceName, PROPERTY_NAME_NAMING_CONVENTION)
						.orElseThrow(() -> new GraphQLQueryResolvingInternalError("Could not find reference `" + referenceName + "` in `" + entitySchema.getName() + "`."));
					originalReferenceName = reference.getName();
				}

				final List<DataFetchingFieldSelectionSet> fields = parentsContentFields.computeIfAbsent(originalReferenceName, k -> new LinkedList<>());
				fields.addAll(
					SelectionSetWrapper.from(f.getSelectionSet())
						.getFields(ParentsOfEntityDescriptor.PARENT_ENTITIES.name())
						.stream()
						.map(SelectedField::getSelectionSet)
						.toList()
				);
				fields.addAll(
					SelectionSetWrapper.from(f.getSelectionSet())
						.getFields(ParentsOfEntityDescriptor.REFERENCES.name())
						.stream()
						.flatMap(f2 -> f2.getSelectionSet().getFields(ParentsOfReferenceDescriptor.PARENT_ENTITIES.name()).stream())
						.map(SelectedField::getSelectionSet)
						.toList()
				);
			});

		// construct actual requires from gathered data
		final List<RequireConstraint> requestedParents = new ArrayList<>(parentsContentFields.size());
		parentsContentFields.forEach((referenceName, contentFields) -> {
			if (referenceName.equals(HierarchyParentsDescriptor.SELF.name())) {
				final Optional<EntityFetch> entityFetch = entityFetchRequireResolver.resolveEntityFetch(
					SelectionSetWrapper.from(contentFields),
					desiredLocale,
					entitySchema
				);
				requestedParents.add(hierarchyParentsOfSelf(entityFetch.orElse(null)));
			} else {
				final Optional<EntityFetch> entityFetch = entityFetchRequireResolver.resolveEntityFetch(
					SelectionSetWrapper.from(contentFields),
					desiredLocale,
					referencedEntitySchemas.get(referenceName)
				);
				requestedParents.add(hierarchyParentsOfReference(referenceName, entityFetch.orElse(null)));
			}
		});

		return requestedParents;
	}
}
