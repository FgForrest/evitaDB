/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2023-2026
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

package io.evitadb.api.functional.facet;

import io.evitadb.api.query.FilterConstraint;
import io.evitadb.api.query.Query;
import io.evitadb.api.query.filter.EntityPrimaryKeyInSet;
import io.evitadb.api.query.filter.FacetHaving;
import io.evitadb.api.query.filter.FilterBy;
import io.evitadb.api.query.require.FacetCalculationRules;
import io.evitadb.api.query.require.FacetGroupRelationLevel;
import io.evitadb.api.query.require.FacetGroupsConjunction;
import io.evitadb.api.query.require.FacetGroupsConstraint;
import io.evitadb.api.query.require.FacetGroupsDisjunction;
import io.evitadb.api.query.require.FacetGroupsExclusivity;
import io.evitadb.api.query.require.FacetGroupsNegation;
import io.evitadb.api.query.require.FacetRelationType;
import io.evitadb.api.requestResponse.data.ReferenceContract;
import io.evitadb.api.requestResponse.data.SealedEntity;
import io.evitadb.api.requestResponse.data.mutation.reference.ReferenceKey;
import io.evitadb.api.requestResponse.schema.EntitySchemaContract;
import io.evitadb.api.requestResponse.schema.ReferenceSchemaContract;
import io.evitadb.test.Entities;
import io.evitadb.utils.ArrayUtils;
import io.evitadb.utils.Assert;
import lombok.Getter;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.IntPredicate;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import static io.evitadb.api.query.QueryUtils.findConstraints;
import static io.evitadb.api.query.QueryUtils.findRequire;
import static io.evitadb.api.query.QueryUtils.findRequires;
import static io.evitadb.api.query.require.FacetGroupRelationLevel.WITH_DIFFERENT_FACETS_IN_GROUP;
import static io.evitadb.api.query.require.FacetGroupRelationLevel.WITH_DIFFERENT_GROUPS;
import static java.util.Optional.ofNullable;
import static java.util.stream.Collectors.groupingBy;
import static java.util.stream.Collectors.toList;

/**
 * Strategy defining how facet IDs are matched against an entity's references.
 *
 * - `AND` requires all facet IDs to be present (conjunction)
 * - `OR` requires at least one facet ID to be present (disjunction)
 * - `NOT` requires none of the facet IDs to be present (negation)
 * - `EXCLUSIVE` behaves like `OR` for matching but on combine replaces existing facets
 * rather than merging them (exclusivity within the group)
 */
enum FacetMatchStrategy {
	AND("AND", IntStream::allMatch),
	OR("OR", IntStream::anyMatch),
	NOT("NOT", IntStream::noneMatch),
	EXCLUSIVE("EXCLUSIVE", IntStream::anyMatch);

	/** Human-readable label for this strategy (e.g. "AND", "OR"). */
	@Getter private final String label;
	/** Function that applies the matching logic over a stream of facet IDs and a containment predicate. */
	private final BiFunction<IntStream, IntPredicate, Boolean> matcher;

	FacetMatchStrategy(
		@Nonnull String label,
		@Nonnull BiFunction<IntStream, IntPredicate, Boolean> matcher
	) {
		this.label = label;
		this.matcher = matcher;
	}

	/**
	 * Tests whether the given facet IDs match against the entity's reference set
	 * according to this strategy.
	 *
	 * @param facetIds     the facet IDs to test
	 * @param referenceSet the set of referenced primary keys from the entity
	 * @return `true` if the facet IDs match according to this strategy
	 */
	boolean test(@Nonnull int[] facetIds, @Nonnull Set<Integer> referenceSet) {
		return this.matcher.apply(Arrays.stream(facetIds), referenceSet::contains);
	}
}

