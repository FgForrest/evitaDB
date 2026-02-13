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

package io.evitadb.api.requestResponse.schema.builder;

import io.evitadb.api.exception.AttributeAlreadyPresentInEntitySchemaException;
import io.evitadb.api.exception.InvalidSchemaMutationException;
import io.evitadb.api.exception.SortableAttributeCompoundSchemaException;
import io.evitadb.api.requestResponse.schema.AttributeSchemaContract;
import io.evitadb.api.requestResponse.schema.CatalogSchemaContract;
import io.evitadb.api.requestResponse.schema.CatalogSchemaEditor;
import io.evitadb.api.requestResponse.schema.EntitySchemaContract;
import io.evitadb.api.requestResponse.schema.EntitySchemaEditor;
import io.evitadb.api.requestResponse.schema.NamedSchemaContract;
import io.evitadb.api.requestResponse.schema.ReferenceSchemaContract;
import io.evitadb.api.requestResponse.schema.SortableAttributeCompoundSchemaContract;
import io.evitadb.api.requestResponse.schema.SortableAttributeCompoundSchemaContract.AttributeElement;
import io.evitadb.api.requestResponse.schema.builder.InternalEntitySchemaBuilder.AttributeNamingConventionConflict;
import io.evitadb.api.requestResponse.schema.dto.ReferenceIndexType;
import io.evitadb.api.requestResponse.schema.mutation.CombinableCatalogSchemaMutation;
import io.evitadb.api.requestResponse.schema.mutation.CombinableLocalEntitySchemaMutation;
import io.evitadb.api.requestResponse.schema.mutation.LocalCatalogSchemaMutation;
import io.evitadb.api.requestResponse.schema.mutation.LocalEntitySchemaMutation;
import io.evitadb.api.requestResponse.schema.mutation.SchemaMutation;
import io.evitadb.api.requestResponse.schema.mutation.reference.ModifyReferenceAttributeSchemaMutation;
import io.evitadb.api.requestResponse.schema.mutation.reference.ScopedReferenceIndexType;
import io.evitadb.api.requestResponse.schema.mutation.reference.SetReferenceSchemaIndexedMutation;
import io.evitadb.dataType.Scope;
import io.evitadb.utils.ArrayUtils;
import io.evitadb.utils.Assert;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.lang.reflect.Array;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.stream.Stream;

/**
 * Interface contains shared logic both for {@link CatalogSchemaEditor} and {@link EntitySchemaEditor}.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2022
 */
public interface InternalSchemaBuilderHelper {

