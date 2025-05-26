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
import io.evitadb.api.query.HierarchyConstraint;
import io.evitadb.api.query.RequireConstraint;
import io.evitadb.api.query.order.OrderBy;
import io.evitadb.api.query.require.*;
import io.evitadb.api.requestResponse.schema.EntitySchemaContract;
import io.evitadb.api.requestResponse.schema.ReferenceSchemaContract;
import io.evitadb.externalApi.api.catalog.dataApi.constraint.DataLocator;
import io.evitadb.externalApi.api.catalog.dataApi.constraint.EntityDataLocator;
import io.evitadb.externalApi.api.catalog.dataApi.constraint.HierarchyDataLocator;
import io.evitadb.externalApi.api.catalog.dataApi.constraint.ManagedEntityTypePointer;
import io.evitadb.externalApi.api.catalog.dataApi.model.extraResult.ExtraResultsDescriptor;
import io.evitadb.externalApi.api.catalog.dataApi.model.extraResult.HierarchyDescriptor;
import io.evitadb.externalApi.api.model.PropertyDescriptor;
import io.evitadb.externalApi.graphql.api.catalog.dataApi.model.extraResult.HierarchyFromNodeHeaderDescriptor;
import io.evitadb.externalApi.graphql.api.catalog.dataApi.model.extraResult.HierarchyHeaderDescriptor;
import io.evitadb.externalApi.graphql.api.catalog.dataApi.model.extraResult.HierarchyOfDescriptor;
import io.evitadb.externalApi.graphql.api.catalog.dataApi.model.extraResult.HierarchyOfReferenceHeaderDescriptor;
import io.evitadb.externalApi.graphql.api.catalog.dataApi.model.extraResult.HierarchyParentsHeaderDescriptor;
import io.evitadb.externalApi.graphql.api.catalog.dataApi.model.extraResult.HierarchyParentsHeaderDescriptor.HierarchyParentsSiblingsSpecification;
import io.evitadb.externalApi.graphql.api.catalog.dataApi.model.extraResult.HierarchyRequireHeaderDescriptor;
import io.evitadb.externalApi.graphql.api.catalog.dataApi.model.extraResult.LevelInfoDescriptor;
import io.evitadb.externalApi.graphql.api.resolver.SelectionSetAggregator;
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
import java.util.Map;
import java.util.Map.Entry;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import static io.evitadb.api.query.QueryConstraints.*;
import static io.evitadb.externalApi.api.ExternalApiNamingConventions.PROPERTY_NAME_NAMING_CONVENTION;
import static io.evitadb.utils.CollectionUtils.createHashSet;

/**
 * Custom constraint resolver which resolves additional constraints from output fields defined by client, rather
 * than using main query.
 * Resolves list of {@link HierarchyRequireConstraint}s based on which extra result fields client specified.
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2023
 */
@RequiredArgsConstructor
public class HierarchyExtraResultRequireResolver {

	@Nonnull private final EntitySchemaContract entitySchema;
	@Nonnull private final Function<String, EntitySchemaContract> entitySchemaFetcher;
	@Nonnull private final EntityFetchRequireResolver entityFetchRequireResolver;
	@Nonnull private final OrderConstraintResolver orderConstraintResolver;
	@Nonnull private final RequireConstraintResolver requireConstraintResolver;

	@Nonnull
	public Collection<RequireConstraint> resolve(@Nonnull SelectionSetAggregator extraResultsSelectionSet,
	                                             @Nullable Locale desiredLocale) {
		final List<SelectedField> hierarchyFields = extraResultsSelectionSet.getImmediateFields(ExtraResultsDescriptor.HIERARCHY.name());
		if (hierarchyFields.isEmpty()) {
			return List.of();
		}

		return hierarchyFields.stream()
			.flatMap(f -> SelectionSetAggregator.getImmediateFields(f.getSelectionSet()).stream())
			.map(referenceField -> {
				if (HierarchyDescriptor.SELF.name().equals(referenceField.getName())) {
					return resolveHierarchyOfSelf(referenceField, desiredLocale);
				} else {
					return resolveHierarchyOfReference(referenceField, desiredLocale);
				}
			})
			.collect(Collectors.toMap(Entry::getKey, Entry::getValue, (c, c2) -> {
				throw new GraphQLInvalidResponseUsageException("Duplicate hierarchies for single reference.");
			}))
			.values();
	}