/**
 * Facet predicate parameterized by a [FacetMatchStrategy]. Tests whether a sealed entity
 * matches specific facet criteria. Provides reference schema, group, and facet ID information
 * along with a combine operation for merging additional facet constraints.
 *
 * @param strategy        the matching strategy (AND, OR, NOT, or EXCLUSIVE)
 * @param referenceSchema the reference schema this predicate applies to
 * @param facetGroupId    the optional facet group ID
 * @param facetIds        the facet IDs to match against
 */
record FacetPredicate(
	@Nonnull FacetMatchStrategy strategy,
	@Nonnull ReferenceSchemaContract referenceSchema,
	@Nullable Integer facetGroupId,
	@Nonnull int[] facetIds
) implements Predicate<SealedEntity> {

	@Override
	public boolean test(SealedEntity entity) {
		final Set<Integer> referenceSet = entity.getReferences(this.referenceSchema.getName())
			.stream()
			.map(ReferenceContract::getReferencedPrimaryKey)
			.collect(Collectors.toSet());
		return this.strategy.test(this.facetIds, referenceSet);
	}

	/**
	 * Creates a new predicate that combines this predicate's facet IDs with the given ones.
	 *
	 * @param referenceSchema the reference schema (must match this predicate's schema)
	 * @param facetGroupId    the facet group ID (must match this predicate's group)
	 * @param facetIds        the additional facet IDs to combine
	 * @return a new combined predicate
	 */
	@Nonnull
	FacetPredicate combine(
		@Nonnull ReferenceSchemaContract referenceSchema, @Nullable Integer facetGroupId, @Nonnull int... facetIds) {
		Assert.isTrue(this.referenceSchema.equals(referenceSchema), "Sanity check!");
		Assert.isTrue(Objects.equals(this.facetGroupId, facetGroupId), "Sanity check!");
		if (this.strategy == FacetMatchStrategy.EXCLUSIVE) {
			// exclusivity replaces existing facets with an OR predicate on new facets only
			return new FacetPredicate(FacetMatchStrategy.OR, referenceSchema, facetGroupId(), facetIds);
		}
		return new FacetPredicate(
			this.strategy, referenceSchema, facetGroupId(), ArrayUtils.mergeArrays(facetIds(), facetIds));
	}

	@Nonnull
	@Override
	public String toString() {
		return referenceSchema() + ofNullable(this.facetGroupId).map(it -> " " + it).orElse("") +
			" (" + this.strategy.getLabel() + "):" + Arrays.toString(facetIds());
	}

}

/**
 * Internal data structure for referencing nullable groups. Associates a reference schema
 * with an optional group ID and provides natural ordering by reference name then group ID.
 *
 * @param referenceSchema the reference schema for this group reference
 * @param groupId         the optional group primary key, or `null` for ungrouped references
 */
record GroupReference(
	@Nonnull ReferenceSchemaContract referenceSchema,
	@Nullable Integer groupId
) implements Comparable<GroupReference> {

	GroupReference {
		Assert.notNull(referenceSchema, "Reference schema must not be null!");
	}

	@Override
	public int compareTo(@Nonnull GroupReference o) {
		final int first = this.referenceSchema.getName().compareTo(o.referenceSchema.getName());
		return first == 0 ? ofNullable(this.groupId).map(it -> ofNullable(o.groupId).map(it::compareTo).orElse(-1))
			.orElseGet(() -> o.groupId != null ? 1 : 0) : first;
	}

	@Override
	public boolean equals(@Nullable Object o) {
		if (!(o instanceof GroupReference that)) return false;

		return compareTo(that) == 0;
	}

	@Override
	public int hashCode() {
		int result = this.referenceSchema.hashCode();
		result = 31 * result + Objects.hashCode(this.groupId);
		return result;
	}
}