	/**
	 * Method does the real heavy lifting of adding the mutation to the existing set of mutations.
	 * This method is quite slow - it has addition factorial complexity O(n!+). For each added mutation we need to
	 * compute combination result with all previous mutations.
	 *
	 * @param mutationType      type class required for Java generics array creation
	 * @param combiner          function that combines an existing mutation with a new mutation
	 * @param existingMutations set of existing mutations in the pipeline
	 * @param newMutations      array of new mutations we want to add to the pipeline
	 * @param <T>               mutation type parameter required by Java generics constraints
	 * @return mutation impact to signalize that the pipeline was modified and the cached schema needs to be recalculated
	 */
	@Nonnull
	@SafeVarargs
	private static <T extends SchemaMutation> MutationImpact addMutations(
		@Nonnull Class<T> mutationType,
		@Nonnull BiFunction<T, T, MutationCombinationResult<T>> combiner,
		@Nonnull List<T> existingMutations,
		@Nonnull T... newMutations
	) {
		MutationImpact schemaUpdated = MutationImpact.NO_IMPACT;
		// go through all new mutations
		for (T newMutation : newMutations) {
			if (newMutation instanceof CombinableLocalEntitySchemaMutation || newMutation instanceof CombinableCatalogSchemaMutation) {
				final List<MutationReplacement<T>> replacements = new LinkedList<>();
				// for each - traverse all existing mutations
				@SuppressWarnings("unchecked")
				T[] mutationsToExamine = (T[]) Array.newInstance(mutationType, 1);
				mutationsToExamine[0] = newMutation;

				do {
					final T[] mutationsToGoThrough = Arrays.copyOf(mutationsToExamine, mutationsToExamine.length);
					mutationsToExamine = null;
					boolean discarded = false;
					for (int i = 0; i < mutationsToGoThrough.length; i++) {
						T examinedMutation = mutationsToGoThrough[i];
						final Iterator<T> existingIt = existingMutations.iterator();
						int index = -1;
						while (existingIt.hasNext()) {
							index++;
							final T existingMutation = existingIt.next();
							// and try to combine them together
							final MutationCombinationResult<T> combinationResult = combiner.apply(
								existingMutation, examinedMutation
							);
							if (combinationResult != null) {
								discarded = combinationResult.discarded();
								// now check the result
								if (combinationResult.origin() == null) {
									// or we may find out that the new mutation makes previous mutation obsolete
									existingIt.remove();
									index--;
									schemaUpdated = updateMutationImpactInternal(schemaUpdated, MutationImpact.MODIFIED_PREVIOUS);
									examinedMutation = null;
								} else if (combinationResult.origin() != existingMutation) {
									// or we may find out that the new mutation makes previous mutation partially obsolete
									replacements.add(new MutationReplacement<>(index, combinationResult.origin()));
									examinedMutation = null;
								}
								// we may find out that the new mutation is not necessary, or partially not necessary
								if (ArrayUtils.isEmpty(combinationResult.current())) {
									break;
								} else if (combinationResult.current().length == 1 && combinationResult.current()[0] == examinedMutation) {
									// continue with this mutation
								} else {
									//noinspection unchecked
									mutationsToExamine = ArrayUtils.mergeArrays(
										Arrays.copyOfRange(mutationsToGoThrough, i + 1, mutationsToGoThrough.length),
										combinationResult.current()
									);
									break;
								}
							}
						}
						if (!discarded) {
							// replace all partially obsolete existing mutations outside the loop to avoid ConcurrentModificationException
							for (MutationReplacement<T> replacement : replacements) {
								existingMutations.set(replacement.index(), replacement.replaceMutation());
								schemaUpdated = updateMutationImpactInternal(schemaUpdated, MutationImpact.ADDED);
							}
							// clear applied replacements
							replacements.clear();
							// and if the new mutation still applies, append it to the end
							if (examinedMutation != null) {
								existingMutations.add(examinedMutation);
								schemaUpdated = updateMutationImpactInternal(schemaUpdated, MutationImpact.ADDED);
							}
						}
					}
				} while (mutationsToExamine != null);
			} else {
				existingMutations.add(newMutation);
				schemaUpdated = updateMutationImpactInternal(schemaUpdated, MutationImpact.ADDED);
			}
		}
		return schemaUpdated;
	}

	/**
	 * Method updates the impact of the mutation on the schema but only if the impact is more significant than
	 * the existing one.
	 *
	 * @param existingImpactLevel the existing impact level
	 * @param newImpactLevel      the new impact level
	 * @return the new impact level if more significant than the existing one, otherwise the existing one
	 */
	@Nonnull
	private static MutationImpact updateMutationImpactInternal(@Nonnull MutationImpact existingImpactLevel, @Nonnull MutationImpact newImpactLevel) {
		if (existingImpactLevel.ordinal() < newImpactLevel.ordinal()) {
			existingImpactLevel = newImpactLevel;
		}
		return existingImpactLevel;
	}

	/**
	 * Sorts mutations in a list so that {@link ModifyReferenceAttributeSchemaMutation} instances
	 * always come last. This ensures that reference schema properties (such as faceted/indexed)
	 * are applied before attribute mutations.
	 *
	 * @param mutations the list of mutations to sort (modified in place)
	 */
	default void sortReferenceAttributeMutationsLast(
		@Nonnull List<LocalEntitySchemaMutation> mutations
	) {
		final Iterator<LocalEntitySchemaMutation> it = mutations.iterator();
		final List<LocalEntitySchemaMutation> movedMutations = new LinkedList<>();
		while (it.hasNext()) {
			final LocalEntitySchemaMutation mutation = it.next();
			if (mutation instanceof ModifyReferenceAttributeSchemaMutation) {
				it.remove();
				movedMutations.add(mutation);
			}
		}
		mutations.addAll(movedMutations);
	}

