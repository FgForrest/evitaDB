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

import graphql.schema.SelectedField;
import io.evitadb.api.query.RequireConstraint;
import io.evitadb.api.query.require.EntityFetch;
import io.evitadb.api.query.require.HierarchyNode;
import io.evitadb.api.query.require.HierarchyOfReference;
import io.evitadb.api.query.require.HierarchyOfSelf;
import io.evitadb.api.query.require.HierarchyRequireConstraint;
import io.evitadb.api.query.require.HierarchySiblings;
import io.evitadb.api.query.require.HierarchyStatistics;
import io.evitadb.api.query.require.HierarchyStopAt;
import io.evitadb.api.requestResponse.schema.EntitySchemaContract;
import io.evitadb.api.requestResponse.schema.ReferenceSchemaContract;
import io.evitadb.externalApi.api.catalog.dataApi.constraint.HierarchyDataLocator;
import io.evitadb.externalApi.api.catalog.dataApi.model.extraResult.ExtraResultsDescriptor;
import io.evitadb.externalApi.api.catalog.dataApi.model.extraResult.HierarchyDescriptor;
import io.evitadb.externalApi.api.catalog.dataApi.model.extraResult.HierarchyDescriptor.HierarchyOfDescriptor;
import io.evitadb.externalApi.api.catalog.dataApi.model.extraResult.HierarchyDescriptor.LevelInfoDescriptor;
import io.evitadb.externalApi.api.catalog.dataApi.model.extraResult.HierarchyDescriptor.ParentInfoDescriptor;
import io.evitadb.externalApi.api.model.PropertyDescriptor;
import io.evitadb.externalApi.graphql.api.catalog.dataApi.model.extraResult.HierarchyFromNodeHeaderDescriptor;
import io.evitadb.externalApi.graphql.api.catalog.dataApi.model.extraResult.HierarchyRequireHeaderDescriptor;
import io.evitadb.externalApi.graphql.api.resolver.SelectionSetWrapper;
import io.evitadb.externalApi.graphql.exception.GraphQLInvalidResponseUsageException;
import io.evitadb.externalApi.graphql.exception.GraphQLQueryResolvingInternalError;
import io.evitadb.utils.Assert;
import lombok.RequiredArgsConstructor;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.AbstractMap.SimpleEntry;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map.Entry;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

import static io.evitadb.api.query.QueryConstraints.*;
import static io.evitadb.externalApi.api.ExternalApiNamingConventions.PROPERTY_NAME_NAMING_CONVENTION;

/**
 * Custom constraint resolver which resolves additional constraints from output fields defined by client, rather
 * than using main query.
 * Resolves list of {@link HierarchyRequireConstraint}s based on which extra result fields client specified.
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2023
 */
@RequiredArgsConstructor
public class HierarchyExtraResultRequireResolver {

	@Nonnull private final Function<String, EntitySchemaContract> entitySchemaFetcher;
	@Nonnull private final EntityFetchRequireResolver entityFetchRequireResolver;
	@Nonnull private final RequireConstraintResolver requireConstraintResolver;

	@Nonnull
	public Collection<RequireConstraint> resolveHierarchyExtraResultRequires(@Nonnull SelectionSetWrapper extraResultsSelectionSet,
	                                                                                              @Nullable Locale desiredLocale,
	                                                                                              @Nullable EntitySchemaContract currentEntitySchema) {
		final List<SelectedField> hierarchyFields = extraResultsSelectionSet.getFields(ExtraResultsDescriptor.HIERARCHY.name());
		if (hierarchyFields.isEmpty()) {
			return List.of();
		}

		return hierarchyFields.stream()
			.flatMap(f -> SelectionSetWrapper.from(f.getSelectionSet()).getFields("*").stream())
			.map(referenceField -> {
				if (HierarchyDescriptor.SELF.name().equals(referenceField.getName())) {
					return resolveHierarchyOfSelf(referenceField, desiredLocale, currentEntitySchema);
				} else {
					return resolveHierarchyOfReference(referenceField, desiredLocale, currentEntitySchema);
				}
			})
			.collect(Collectors.toMap(Entry::getKey, Entry::getValue, (c, c2) -> {
				throw new GraphQLInvalidResponseUsageException("Duplicate hierarchies for single reference.");
			}))
			.values();
	}

	@Nonnull
	private Entry<String, RequireConstraint> resolveHierarchyOfSelf(@Nonnull SelectedField field,
	                                                                @Nullable Locale desiredLocale,
	                                                                @Nullable EntitySchemaContract currentEntitySchema) {
		final HierarchyRequireConstraint[] hierarchyRequires = resolveHierarchyRequirements(
			field,
			new HierarchyDataLocator(currentEntitySchema.getName()),
			desiredLocale,
			currentEntitySchema
		);

		final HierarchyOfSelf hierarchyOfSelf = hierarchyOfSelf(hierarchyRequires);
		return new SimpleEntry<>(HierarchyDescriptor.SELF.name(), hierarchyOfSelf);
	}