/**
 * Computational context for facet summary calculation. Encapsulates the logic for creating
 * facet filtering predicates based on query constraints (conjunction, disjunction, negation,
 * exclusivity) and provides methods for computing base and test predicates used during
 * facet impact calculation.
 *
 * This class is stateless after construction and can be used to evaluate multiple entities.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
class FacetComputationalContext {
	/** The entity schema defining available references and their structure. */
	private final EntitySchemaContract entitySchema;
	/** The original query containing filter and require constraints. */
	private final Query query;
	/** Predicate that checks whether a given facet (by reference name and ID) was requested in the query. */
	private final BiFunction<String, Integer, Boolean> facetSelectionPredicate;
	/** Mapping from parameter facet primary key to its group primary key. */
	private final Map<Integer, Integer> parameterGroupMapping;
	/** Pre-computed facet predicates derived from the query's existing facet filter constraints. */
	private final List<FacetPredicate> existingFacetPredicates;
	/** Groups configured with conjunction (AND) relation, indexed by relation level. */
	private final Map<FacetGroupRelationLevel, Set<GroupReference>> conjugatedGroups;
	/** Groups configured with disjunction (OR) relation, indexed by relation level. */
	private final Map<FacetGroupRelationLevel, Set<GroupReference>> disjugatedGroups;
	/** Groups configured with negation (NOT) relation, indexed by relation level. */
	private final Map<FacetGroupRelationLevel, Set<GroupReference>> negatedGroups;
	/** Groups configured with exclusivity relation, indexed by relation level. */
	private final Map<FacetGroupRelationLevel, Set<GroupReference>> exclusiveGroups;
	/** Default relation type for facets within the same group when no explicit setting exists. */
	private final FacetRelationType defaultFacetRelationType;
	/** Default relation type for facets across different groups when no explicit setting exists. */
	private final FacetRelationType defaultGroupRelationType;

	/**
	 * Extracts facet primary key IDs from a [FacetHaving] filter constraint.
	 *
	 * @param facetHavingFilter the facet having filter to extract IDs from
	 * @return an array of facet primary keys
	 */
	@Nonnull
	static int[] extractFacetIds(@Nonnull FacetHaving facetHavingFilter) {
		for (FilterConstraint child : facetHavingFilter.getChildren()) {
			if (child instanceof EntityPrimaryKeyInSet epkis) {
				return epkis.getPrimaryKeys();
			} else {
				throw new IllegalArgumentException("Unsupported constraint in facet filter: " + child);
			}
		}
		return new int[0];
	}

	/**
	 * Extracts entity primary key IDs from a [FilterBy] constraint.
	 *
	 * @param filterBy the filter by constraint to extract IDs from
	 * @return an array of entity primary keys
	 */
	@Nonnull
	static int[] extractFacetIds(@Nonnull FilterBy filterBy) {
		for (FilterConstraint child : filterBy.getChildren()) {
			if (child instanceof EntityPrimaryKeyInSet epkis) {
				return epkis.getPrimaryKeys();
			} else {
				throw new IllegalArgumentException("Unsupported constraint in facet filter: " + child);
			}
		}
		return new int[0];
	}

	/**
	 * Creates the default facet extraction predicate from the query's filter constraints.
	 * Returns a function that checks whether a given facet (identified by reference name
	 * and facet ID) was requested in the query's `facetHaving` constraints.
	 *
	 * @param query the query to extract facet having constraints from
	 * @return a bi-function returning `true` if the facet was requested in the query
	 */
	@Nonnull
	private static BiFunction<String, Integer, Boolean> createDefaultFacetExtractPredicate(@Nonnull Query query) {
		final List<FacetHaving> facetHavingConstraints = ofNullable(query.getFilterBy())
			.map(it -> findConstraints(it, FacetHaving.class))
			.orElse(Collections.emptyList());
		return (referenceName, facetId) -> facetHavingConstraints
			.stream()
			.anyMatch(facetHaving -> {
				if (!referenceName.equals(facetHaving.getReferenceName())) {
					return false;
				} else {
					return Arrays.stream(extractFacetIds(facetHaving)).anyMatch(theFacetId -> facetId == theFacetId);
				}
			});
	}

	/**
	 * Extracts facet group relation settings (conjunction, disjunction, negation, exclusivity)
	 * from the query's require constraints for the given constraint type.
	 *
	 * @param entitySchema   the entity schema for resolving reference names
	 * @param query          the query to extract settings from
	 * @param constraintType the type of facet group constraint to look for
	 * @param <T>            the constraint type
	 * @return a map from relation level to the set of group references matching that level
	 */
	@Nonnull
	private static <T extends FacetGroupsConstraint> Map<FacetGroupRelationLevel, Set<GroupReference>> extractSettingsFor(
		@Nonnull EntitySchemaContract entitySchema,
		@Nonnull Query query,
		@Nonnull Class<T> constraintType
	) {
		return findRequires(query, constraintType)
			.stream()
			.collect(
				Collectors.groupingBy(
					FacetGroupsConstraint::getFacetGroupRelationLevel,
					Collectors.flatMapping(
						it -> {
							if (ArrayUtils.isEmpty(extractFacetIds(it.getFacetGroups().orElseThrow()))) {
								return Stream.of(
									new GroupReference(
										entitySchema.getReferenceOrThrowException(it.getReferenceName()),
										null
									));
							} else {
								return Arrays.stream(extractFacetIds(it.getFacetGroups().orElseThrow()))
									.mapToObj(x -> new GroupReference(
										entitySchema.getReferenceOrThrowException(it.getReferenceName()), x));
							}
						},
						Collectors.toSet()
					)
				)
			);
	}

	/**
	 * Constructs a new facet computational context from the entity schema, query, and
	 * parameter group mapping. Analyzes the query constraints to determine facet relation
	 * types and builds the initial set of facet predicates.
	 *
	 * @param entitySchema          the entity schema defining references
	 * @param query                 the query containing facet filter and require constraints
	 * @param parameterGroupMapping mapping from parameter facet ID to its group ID
	 * @param selectedFacetProvider optional provider of pre-selected facet IDs per reference name
	 */
	FacetComputationalContext(
		@Nonnull EntitySchemaContract entitySchema,
		@Nonnull Query query,
		@Nonnull Map<Integer, Integer> parameterGroupMapping,
		@Nullable Function<String, int[]> selectedFacetProvider
	) {
		this.entitySchema = entitySchema;
		this.query = query;
		this.parameterGroupMapping = parameterGroupMapping;
		final Optional<FacetCalculationRules> calculationRules = ofNullable(
			findRequire(query, FacetCalculationRules.class));
		this.defaultFacetRelationType = calculationRules.map(FacetCalculationRules::getFacetsWithSameGroupRelationType)
			.orElse(FacetRelationType.DISJUNCTION);
		this.defaultGroupRelationType = calculationRules.map(
			FacetCalculationRules::getFacetsWithDifferentGroupsRelationType).orElse(FacetRelationType.CONJUNCTION);
		this.conjugatedGroups = extractSettingsFor(entitySchema, query, FacetGroupsConjunction.class);
		this.disjugatedGroups = extractSettingsFor(entitySchema, query, FacetGroupsDisjunction.class);
		this.negatedGroups = extractSettingsFor(entitySchema, query, FacetGroupsNegation.class);
		this.exclusiveGroups = extractSettingsFor(entitySchema, query, FacetGroupsExclusivity.class);
		// create function that allows to create predicate that returns true if specified facet was part of input query filter
		this.facetSelectionPredicate = selectedFacetProvider == null ?
			createDefaultFacetExtractPredicate(query) :
			(referenceName, facetId) -> ArrayUtils.contains(selectedFacetProvider.apply(referenceName), facetId);

		// create predicates that can filter along facet constraints in current query
		this.existingFacetPredicates = computeExistingFacetPredicates(
			query.getFilterBy(), entitySchema, selectedFacetProvider);
	}

	/**
	 * Creates the base facet predicate that combines all existing facet constraints
	 * from the query into a single composite predicate.
	 *
	 * @return a predicate matching entities that satisfy all query facet constraints
	 */
	@Nonnull
	Predicate<? super SealedEntity> createBaseFacetPredicate() {
		return combineFacetsIntoPredicate(this.existingFacetPredicates);
	}

	/**
	 * Creates a predicate like [createBaseFacetPredicate] but excludes the facet group
	 * containing the specified facet and replaces it with a fresh single-facet predicate.
	 * Used for "has sense" calculation in facet impact.
	 *
	 * @param facet the reference key of the facet whose group should be replaced
	 * @return a predicate with the facet's group replaced by a single-facet predicate
	 */
	@Nonnull
	Predicate<? super SealedEntity> createBaseFacetPredicateWithoutGroupOfFacet(@Nonnull ReferenceKey facet) {
		final Predicate<FacetPredicate> matchTypeAndGroup = it -> Objects.equals(
			facet.referenceName(), it.referenceSchema().getName()) &&
			Objects.equals(getGroup(facet), it.facetGroupId());

		return combineFacetsIntoPredicate(
			Stream.concat(
				Stream.of(
					// create brand new predicate
					createFacetGroupPredicate(
						this.entitySchema.getReferenceOrThrowException(facet.referenceName()),
						getGroup(facet),
						facet.primaryKey()
					)
				),
				// use all previous facet predicates that doesn't match this facet type and group
				this.existingFacetPredicates
					.stream()
					.filter(matchTypeAndGroup.negate())
			).toList()
		);
	}

	/**
	 * Creates a test predicate that adds the specified facet to the existing constraints.
	 * If the facet's group already has a predicate, it combines them; otherwise creates a new one.
	 * Used for computing the impact of selecting an additional facet.
	 *
	 * @param facet the reference key of the facet to add to the predicate
	 * @return a predicate representing the result of selecting this additional facet
	 */
	@Nonnull
	Predicate<? super SealedEntity> createTestFacetPredicate(@Nonnull ReferenceKey facet) {
		final Predicate<FacetPredicate> matchTypeAndGroup = it -> Objects.equals(
			facet.referenceName(), it.referenceSchema().getName()) &&
			Objects.equals(getGroup(facet), it.facetGroupId());

		// alter existing facet predicate by adding new OR facet id or create new facet predicate for current facet
		final FacetPredicate currentFacetGroupPredicate = this.existingFacetPredicates
			.stream()
			.filter(matchTypeAndGroup)
			.findFirst()
			.map(
				it -> it.combine(
					this.entitySchema.getReferenceOrThrowException(facet.referenceName()), getGroup(facet),
					facet.primaryKey()
				))
			.orElseGet(() ->
				           createFacetGroupPredicate(
					           this.entitySchema.getReferenceOrThrowException(facet.referenceName()),
					           getGroup(facet),
					           facet.primaryKey()
				           )
			);
		// use all previous facet predicates that don't match this facet type and group
		final Stream<FacetPredicate> otherFacetGroupPredicates = this.existingFacetPredicates
			.stream()
			.filter(matchTypeAndGroup.negate());

		if (isExclusiveAmongOtherGroups(currentFacetGroupPredicate)) {
			// use only this facet group predicate - the group is exclusive on group level
			return currentFacetGroupPredicate;
		} else {
			// now create combined predicate upon it
			return combineFacetsIntoPredicate(
				Stream.concat(
					otherFacetGroupPredicates,
					Stream.of(currentFacetGroupPredicate)
				).collect(toList())
			);
		}
	}

	/**
	 * Checks whether the specified facet was part of the original query filter.
	 *
	 * @param facet the reference key to check
	 * @return `true` if the facet was requested in the query
	 */
	boolean wasFacetRequested(@Nonnull ReferenceKey facet) {
		return ofNullable(this.query.getFilterBy())
			.map(fb -> this.facetSelectionPredicate.apply(facet.referenceName(), facet.primaryKey()))
			.orElse(false);
	}

	/**
	 * Checks whether any facet group is negated at the specified relation level.
	 *
	 * @param level the relation level to check
	 * @return `true` if at least one group is negated
	 */
	boolean isAnyFacetGroupNegated(@Nonnull FacetGroupRelationLevel level) {
		return !this.negatedGroups
			.getOrDefault(level, Collections.emptySet())
			.isEmpty();
	}

	/**
	 * Checks whether the specified group reference is negated at the given relation level.
	 *
	 * @param groupReference the group reference to check
	 * @param level          the relation level to check
	 * @return `true` if the group is negated
	 */
	boolean isFacetGroupNegated(@Nonnull GroupReference groupReference, @Nonnull FacetGroupRelationLevel level) {
		return this.negatedGroups
			.getOrDefault(level, Collections.emptySet())
			.contains(groupReference);
	}

	/**
	 * Checks whether the specified group reference is exclusive at the given relation level.
	 *
	 * @param groupReference the group reference to check
	 * @param level          the relation level to check
	 * @return `true` if the group is exclusive
	 */
	boolean isFacetGroupExclusive(@Nonnull GroupReference groupReference, @Nonnull FacetGroupRelationLevel level) {
		return this.exclusiveGroups
			.getOrDefault(level, Collections.emptySet())
			.contains(groupReference);
	}

	/**
	 * Checks whether the specified group reference is conjugated at the given relation level.
	 *
	 * @param groupReference the group reference to check
	 * @param level          the relation level to check
	 * @return `true` if the group is conjugated (AND)
	 */
	boolean isFacetGroupConjugated(@Nonnull GroupReference groupReference, @Nonnull FacetGroupRelationLevel level) {
		return this.conjugatedGroups
			.getOrDefault(level, Collections.emptySet())
			.contains(groupReference);
	}

	/**
	 * Checks whether the specified group reference is disjugated at the given relation level.
	 *
	 * @param groupReference the group reference to check
	 * @param level          the relation level to check
	 * @return `true` if the group is disjugated (OR)
	 */
	boolean isFacetGroupDisjugated(@Nonnull GroupReference groupReference, @Nonnull FacetGroupRelationLevel level) {
		return this.disjugatedGroups
			.getOrDefault(level, Collections.emptySet())
			.contains(groupReference);
	}

	/**
	 * Checks whether the given facet predicate's group is exclusive at the
	 * {@link FacetGroupRelationLevel#WITH_DIFFERENT_GROUPS} level. Falls back to the default
	 * group relation type when no explicit setting exists.
	 *
	 * @param currentFacetGroupPredicate the facet predicate whose group to check
	 * @return `true` if the group is exclusive among other groups
	 */
	private boolean isExclusiveAmongOtherGroups(@Nonnull FacetPredicate currentFacetGroupPredicate) {
		final GroupReference groupReference = new GroupReference(
			currentFacetGroupPredicate.referenceSchema(),
			currentFacetGroupPredicate.facetGroupId()
		);
		if (this.exclusiveGroups.getOrDefault(WITH_DIFFERENT_GROUPS, Collections.emptySet())
			.contains(groupReference)) {
			return true;
		} else if (
			// check defaults if there is no specific mapping
			Stream.of(
				this.conjugatedGroups,
				this.disjugatedGroups,
				this.negatedGroups
			).noneMatch(
				it -> it
					.getOrDefault(WITH_DIFFERENT_GROUPS, Collections.emptySet())
					.contains(groupReference)
			)
		) {
			return this.defaultGroupRelationType == FacetRelationType.EXCLUSIVITY;
		} else {
			return false;
		}
	}

	/**
	 * Resolves the group ID for a given facet reference key. Returns the mapped group ID
	 * from {@link #parameterGroupMapping} for `PARAMETER` references, or `null` for all others.
	 *
	 * @param facet the reference key to resolve the group for
	 * @return the group primary key, or `null` if not a parameter reference
	 */
	@Nullable
	private Integer getGroup(@Nonnull ReferenceKey facet) {
		return Entities.PARAMETER.equals(facet.referenceName()) ?
			this.parameterGroupMapping.get(facet.primaryKey()) : null;
	}

	/**
	 * Computes the list of facet predicates from the query's existing {@link FacetHaving} filter
	 * constraints. For `PARAMETER` references, facets are grouped by their group ID before
	 * creating predicates; other references produce a single ungrouped predicate each.
	 *
	 * @param filterBy              the filter by constraint to scan for facet having constraints
	 * @param entitySchema          the entity schema for resolving reference names
	 * @param selectedFacetProvider optional provider of pre-selected facet IDs per reference name
	 * @return a list of facet predicates representing the existing query constraints
	 */
	@Nonnull
	private List<FacetPredicate> computeExistingFacetPredicates(
		@Nullable FilterBy filterBy,
		@Nonnull EntitySchemaContract entitySchema,
		@Nullable Function<String, int[]> selectedFacetProvider
	) {
		final List<FacetPredicate> userFilterPredicates = new LinkedList<>();
		if (filterBy != null) {
			for (FacetHaving facetHavingFilter : findConstraints(filterBy, FacetHaving.class)) {
				final int[] selectedFacets = selectedFacetProvider == null ?
					extractFacetIds(facetHavingFilter) :
					selectedFacetProvider.apply(facetHavingFilter.getReferenceName());

				if (Entities.PARAMETER.equals(facetHavingFilter.getReferenceName())) {
					final Map<Integer, List<Integer>> groupedFacets = Arrays.stream(selectedFacets)
						.boxed()
						.collect(
							groupingBy(this.parameterGroupMapping::get)
						);
					groupedFacets
						.forEach((facetGroupId, facetIdList) -> {
							final int[] facetIds = facetIdList.stream().mapToInt(it -> it).toArray();
							userFilterPredicates.add(
								createFacetGroupPredicate(
									entitySchema.getReferenceOrThrowException(facetHavingFilter.getReferenceName()),
									facetGroupId,
									facetIds
								)
							);
						});
				} else {
					userFilterPredicates.add(
						createFacetGroupPredicate(
							entitySchema.getReferenceOrThrowException(facetHavingFilter.getReferenceName()),
							null,
							selectedFacets
						)
					);
				}
			}
		}
		return userFilterPredicates;
	}

	/**
	 * Creates a {@link FacetPredicate} for a specific reference schema and group, selecting the
	 * appropriate {@link FacetMatchStrategy} based on configured group relation settings or
	 * the default facet relation type.
	 *
	 * @param referenceSchema the reference schema this predicate applies to
	 * @param facetGroupId    the optional facet group ID
	 * @param facetIds        the facet IDs to match against
	 * @return a new facet predicate with the resolved matching strategy
	 */
	@Nonnull
	private FacetPredicate createFacetGroupPredicate(
		@Nonnull ReferenceSchemaContract referenceSchema,
		@Nullable Integer facetGroupId,
		int... facetIds
	) {
		final GroupReference groupReference = new GroupReference(referenceSchema, facetGroupId);
		final FacetMatchStrategy strategy;
		if (isFacetGroupConjugated(groupReference, WITH_DIFFERENT_FACETS_IN_GROUP)) {
			strategy = FacetMatchStrategy.AND;
		} else if (isFacetGroupNegated(groupReference, WITH_DIFFERENT_FACETS_IN_GROUP)) {
			strategy = FacetMatchStrategy.NOT;
		} else if (isFacetGroupExclusive(groupReference, WITH_DIFFERENT_FACETS_IN_GROUP)) {
			strategy = FacetMatchStrategy.EXCLUSIVE;
		} else if (isFacetGroupDisjugated(groupReference, WITH_DIFFERENT_FACETS_IN_GROUP)) {
			strategy = FacetMatchStrategy.OR;
		} else {
			strategy = switch (this.defaultFacetRelationType) {
				case CONJUNCTION -> FacetMatchStrategy.AND;
				case DISJUNCTION -> FacetMatchStrategy.OR;
				case NEGATION -> FacetMatchStrategy.NOT;
				case EXCLUSIVITY -> FacetMatchStrategy.EXCLUSIVE;
			};
		}
		return new FacetPredicate(strategy, referenceSchema, facetGroupId, facetIds);
	}

	/**
	 * Combines a list of facet predicates into a single composite predicate by categorizing each
	 * predicate into conjugated, disjugated, negated, or exclusive groups based on the
	 * {@link FacetGroupRelationLevel#WITH_DIFFERENT_GROUPS} configuration. Conjugated and negated
	 * predicates are combined with AND, disjugated and exclusive with OR, then all are merged
	 * into the final predicate.
	 *
	 * @param predicates the list of facet predicates to combine
	 * @return a composite predicate that applies all facet constraints
	 */
	@Nonnull
	private Predicate<SealedEntity> combineFacetsIntoPredicate(@Nonnull List<FacetPredicate> predicates) {
		final List<Predicate<SealedEntity>> disjugatedPredicates = new ArrayList<>();
		final List<Predicate<SealedEntity>> conjugatedPredicates = new ArrayList<>();
		final List<Predicate<SealedEntity>> negatedPredicates = new ArrayList<>();
		final List<Predicate<SealedEntity>> exclusivePredicates = new ArrayList<>();
		for (FacetPredicate predicate : predicates) {
			final GroupReference groupReference = new GroupReference(
				predicate.referenceSchema(), predicate.facetGroupId());
			if (isFacetGroupConjugated(groupReference, WITH_DIFFERENT_GROUPS)) {
				conjugatedPredicates.add(predicate);
			} else if (isFacetGroupNegated(groupReference, WITH_DIFFERENT_GROUPS)) {
				negatedPredicates.add(predicate);
			} else if (isFacetGroupExclusive(groupReference, WITH_DIFFERENT_GROUPS)) {
				exclusivePredicates.add(predicate);
			} else if (isFacetGroupDisjugated(groupReference, WITH_DIFFERENT_GROUPS)) {
				disjugatedPredicates.add(predicate);
			} else {
				switch (this.defaultGroupRelationType) {
					case CONJUNCTION -> conjugatedPredicates.add(predicate);
					case DISJUNCTION -> disjugatedPredicates.add(predicate);
					case NEGATION -> negatedPredicates.add(predicate);
					case EXCLUSIVITY -> exclusivePredicates.add(predicate);
				}
			}
		}

		final Optional<Predicate<SealedEntity>> disjugatedPredicate = disjugatedPredicates.stream().reduce(
			Predicate::or);
		final Optional<Predicate<SealedEntity>> conjugatedPredicate = conjugatedPredicates.stream().reduce(
			Predicate::and);
		final Optional<Predicate<SealedEntity>> negatedPredicate = negatedPredicates.stream().reduce(Predicate::and);
		final Optional<Predicate<SealedEntity>> exclusivePredicate = exclusivePredicates.stream().reduce(Predicate::or);

		Predicate<SealedEntity> resultPredicate = entity -> true;
		if (conjugatedPredicate.isPresent()) {
			resultPredicate = resultPredicate.and(conjugatedPredicate.get());
		}
		if (negatedPredicate.isPresent()) {
			resultPredicate = resultPredicate.and(negatedPredicate.get());
		}
		if (exclusivePredicate.isPresent()) {
			// exclusivity must be enforced on client level - it's too late here, so we fall back to system default - and
			resultPredicate = resultPredicate.and(exclusivePredicate.get());
		}
		if (disjugatedPredicate.isPresent()) {
			resultPredicate = resultPredicate.or(disjugatedPredicate.get());
		}

		return resultPredicate;
	}

}