	/**
	 * This method is quite slow - it has addition factorial complexity O(n!+). For each added mutation we need to
	 * compute combination result with all previous mutations.
	 */
	@Nonnull
	default MutationImpact addMutations(
		@Nonnull CatalogSchemaContract currentCatalogSchema,
		@Nonnull List<LocalCatalogSchemaMutation> existingMutations,
		@Nonnull LocalCatalogSchemaMutation... newMutations
	) {
		return addMutations(
			LocalCatalogSchemaMutation.class,
			(existingMutation, newMutation) -> ((CombinableCatalogSchemaMutation) newMutation)
				.combineWith(currentCatalogSchema, existingMutation),
			existingMutations,
			newMutations
		);
	}

	/**
	 * This method is quite slow - it has addition factorial complexity O(n!+). For each added mutation we need to
	 * compute combination result with all previous mutations.
	 */
	@Nonnull
	default MutationImpact addMutations(
		@Nonnull CatalogSchemaContract currentCatalogSchema,
		@Nonnull EntitySchemaContract currentEntitySchema,
		@Nonnull List<LocalEntitySchemaMutation> existingMutations,
		@Nonnull LocalEntitySchemaMutation... newMutations
	) {
		return addMutations(
			LocalEntitySchemaMutation.class,
			(existingMutation, newMutation) -> ((CombinableLocalEntitySchemaMutation) newMutation)
				.combineWith(currentCatalogSchema, currentEntitySchema, existingMutation),
			existingMutations,
			newMutations
		);
	}

	/**
	 * Method updates the impact of the mutation on the schema but only if the impact is more significant than
	 * the existing one.
	 *
	 * @param existingImpactLevel the existing impact level
	 * @param newImpactLevel      the new impact level
	 * @return the new impact level if more significant than the existing one, otherwise the existing one
	 */
	@Nonnull
	default MutationImpact updateMutationImpact(
		@Nonnull MutationImpact existingImpactLevel,
		@Nonnull MutationImpact newImpactLevel
	) {
		return updateMutationImpactInternal(
			existingImpactLevel,
			newImpactLevel
		);
	}

	/**
	 * Method checks that the attribute schema has all its possible variants for all {@link io.evitadb.utils.NamingConvention}
	 * unique among all other attribute schemas on the same level of the parent schema.
	 */
	default void checkNamesAreUniqueInAllNamingConventions(
		@Nonnull Collection<? extends AttributeSchemaContract> attributeSchemas,
		@Nonnull Collection<? extends SortableAttributeCompoundSchemaContract> compoundSchemas,
		@Nonnull NamedSchemaContract newSchema
	) {
		Stream.concat(
				attributeSchemas
					.stream()
					.filter(it -> !(Objects.equals(it.getName(), newSchema.getName()) && newSchema instanceof AttributeSchemaContract))
					.flatMap(it -> it.getNameVariants()
						.entrySet()
						.stream()
						.filter(nameVariant -> nameVariant.getValue().equals(newSchema.getNameVariant(nameVariant.getKey())))
						.map(
							nameVariant -> new AttributeNamingConventionConflict(
								it, null, nameVariant.getKey(), nameVariant.getValue()
							)
						)
					),
				compoundSchemas
					.stream()
					.filter(it -> !(Objects.equals(it.getName(), newSchema.getName()) && newSchema instanceof SortableAttributeCompoundSchemaContract))
					.flatMap(it -> it.getNameVariants()
						.entrySet()
						.stream()
						.filter(nameVariant -> nameVariant.getValue().equals(newSchema.getNameVariant(nameVariant.getKey())))
						.map(
							nameVariant -> new AttributeNamingConventionConflict(
								null, it, nameVariant.getKey(), nameVariant.getValue()
							)
						)
					)
			)
			.forEach(conflict -> {
				if (newSchema instanceof AttributeSchemaContract newAttributeSchema) {
					if (conflict.conflictingAttributeSchema() == null) {
						throw new AttributeAlreadyPresentInEntitySchemaException(
							Objects.requireNonNull(conflict.conflictingCompoundSchema()),
							newAttributeSchema,
							conflict.convention(), conflict.conflictingName()
						);
					} else {
						throw new AttributeAlreadyPresentInEntitySchemaException(
							conflict.conflictingAttributeSchema(),
							newAttributeSchema,
							conflict.convention(), conflict.conflictingName()
						);
					}
				} else if (newSchema instanceof SortableAttributeCompoundSchemaContract newCompoundSchema) {
					if (conflict.conflictingAttributeSchema() == null) {
						throw new AttributeAlreadyPresentInEntitySchemaException(
							Objects.requireNonNull(conflict.conflictingCompoundSchema()),
							newCompoundSchema,
							conflict.convention(), conflict.conflictingName()
						);
					} else {
						throw new AttributeAlreadyPresentInEntitySchemaException(
							conflict.conflictingAttributeSchema(),
							newCompoundSchema,
							conflict.convention(), conflict.conflictingName()
						);
					}
				} else {
					throw new IllegalStateException("Should not be possible");
				}
			});
	}

