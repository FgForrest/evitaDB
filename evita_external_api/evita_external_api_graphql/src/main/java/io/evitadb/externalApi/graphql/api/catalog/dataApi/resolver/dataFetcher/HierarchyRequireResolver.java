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

package io.evitadb.externalApi.graphql.api.catalog.dataApi.resolver.dataFetcher;

import graphql.schema.SelectedField;
import io.evitadb.api.query.require.*;
import io.evitadb.api.requestResponse.schema.EntitySchemaContract;
import io.evitadb.api.requestResponse.schema.ReferenceSchemaContract;
import io.evitadb.externalApi.api.catalog.dataApi.constraint.HierarchyDataLocator;
import io.evitadb.externalApi.api.catalog.dataApi.model.extraResult.ExtraResultsDescriptor;
import io.evitadb.externalApi.api.catalog.dataApi.model.extraResult.HierarchyDescriptor;
import io.evitadb.externalApi.api.catalog.dataApi.model.extraResult.HierarchyDescriptor.HierarchyOfDescriptor;
import io.evitadb.externalApi.api.catalog.dataApi.model.extraResult.HierarchyDescriptor.LevelInfoDescriptor;
import io.evitadb.externalApi.api.catalog.dataApi.model.extraResult.HierarchyDescriptor.ParentInfoDescriptor;
import io.evitadb.externalApi.api.model.PropertyDescriptor;
import io.evitadb.externalApi.graphql.api.catalog.dataApi.model.HierarchyHeaderDescriptor.HierarchyFromNodeHeaderDescriptor;
import io.evitadb.externalApi.graphql.api.catalog.dataApi.model.HierarchyHeaderDescriptor.HierarchyFromRootHeaderDescriptor;
import io.evitadb.externalApi.graphql.api.catalog.dataApi.model.HierarchyHeaderDescriptor.HierarchyRequireHeaderDescriptor;
import io.evitadb.externalApi.graphql.api.catalog.dataApi.resolver.constraint.RequireConstraintResolver;
import io.evitadb.externalApi.graphql.exception.GraphQLInvalidResponseUsageException;
import io.evitadb.externalApi.graphql.exception.GraphQLQueryResolvingInternalError;
import io.evitadb.utils.Assert;
import lombok.RequiredArgsConstructor;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.AbstractMap.SimpleEntry;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Optional;

import static io.evitadb.externalApi.api.ExternalApiNamingConventions.PROPERTY_NAME_NAMING_CONVENTION;
import static io.evitadb.utils.CollectionUtils.createHashMap;

/**
 * TODO lho docs
 *
 * @author Lukáš Hornych, 2023
 */
@RequiredArgsConstructor
public class HierarchyRequireResolver {

	@Nonnull private final EntityFetchRequireResolver entityFetchRequireResolver;
	@Nonnull private final RequireConstraintResolver requireConstraintResolver;

	@Nonnull
	public List<HierarchyRequireConstraint> resolveHierarchyRequires(@Nonnull SelectionSetWrapper extraResultsSelectionSet,
	                                                                 @Nullable Locale desiredLocale,
	                                                                 @Nullable EntitySchemaContract currentEntitySchema) {
		final List<SelectedField> hierarchyFields = extraResultsSelectionSet.getFields(ExtraResultsDescriptor.HIERARCHY.name());
		if (hierarchyFields.isEmpty()) {
			return List.of();
		}

		// key is an output name
		final Map<String, HierarchyRequireConstraint> requestedHierarchies = createHashMap(20);
		hierarchyFields.stream()
			.flatMap(f -> SelectionSetWrapper.from(f.getSelectionSet()).getFields("*").stream())
			.forEach(referenceField -> {
				final HierarchyDataLocator hierarchyDataLocator = resolveHierarchyDataLocator(currentEntitySchema, referenceField);

				referenceField.getSelectionSet()
					.getFields("*")
					.forEach(specificHierarchyField -> {
						final Entry<String, HierarchyRequireConstraint> hierarchyRequire = resolveHierarchyRequire(
							specificHierarchyField,
							hierarchyDataLocator,
							desiredLocale,
							currentEntitySchema
						);
						requestedHierarchies.merge(
							hierarchyRequire.getKey(),
							hierarchyRequire.getValue(),
							(c, c2) -> {
								throw new GraphQLInvalidResponseUsageException("Duplicate hierarchy output name `" + hierarchyRequire.getKey() + "`.");
							}
						);
					});
			});

		return new ArrayList<>(requestedHierarchies.values());
	}

	@Nonnull
	private HierarchyDataLocator resolveHierarchyDataLocator(@Nullable EntitySchemaContract currentEntitySchema,
	                                                         @Nonnull SelectedField referenceField) {
		final String referenceName = referenceField.getName();

		if (referenceName.equals(HierarchyDescriptor.SELF.name())) {
			return new HierarchyDataLocator(currentEntitySchema.getName());
		} else {
			final ReferenceSchemaContract reference = currentEntitySchema.getReferenceByName(referenceName, PROPERTY_NAME_NAMING_CONVENTION)
				.orElseThrow(() -> new GraphQLQueryResolvingInternalError("Could not find reference `" + referenceName + "` in `" + currentEntitySchema.getName() + "`."));
			return new HierarchyDataLocator(currentEntitySchema.getName(), reference.getName());
		}
	}