	@Nonnull
	private Entry<String, RequireConstraint> resolveHierarchyOfReference(@Nonnull SelectedField field,
	                                                                     @Nullable Locale desiredLocale,
	                                                                     @Nullable EntitySchemaContract currentEntitySchema) {
		final ReferenceSchemaContract reference = currentEntitySchema.getReferenceByName(field.getName(), PROPERTY_NAME_NAMING_CONVENTION)
			.orElseThrow(() ->
				new GraphQLQueryResolvingInternalError("Could not find reference `" + field.getName() + "` in `" + currentEntitySchema.getName() + "`."));

		final String referenceName = reference.getName();
		final HierarchyDataLocator hierarchyDataLocator = new HierarchyDataLocator(currentEntitySchema.getName(), referenceName);
		final EntitySchemaContract hierarchyEntitySchema = reference.isReferencedEntityTypeManaged()
			? entitySchemaFetcher.apply(reference.getReferencedEntityType())
			: null;

		final HierarchyRequireConstraint[] hierarchyRequires = resolveHierarchyRequirements(
			field,
			hierarchyDataLocator,
			desiredLocale,
			hierarchyEntitySchema
		);

		final HierarchyOfReference hierarchyOfReference = hierarchyOfReference(referenceName, hierarchyRequires);
		return new SimpleEntry<>(referenceName, hierarchyOfReference);
	}

	@Nonnull
	private HierarchyRequireConstraint[] resolveHierarchyRequirements(@Nonnull SelectedField field,
	                                                                  @Nonnull HierarchyDataLocator hierarchyDataLocator,
	                                                                  @Nullable Locale desiredLocale,
	                                                                  @Nonnull EntitySchemaContract hierarchyEntitySchema) {
		return field.getSelectionSet()
			.getFields("*")
			.stream()
			.map(specificHierarchyField -> resolveHierarchyRequire(
				specificHierarchyField,
				hierarchyDataLocator,
				desiredLocale,
				hierarchyEntitySchema
			))
			.collect(Collectors.toMap(Entry::getKey, Entry::getValue, (c, c2) -> {
				throw new GraphQLInvalidResponseUsageException("Duplicate hierarchy output name `" + c.getOutputName() + "`.");
			}))
			.values()
			.toArray(HierarchyRequireConstraint[]::new);
	}

	@Nonnull
	private Entry<String, HierarchyRequireConstraint> resolveHierarchyRequire(@Nonnull SelectedField field,
	                                                                          @Nonnull HierarchyDataLocator hierarchyDataLocator,
	                                                                          @Nullable Locale desiredLocale,
	                                                                          @Nullable EntitySchemaContract currentEntitySchema) {
		final String outputName = HierarchyRequireOutputNameResolver.resolve(field);

		final String hierarchyType = field.getName();
		final HierarchyStopAt stopAt = resolveChildHierarchyRequireFromArgument(field, hierarchyDataLocator, HierarchyRequireHeaderDescriptor.STOP_AT);
		final HierarchyStatistics statistics = resolveChildHierarchyRequireFromArgument(field, hierarchyDataLocator, HierarchyRequireHeaderDescriptor.STATISTICS);
		final EntityFetch entityFetch = resolveHierarchyEntityFetch(field, desiredLocale, currentEntitySchema);

		final HierarchyRequireConstraint hierarchyRequire;
		if (HierarchyOfDescriptor.FROM_ROOT.name().equals(hierarchyType)) {
			hierarchyRequire = fromRoot(outputName, entityFetch, stopAt, statistics);
		} else if (HierarchyOfDescriptor.FROM_NODE.name().equals(hierarchyType)) {
			final HierarchyNode node = resolveChildHierarchyRequireFromArgument(field, hierarchyDataLocator, HierarchyFromNodeHeaderDescriptor.NODE);
			Assert.isPremiseValid(
				node != null,
				() -> new GraphQLQueryResolvingInternalError("Missing `" + HierarchyFromNodeHeaderDescriptor.NODE.name() + "` argument.")
			);
			hierarchyRequire = fromNode(outputName, node, entityFetch, stopAt, statistics);
		} else if (HierarchyOfDescriptor.CHILDREN.name().equals(hierarchyType)) {
			hierarchyRequire = children(outputName, entityFetch, stopAt, statistics);
		} else if (HierarchyOfDescriptor.PARENTS.name().equals(hierarchyType)) {
			final HierarchySiblings siblings = resolveSiblingsOfParents(field, hierarchyDataLocator, desiredLocale, currentEntitySchema);
			hierarchyRequire = parents(outputName, entityFetch, siblings, stopAt, statistics);
		} else if (HierarchyOfDescriptor.SIBLINGS.name().equals(hierarchyType)) {
			hierarchyRequire = siblings(outputName, entityFetch, stopAt, statistics);
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