	/**
	 * Method checks whether there is any sortable attribute compound using attribute with particular name and
	 * throws {@link SortableAttributeCompoundSchemaException} if it does.
	 *
	 * @throws SortableAttributeCompoundSchemaException when there is sortable attribute compound using attribute
	 */
	default void checkSortableAttributeCompoundsWithoutAttribute(
		@Nonnull String attributeName,
		@Nonnull Collection<? extends SortableAttributeCompoundSchemaContract> sortableAttributeCompounds
	) {
		final SortableAttributeCompoundSchemaContract conflictingCompounds = sortableAttributeCompounds
			.stream()
			.filter(
				it -> it.getAttributeElements()
					.stream()
					.anyMatch(attr -> attributeName.equals(attr.attributeName()))
			)
			.findFirst()
			.orElse(null);

		Assert.isTrue(
			conflictingCompounds == null,
			() -> new SortableAttributeCompoundSchemaException(
				"The attribute `" + attributeName + "` cannot be removed because there is sortable attribute compound" +
					" relying on it! Please, remove the compound first. ",
				Objects.requireNonNull(conflictingCompounds)
			)
		);
	}

	/**
	 * Method checks whether the sortable attribute is not an array type. It's not possible to sort entities that would
	 * provide multiple values to sort by.
	 */
	default void checkSortableTraits(@Nonnull String attributeName, @Nonnull AttributeSchemaContract attributeSchema) {
		if (attributeSchema.isSortableInAnyScope()) {
			Assert.isTrue(
				!attributeSchema.getType().isArray(),
				() -> new InvalidSchemaMutationException(
					"Attribute " + attributeName + " is marked as sortable and thus cannot be the array of " +
						attributeSchema.getType() + "!"
				)
			);
		}
	}

	/**
	 * Method checks whether the sortable attribute is not an array type. It's not possible to sort entities that would
	 * provide multiple values to sort by.
	 */
	default void checkSortableTraits(
		@Nonnull String compoundSchemaName,
		@Nonnull SortableAttributeCompoundSchemaContract compoundSchemaContract,
		@Nonnull Map<String, ? extends AttributeSchemaContract> attributeSchemas
	) {
		for (AttributeElement attributeElement : compoundSchemaContract.getAttributeElements()) {
			final AttributeSchemaContract attributeSchema = attributeSchemas.get(attributeElement.attributeName());
			if (attributeSchema == null) {
				throw new SortableAttributeCompoundSchemaException(
					"Attribute `" + attributeElement.attributeName() + "` the sortable attribute compound" +
						" `" + compoundSchemaName + "` consists of doesn't exist!",
					compoundSchemaContract
				);
			} else {
				Assert.isTrue(
					!attributeSchema.getType().isArray(),
					() -> new InvalidSchemaMutationException(
						"Attribute `" + attributeElement.attributeName() + "` the sortable attribute compound" +
							" `" + compoundSchemaName + "` consists of cannot be the array of " +
							attributeSchema.getType() + "!"
					)
				);
			}
		}
	}