	@Nonnull
	private Entry<String, RequireConstraint> resolveHierarchyOfSelf(@Nonnull SelectedField field,
	                                                                @Nullable Locale desiredLocale) {

		final OrderBy orderBy = (OrderBy) Optional.ofNullable(field.getArguments().get(HierarchyHeaderDescriptor.ORDER_BY.name()))
			.map(it -> this.orderConstraintResolver.resolve(
				new EntityDataLocator(new ManagedEntityTypePointer(this.entitySchema.getName())),
				HierarchyHeaderDescriptor.ORDER_BY.name(),
				it
			))
			.orElse(null);

		final HierarchyRequireConstraint[] hierarchyRequires = resolveHierarchyRequirements(
			field,
			this.entitySchema,
			new HierarchyDataLocator(new ManagedEntityTypePointer(this.entitySchema.getName())),
			desiredLocale
		);

		final HierarchyOfSelf hierarchyOfSelf = hierarchyOfSelf(orderBy, hierarchyRequires);
		return new SimpleEntry<>(HierarchyDescriptor.SELF.name(), hierarchyOfSelf);
	}

	@Nonnull
	private Entry<String, RequireConstraint> resolveHierarchyOfReference(@Nonnull SelectedField field,
	                                                                     @Nullable Locale desiredLocale) {
		final ReferenceSchemaContract referenceSchema = this.entitySchema.getReferenceByName(field.getName(), PROPERTY_NAME_NAMING_CONVENTION)
			.orElseThrow(() ->
				new GraphQLQueryResolvingInternalError("Could not find reference `" + field.getName() + "` in `" + this.entitySchema.getName() + "`."));

		final String referenceName = referenceSchema.getName();
		final EntitySchemaContract hierarchyEntitySchema = referenceSchema.isReferencedEntityTypeManaged()
			? this.entitySchemaFetcher.apply(referenceSchema.getReferencedEntityType())
			: null;

		final EmptyHierarchicalEntityBehaviour emptyHierarchicalEntityBehaviour =
			(EmptyHierarchicalEntityBehaviour) field.getArguments().get(HierarchyOfReferenceHeaderDescriptor.EMPTY_HIERARCHICAL_ENTITY_BEHAVIOUR.name());
		final OrderBy orderBy;
		if (referenceSchema.isReferencedEntityTypeManaged()) {
			Assert.isPremiseValid(
				hierarchyEntitySchema != null,
				() -> new GraphQLQueryResolvingInternalError("Could not find entity schema for reference `" + referenceName + "` in `" + this.entitySchema.getName() + "`.")
			);
			orderBy = (OrderBy) Optional.ofNullable(field.getArguments().get(HierarchyHeaderDescriptor.ORDER_BY.name()))
				.map(it -> this.orderConstraintResolver.resolve(
					new EntityDataLocator(new ManagedEntityTypePointer(hierarchyEntitySchema.getName())),
					HierarchyHeaderDescriptor.ORDER_BY.name(),
					it
				))
				.orElse(null);
		} else {
			orderBy = null;
		}

		final HierarchyRequireConstraint[] hierarchyRequires = resolveHierarchyRequirements(
			field,
			hierarchyEntitySchema,
			new HierarchyDataLocator(new ManagedEntityTypePointer(this.entitySchema.getName()), referenceName),
			desiredLocale
		);

		final HierarchyOfReference hierarchyOfReference = hierarchyOfReference(
			referenceName,
			emptyHierarchicalEntityBehaviour,
			orderBy,
			hierarchyRequires
		);
		return new SimpleEntry<>(referenceName, hierarchyOfReference);
	}

	@Nonnull
	private HierarchyRequireConstraint[] resolveHierarchyRequirements(@Nonnull SelectedField field,
																	  @Nullable EntitySchemaContract hierarchyEntitySchema,
	                                                                  @Nonnull DataLocator hierarchyDataLocator,
	                                                                  @Nullable Locale desiredLocale) {
		return field.getSelectionSet()
			.getImmediateFields()
			.stream()
			.map(specificHierarchyField -> resolveHierarchyRequire(
				specificHierarchyField,
				hierarchyEntitySchema,
				hierarchyDataLocator,
				desiredLocale
			))
			.collect(Collectors.toMap(Entry::getKey, Entry::getValue, (c, c2) -> {
				throw new GraphQLInvalidResponseUsageException("Duplicate hierarchy output name `" + c.getOutputName() + "`.");
			}))
			.values()
			.toArray(HierarchyRequireConstraint[]::new);
	}

