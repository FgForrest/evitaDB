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

package io.evitadb.api.requestResponse.schema.builder;

import io.evitadb.api.exception.AttributeAlreadyPresentInEntitySchemaException;
import io.evitadb.api.exception.InvalidSchemaMutationException;
import io.evitadb.api.requestResponse.schema.AttributeSchemaContract;
import io.evitadb.api.requestResponse.schema.CatalogSchemaContract;
import io.evitadb.api.requestResponse.schema.CatalogSchemaEditor;
import io.evitadb.api.requestResponse.schema.EntitySchemaContract;
import io.evitadb.api.requestResponse.schema.EntitySchemaEditor;
import io.evitadb.api.requestResponse.schema.builder.InternalEntitySchemaBuilder.AttributeNamingConventionConflict;
import io.evitadb.api.requestResponse.schema.mutation.CombinableCatalogSchemaMutation;
import io.evitadb.api.requestResponse.schema.mutation.CombinableEntitySchemaMutation;
import io.evitadb.api.requestResponse.schema.mutation.EntitySchemaMutation;
import io.evitadb.api.requestResponse.schema.mutation.LocalCatalogSchemaMutation;
import io.evitadb.api.requestResponse.schema.mutation.SchemaMutation;
import io.evitadb.utils.ArrayUtils;
import io.evitadb.utils.Assert;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.lang.reflect.Array;
import java.util.Arrays;
import java.util.Collection;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Objects;
import java.util.function.BiFunction;

/**
 * Interface contains shared logic both for {@link CatalogSchemaEditor} and {@link EntitySchemaEditor}.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2022
 */
public interface InternalSchemaBuilderHelper {

	/**
	 * This method is quite slow - it has addition factorial complexity O(n!+). For each added mutation we need to
	 * compute combination result with all previous mutations.
	 */
	default boolean addMutations(
		@Nonnull CatalogSchemaContract currentCatalogSchema,
		@Nonnull List<LocalCatalogSchemaMutation> existingMutations,
		@Nonnull LocalCatalogSchemaMutation... newMutations
	) {
		return addMutations(
			LocalCatalogSchemaMutation.class,
			(existingMutation, newMutation) -> ((CombinableCatalogSchemaMutation)newMutation)
				.combineWith(currentCatalogSchema, existingMutation),
			existingMutations,
			newMutations
		);
	}

	/**
	 * This method is quite slow - it has addition factorial complexity O(n!+). For each added mutation we need to
	 * compute combination result with all previous mutations.
	 */
	default boolean addMutations(
		@Nonnull CatalogSchemaContract currentCatalogSchema,
		@Nonnull EntitySchemaContract currentEntitySchema,
		@Nonnull List<EntitySchemaMutation> existingMutations,
		@Nonnull EntitySchemaMutation... newMutations
	) {
		return addMutations(
			EntitySchemaMutation.class,
			(existingMutation, newMutation) -> ((CombinableEntitySchemaMutation)newMutation)
				.combineWith(currentCatalogSchema, currentEntitySchema, existingMutation),
			existingMutations,
			newMutations
		);
	}