	/**
	 * Shared logic for adding a sortable attribute compound to a reference schema.
	 * Used by both {@link ReferenceSchemaBuilder} and {@link ReflectedReferenceSchemaBuilder}
	 * to avoid code duplication.
	 *
	 * @param catalogSchema       the catalog schema
	 * @param entitySchema        the entity schema
	 * @param referenceSchema     the current reference schema (the builder's live view)
	 * @param baseReferenceSchema the base reference schema (for looking up existing compounds)
	 * @param mutations           the mutation list to append to
	 * @param currentDirty        the current mutation impact
	 * @param compoundName        the name of the compound to add
	 * @param attributeElements   the attribute elements of the compound
	 * @param whichIs             optional consumer to further configure the compound
	 * @return the updated mutation impact
	 */
	default MutationImpact addSortableAttributeCompoundToReference(
		@Nonnull CatalogSchemaContract catalogSchema,
		@Nonnull EntitySchemaContract entitySchema,
		@Nonnull ReferenceSchemaContract referenceSchema,
		@Nonnull ReferenceSchemaContract baseReferenceSchema,
		@Nonnull List<LocalEntitySchemaMutation> mutations,
		@Nonnull MutationImpact currentDirty,
		@Nonnull String compoundName,
		@Nonnull AttributeElement[] attributeElements,
		@Nullable Consumer<SortableAttributeCompoundSchemaBuilder> whichIs
	) {
		final Optional<SortableAttributeCompoundSchemaContract> existingCompound =
			referenceSchema.getSortableAttributeCompound(compoundName);
		final SortableAttributeCompoundSchemaBuilder builder = new SortableAttributeCompoundSchemaBuilder(
			catalogSchema,
			entitySchema,
			referenceSchema,
			baseReferenceSchema.getSortableAttributeCompound(compoundName).orElse(null),
			compoundName,
			Arrays.asList(attributeElements),
			Collections.emptyList(),
			true
		);
		final SortableAttributeCompoundSchemaBuilder schemaBuilder =
			existingCompound
				.map(it -> {
					Assert.isTrue(
						it.getAttributeElements().equals(Arrays.asList(attributeElements)),
						() -> new AttributeAlreadyPresentInEntitySchemaException(
							it, builder.toInstance(), null, compoundName
						)
					);
					return builder;
				})
				.orElse(builder);

		if (whichIs != null) {
			whichIs.accept(schemaBuilder);
		}
		final SortableAttributeCompoundSchemaContract compoundSchema = schemaBuilder.toInstance();
		validateSortableAttributeCompound(
			compoundName, compoundSchema, referenceSchema.getAttributes(),
			referenceSchema.getSortableAttributeCompounds().values()
		);

		if (existingCompound.map(it -> !it.equals(compoundSchema)).orElse(true)) {
			return updateMutationImpact(
				currentDirty,
				addMutations(
					catalogSchema, entitySchema, mutations,
					schemaBuilder
						.toReferenceMutation(referenceSchema.getName())
						.stream()
						.map(LocalEntitySchemaMutation.class::cast)
						.toArray(LocalEntitySchemaMutation[]::new)
				)
			);
		}
		return currentDirty;
	}

	/**
	 * Shared logic for indexing a reference for a specific {@link ReferenceIndexType} in given scopes.
	 * Used by both {@link ReferenceSchemaBuilder} and {@link ReflectedReferenceSchemaBuilder}
	 * to implement {@code indexedForFilteringInScope} and {@code indexedForFilteringAndPartitioningInScope}.
	 *
	 * @param catalogSchema the catalog schema
	 * @param entitySchema  the entity schema
	 * @param mutations     the mutation list to append to
	 * @param currentDirty  the current mutation impact
	 * @param referenceName the name of the reference
	 * @param indexType     the type of reference index to apply
	 * @param inScope       the scopes to apply the index in
	 * @return the updated mutation impact
	 */
	default MutationImpact indexedForTypeInScope(
		@Nonnull CatalogSchemaContract catalogSchema,
		@Nonnull EntitySchemaContract entitySchema,
		@Nonnull List<LocalEntitySchemaMutation> mutations,
		@Nonnull MutationImpact currentDirty,
		@Nonnull String referenceName,
		@Nonnull ReferenceIndexType indexType,
		@Nonnull Scope... inScope
	) {
		final ScopedReferenceIndexType[] scopedIndexTypes = Arrays.stream(inScope)
			.map(scope -> new ScopedReferenceIndexType(scope, indexType))
			.toArray(ScopedReferenceIndexType[]::new);

		return updateMutationImpact(
			currentDirty,
			addMutations(
				catalogSchema, entitySchema, mutations,
				new SetReferenceSchemaIndexedMutation(referenceName, scopedIndexTypes)
			)
		);
	}