	@Nonnull
	private Entry<String, HierarchyRequireConstraint> resolveHierarchyRequire(@Nonnull SelectedField field,
	                                                                          @Nullable EntitySchemaContract hierarchyEntitySchema,
	                                                                          @Nonnull DataLocator hierarchyDataLocator,
	                                                                          @Nullable Locale desiredLocale) {
		final String outputName = HierarchyRequireOutputNameResolver.resolve(field);

		final String hierarchyType = field.getName();
		final HierarchyStopAt stopAt = resolveChildHierarchyRequireFromArgument(field, hierarchyDataLocator, HierarchyRequireHeaderDescriptor.STOP_AT);
		final HierarchyStatistics statistics = resolveHierarchyStatistics(field);
		final EntityFetch entityFetch = resolveHierarchyEntityFetch(field, hierarchyEntitySchema, desiredLocale);

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
			final HierarchySiblings siblings = resolveSiblingsOfParents(field, hierarchyDataLocator);
			hierarchyRequire = parents(outputName, entityFetch, siblings, stopAt, statistics);
		} else if (HierarchyOfDescriptor.SIBLINGS.name().equals(hierarchyType)) {
			hierarchyRequire = siblings(outputName, entityFetch, stopAt, statistics);
		} else {
			throw new GraphQLQueryResolvingInternalError("Unsupported hierarchy type `" + hierarchyType + "`.");
		}

		return new SimpleEntry<>(outputName, hierarchyRequire);
	}

	@Nullable
	private <T extends HierarchyConstraint<RequireConstraint>> T resolveChildHierarchyRequireFromArgument(@Nonnull SelectedField field,
	                                                                                                      @Nonnull DataLocator hierarchyDataLocator,
	                                                                                                      @Nonnull PropertyDescriptor argumentDescriptor) {
		//noinspection unchecked
		return (T) Optional.ofNullable(field.getArguments().get(argumentDescriptor.name()))
			.map(it -> this.requireConstraintResolver.resolve(hierarchyDataLocator, hierarchyDataLocator, argumentDescriptor.name(), it))
			.orElse(null);
	}


	@Nullable
	private EntityFetch resolveHierarchyEntityFetch(@Nonnull SelectedField field,
	                                                @Nullable EntitySchemaContract hierarchyEntitySchema,
	                                                @Nullable Locale desiredLocale) {
		return this.entityFetchRequireResolver.resolveEntityFetch(
			SelectionSetAggregator.from(
				SelectionSetAggregator.getImmediateFields(LevelInfoDescriptor.ENTITY.name(), field.getSelectionSet())
					.stream()
					.map(SelectedField::getSelectionSet)
					.toList()
			),
			desiredLocale,
			hierarchyEntitySchema
		)
			.orElse(null);
	}

	@Nullable
	private static HierarchyStatistics resolveHierarchyStatistics(@Nonnull SelectedField field) {
		final Set<StatisticsType> statisticsTypes = createHashSet(2);
		if (SelectionSetAggregator.containsImmediate(LevelInfoDescriptor.CHILDREN_COUNT.name(), field.getSelectionSet())) {
			statisticsTypes.add(StatisticsType.CHILDREN_COUNT);
		}
		if (SelectionSetAggregator.containsImmediate(LevelInfoDescriptor.QUERIED_ENTITY_COUNT.name(), field.getSelectionSet())) {
			statisticsTypes.add(StatisticsType.QUERIED_ENTITY_COUNT);
		}
		if (SelectionSetAggregator.isEmpty(field.getSelectionSet())) {
			// no statistics were requested
			return null;
		}

		final Optional<StatisticsBase> statisticsBase = Optional.ofNullable(field.getArguments().get(HierarchyRequireHeaderDescriptor.STATISTICS_BASE.name()))
			.map(StatisticsBase.class::cast);

		return statistics(
			statisticsBase.orElse(StatisticsBase.WITHOUT_USER_FILTER),
			statisticsTypes.toArray(StatisticsType[]::new)
		);
	}

	@Nullable
	private HierarchySiblings resolveSiblingsOfParents(@Nonnull SelectedField field,
	                                                   @Nonnull DataLocator hierarchyDataLocator) {

		//noinspection unchecked
		final Map<String, Object> siblingsSpecification = (Map<String, Object>) field.getArguments()
			.get(HierarchyParentsHeaderDescriptor.SIBLINGS.name());
		if (siblingsSpecification == null) {
			return null;
		}

		final HierarchyStopAt stopAt = Optional.ofNullable(siblingsSpecification.get(HierarchyParentsSiblingsSpecification.STOP_AT.name()))
			.map(it -> (HierarchyStopAt) this.requireConstraintResolver.resolve(hierarchyDataLocator, hierarchyDataLocator, HierarchyParentsSiblingsSpecification.STOP_AT.name(), it))
			.orElse(null);

		return new HierarchySiblings(null, stopAt);
	}
}