	@Nonnull
	private Map.Entry<String, HierarchyRequireConstraint> resolveHierarchyRequire(@Nonnull SelectedField field,
	                                                                              @Nonnull HierarchyDataLocator hierarchyDataLocator,
	                                                                              @Nullable Locale desiredLocale,
	                                                                              @Nullable EntitySchemaContract currentEntitySchema) {
		// todo lho try to implement full path before name to allow hierarchies across duplicate hierarchy fields
		final String outputName = field.getAlias() != null ? field.getAlias() : field.getName();

		final String hierarchyType = field.getName();
		final HierarchyStopAt stopAt = resolveChildHierarchyRequireFromArgument(field, hierarchyDataLocator, HierarchyRequireHeaderDescriptor.STOP_AT);
		final HierarchyStatistics statistics = resolveChildHierarchyRequireFromArgument(field, hierarchyDataLocator, HierarchyRequireHeaderDescriptor.STATISTICS);
		final EntityFetch entityFetch = resolveHierarchyEntityFetch(field, desiredLocale, currentEntitySchema);

		final HierarchyRequireConstraint hierarchyRequire;
		if (HierarchyOfDescriptor.FROM_ROOT.name().equals(hierarchyType)) {
			hierarchyRequire = new HierarchyFromRoot(outputName, entityFetch, stopAt, statistics);
		} else if (HierarchyOfDescriptor.FROM_NODE.name().equals(hierarchyType)) {
			final HierarchyNode node = resolveChildHierarchyRequireFromArgument(field, hierarchyDataLocator, HierarchyFromNodeHeaderDescriptor.NODE);
			hierarchyRequire = new HierarchyFromNode(outputName, node, entityFetch, stopAt, statistics);
		} else if (HierarchyOfDescriptor.CHILDREN.name().equals(hierarchyType)) {
			hierarchyRequire = new HierarchyChildren(outputName, entityFetch, stopAt, statistics);
		} else if (HierarchyOfDescriptor.PARENTS.name().equals(hierarchyType)) {
			final HierarchySiblings siblings = resolveSiblingsOfParents(field, hierarchyDataLocator, desiredLocale, currentEntitySchema);
			hierarchyRequire = new HierarchyParents(outputName, entityFetch, siblings, stopAt, statistics);
		} else if (HierarchyOfDescriptor.SIBLINGS.name().equals(hierarchyType)) {
			hierarchyRequire = new HierarchySiblings(outputName, entityFetch, stopAt, statistics);
		} else {
			throw new GraphQLQueryResolvingInternalError("Unsupported hierarchy type `" + hierarchyType + "`.");
		}

		return new SimpleEntry<>(outputName, hierarchyRequire);
	}

	@Nullable
	private <T extends HierarchyRequireConstraint> T resolveChildHierarchyRequireFromArgument(@Nonnull SelectedField field,
	                                                                                          @Nonnull HierarchyDataLocator hierarchyDataLocator,
	                                                                                          @Nonnull PropertyDescriptor argumentDescriptor) {
		//noinspection unchecked
		return (T) Optional.ofNullable(field.getArguments().get(argumentDescriptor.name()))
			.map(it -> requireConstraintResolver.resolve(hierarchyDataLocator, argumentDescriptor.name(), it))
			.orElse(null);
	}


	@Nullable
	private EntityFetch resolveHierarchyEntityFetch(@Nonnull SelectedField field,
	                                                @Nullable Locale desiredLocale,
	                                                @Nullable EntitySchemaContract currentEntitySchema) {
		return entityFetchRequireResolver.resolveEntityFetch(
			SelectionSetWrapper.from(
				field.getSelectionSet().getFields(LevelInfoDescriptor.ENTITY.name())
					.stream()
					.map(SelectedField::getSelectionSet)
					.toList()
			),
			desiredLocale,
			currentEntitySchema
		)
			.orElse(null);
	}

	@Nullable
	private HierarchySiblings resolveSiblingsOfParents(@Nonnull SelectedField field,
	                                                   @Nonnull HierarchyDataLocator hierarchyDataLocator,
	                                                   @Nullable Locale desiredLocale,
	                                                   @Nullable EntitySchemaContract currentEntitySchema) {
		final List<SelectedField> siblingsFields = field.getSelectionSet().getFields(ParentInfoDescriptor.SIBLINGS.name());

		if (siblingsFields.isEmpty()) {
			return null;
		} else {
			Assert.isTrue(
				siblingsFields.size() == 1,
				() -> new GraphQLInvalidResponseUsageException("Only single siblings are support in parents.")
			);

			final Entry<String, HierarchyRequireConstraint> siblings = resolveHierarchyRequire(
				siblingsFields.get(0),
				hierarchyDataLocator,
				desiredLocale,
				currentEntitySchema
			);
			return (HierarchySiblings) siblings.getValue();
		}
	}
}