	/**
	 * Validates a sortable attribute compound schema against business rules:
	 *
	 * - the compound must contain more than one attribute element
	 * - all attribute names within the compound must be unique
	 * - all referenced attributes must have valid sortable traits
	 * - names must be unique across all naming conventions
	 *
	 * @param compoundName   the name of the sortable attribute compound
	 * @param compoundSchema the compound schema to validate
	 * @param attributes     available attribute schemas (used for sortable trait checks)
	 * @param compounds      existing compound schemas (used for naming uniqueness checks)
	 * @throws SortableAttributeCompoundSchemaException if any validation fails
	 */
	default void validateSortableAttributeCompound(
		@Nonnull String compoundName,
		@Nonnull SortableAttributeCompoundSchemaContract compoundSchema,
		@Nonnull Map<String, ? extends AttributeSchemaContract> attributes,
		@Nonnull Collection<? extends SortableAttributeCompoundSchemaContract> compounds
	) {
		Assert.isTrue(
			compoundSchema.getAttributeElements().size() > 1,
			() -> new SortableAttributeCompoundSchemaException(
				"Sortable attribute compound requires more than one attribute element!",
				compoundSchema
			)
		);
		Assert.isTrue(
			compoundSchema.getAttributeElements().size() ==
				compoundSchema.getAttributeElements()
					.stream()
					.map(AttributeElement::attributeName)
					.distinct()
					.count(),
			() -> new SortableAttributeCompoundSchemaException(
				"Attribute names of elements in sortable attribute compound must be unique!",
				compoundSchema
			)
		);
		checkSortableTraits(compoundName, compoundSchema, attributes);
		checkNamesAreUniqueInAllNamingConventions(
			attributes.values(), compounds, compoundSchema
		);
	}

	/**
	 * Enum representing the impact of the mutation on the schema.
	 */
	enum MutationImpact {

		/**
		 * No mutation was added.
		 */
		NO_IMPACT,
		/**
		 * All or some of the mutations were added.
		 */
		ADDED,
		/**
		 * Some mutations might have been added, but already existing mutations were removed or replaced.
		 */
		MODIFIED_PREVIOUS

	}

	/**
	 * Internal record enveloping information for replacing existing mutation in the pipeline with different one.
	 *
	 * @param index           the index where the existing mutation should be exchanged
	 * @param replaceMutation the mutation that should replace the existing mutation
	 */
	record MutationReplacement<T extends SchemaMutation>(
		int index,
		@Nonnull T replaceMutation
	) {
	}

	/**
	 * Record representing the order for changing the existing mutation pipeline with different contents.
	 *
	 * @param discarded if set to TRUE it continues to be combining `current` mutation  with other existing mutations,
	 *                  but finally it discards it and does not append it to the list of mutations
	 * @param origin    represents the new mutation that should be used instead of existing mutation, NULL means that
	 *                  existing mutation should be discarded without any compensation
	 * @param current   represents the new mutation set that should be appended instead the currently added inserted
	 *                  mutation, NULL means that the added mutation should be discarded completely, multiple mutation
	 *                  set means that the current mutation should be dismantled to a pieces of which only a few should
	 *                  be added to the pipeline
	 */
	record MutationCombinationResult<T extends SchemaMutation>(
		boolean discarded,
		@Nullable T origin,
		@Nullable T... current
	) {

		@SafeVarargs
		public MutationCombinationResult(@Nullable T origin, @Nullable T... current) {
			this(false, origin, current == null || current.length == 0 || current[0] == null ? null : current);
		}

	}

}