	/**
	 * Method does the real heavy lifting of adding the mutation to the existing set of mutations.
	 * This method is quite slow - it has addition factorial complexity O(n!+). For each added mutation we need to
	 * compute combination result with all previous mutations.
	 *
	 * @param mutationType just for the Java generics sake, damn!
	 * @param combiner again a lambda that satisfies the damned Java generics - we just need to call combineWith method
	 * @param existingMutations set of existing mutations in the pipeline
	 * @param newMutations array of new mutations we want to add to the pipeline
	 * @return TRUE if the pipeline was modified and the cached schema needs te "recalculated"
	 * @param <T> :) having Java more clever, we wouldn't need this
	 */
	@SafeVarargs
	private static <T extends SchemaMutation> boolean addMutations(
		@Nonnull Class<T> mutationType,
		@Nonnull BiFunction<T, T, MutationCombinationResult<T>> combiner,
		@Nonnull List<T> existingMutations,
		@Nonnull T... newMutations
	) {
		boolean schemaUpdated = false;
		// go through all new mutations
		for (T newMutation : newMutations) {
			if (newMutation instanceof CombinableEntitySchemaMutation || newMutation instanceof CombinableCatalogSchemaMutation) {
				final List<MutationReplacement<T>> replacements = new LinkedList<>();
				// for each - traverse all existing mutations
				@SuppressWarnings("unchecked")
				T[] mutationsToExamine = (T[]) Array.newInstance(mutationType, 1);
				mutationsToExamine[0] = newMutation;

				do {
					final T[] mutationsToGoThrough = Arrays.copyOf(mutationsToExamine, mutationsToExamine.length);
					mutationsToExamine = null;
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
								// now check the result
								if (combinationResult.origin() == null) {
									// or we may find out that the new mutation makes previous mutation obsolete
									existingIt.remove();
									index--;
									schemaUpdated = true;
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
						// replace all partially obsolete existing mutations outside the loop to avoid ConcurrentModificationException
						for (MutationReplacement<T> replacement : replacements) {
							existingMutations.set(replacement.index(), replacement.replaceMutation());
							schemaUpdated = true;
						}
						// clear applied replacements
						replacements.clear();
						// and if the new mutation still applies, append it to the end
						if (examinedMutation != null) {
							existingMutations.add(examinedMutation);
							schemaUpdated = true;
						}
					}
				} while (mutationsToExamine != null);
			} else {
				existingMutations.add(newMutation);
				schemaUpdated = true;
			}
		}
		return schemaUpdated;
	}

	/**
	 * Method checks that the attribute schema has all its possible variants for all {@link io.evitadb.utils.NamingConvention}
	 * unique among all other attribute schemas on the same level of the parent schema.
	 */
	default void checkNamesAreUniqueInAllNamingConventions(@Nonnull Collection<? extends AttributeSchemaContract> values, @Nonnull AttributeSchemaContract attributeSchema) {
		values.stream()
			.filter(it -> !Objects.equals(it.getName(), attributeSchema.getName()))
			.flatMap(it -> it.getNameVariants()
				.entrySet()
				.stream()
				.filter(nameVariant -> nameVariant.getValue().equals(attributeSchema.getNameVariant(nameVariant.getKey())))
				.map(nameVariant -> new AttributeNamingConventionConflict(it, nameVariant.getKey(), nameVariant.getValue()))
			)
			.forEach(conflict -> {
				throw new AttributeAlreadyPresentInEntitySchemaException(
					conflict.conflictingSchema(), attributeSchema,
					conflict.convention(), conflict.conflictingName()
				);
			});
	}

	/**
	 * Method checks whether the sortable attribute is not an array type. It's not possible to sort entities that would
	 * provide multiple values to sort by.
	 */
	default void checkSortableTraits(@Nonnull String attributeName, @Nonnull AttributeSchemaContract attributeSchema) {
		if (attributeSchema.isSortable()) {
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
	 * Internal record enveloping information for replacing existing mutation in the pipeline with different one.
	 * @param index the index where the existing mutation should be exchanged
	 * @param replaceMutation the mutation that should replace the existing mutation
	 * @param <T>
	 */
	record MutationReplacement<T extends SchemaMutation>(
		int index,
		@Nonnull T replaceMutation
	) {
	}

	/**
	 * Record representing the order for changing the existing mutation pipeline with different contents.
	 *
	 * @param origin represents the new mutation that should be used instead of existing mutation, NULL means that
	 *                  existing mutation should be discarded without any compensation
	 * @param current represents the new mutation set that should be appended instead the currently added inserted
	 *                mutation, NULL means that the added mutation should be discarded completely, multiple mutation
	 *                set means that the current mutation should be dismantled to a pieces of which only a few should
	 *                be added to the pipeline
	 * @param <T>
	 */
	record MutationCombinationResult<T extends SchemaMutation>(
		@Nullable T origin,
		@Nullable T... current
	) {

		@SafeVarargs
		public MutationCombinationResult(@Nullable T origin, @Nullable T... current) {
			this.origin = origin;
			this.current = current == null || current.length == 0 || current[0] == null ? null : current;
		}
	}

}
