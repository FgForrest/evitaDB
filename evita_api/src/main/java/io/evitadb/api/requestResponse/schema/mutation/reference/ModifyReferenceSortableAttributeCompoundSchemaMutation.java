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

package io.evitadb.api.requestResponse.schema.mutation.reference;

import io.evitadb.api.requestResponse.schema.CatalogSchemaContract;
import io.evitadb.api.requestResponse.schema.EntitySchemaContract;
import io.evitadb.api.requestResponse.schema.ReferenceSchemaContract;
import io.evitadb.api.requestResponse.schema.ReflectedReferenceSchemaContract;
import io.evitadb.api.requestResponse.schema.SortableAttributeCompoundSchemaContract;
import io.evitadb.api.requestResponse.schema.builder.InternalSchemaBuilderHelper.MutationCombinationResult;
import io.evitadb.api.requestResponse.schema.dto.ReflectedReferenceSchema;
import io.evitadb.api.requestResponse.schema.mutation.CombinableLocalEntitySchemaMutation;
import io.evitadb.api.requestResponse.schema.mutation.LocalEntitySchemaMutation;
import io.evitadb.api.requestResponse.schema.mutation.SortableAttributeCompoundSchemaMutation;
import io.evitadb.api.requestResponse.schema.mutation.sortableAttributeCompound.CreateSortableAttributeCompoundSchemaMutation;
import io.evitadb.api.requestResponse.schema.mutation.sortableAttributeCompound.ReferenceSortableAttributeCompoundSchemaMutation;
import io.evitadb.api.requestResponse.schema.mutation.sortableAttributeCompound.RemoveSortableAttributeCompoundSchemaMutation;
import io.evitadb.utils.ArrayUtils;
import io.evitadb.utils.Assert;
import io.evitadb.utils.StringUtils;
import lombok.EqualsAndHashCode;
import lombok.Getter;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import javax.annotation.concurrent.Immutable;
import javax.annotation.concurrent.ThreadSafe;
import java.io.Serial;
import java.util.Optional;

/**
 * Mutation is a holder for a single {@link SortableAttributeCompoundSchemaMutation} that
 * affects any of {@link ReferenceSchemaContract#getSortableAttributeCompounds()} in the
 * {@link EntitySchemaContract}. Mutation implements {@link CombinableLocalEntitySchemaMutation}
 * allowing to resolve conflicts with the same mutation combination if it is placed twice in
 * the mutation pipeline.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2022
 */
@ThreadSafe
@Immutable
@EqualsAndHashCode(callSuper = true)
public class ModifyReferenceSortableAttributeCompoundSchemaMutation
	extends AbstractModifyReferenceDataSchemaMutation
	implements CombinableLocalEntitySchemaMutation {
	@Serial private static final long serialVersionUID = -1439568976069672739L;
	@Nonnull @Getter
	private final ReferenceSortableAttributeCompoundSchemaMutation sortableAttributeCompoundSchemaMutation;

	/**
	 * Creates a mutation that wraps the given sortable attribute compound mutation and
	 * targets the reference identified by `name`.
	 *
	 * @param name                                    the name of the reference whose sortable
	 *                                                attribute compound is being modified
	 * @param sortableAttributeCompoundSchemaMutation the compound-level mutation to apply
	 *                                                within the reference
	 */
	public ModifyReferenceSortableAttributeCompoundSchemaMutation(
		@Nonnull String name,
		@Nonnull ReferenceSortableAttributeCompoundSchemaMutation sortableAttributeCompoundSchemaMutation
	) {
		super(name);
		Assert.isTrue(
			sortableAttributeCompoundSchemaMutation instanceof SortableAttributeCompoundSchemaMutation,
			"The mutation must implement SortableAttributeCompoundSchemaMutation interface!"
		);
		this.sortableAttributeCompoundSchemaMutation = sortableAttributeCompoundSchemaMutation;
	}

	@Nullable
	@Override
	public MutationCombinationResult<LocalEntitySchemaMutation> combineWith(
		@Nonnull CatalogSchemaContract currentCatalogSchema,
		@Nonnull EntitySchemaContract currentEntitySchema,
		@Nonnull LocalEntitySchemaMutation existingMutation
	) {
		if (existingMutation instanceof ModifyReferenceSortableAttributeCompoundSchemaMutation theExistingMutation
			&& this.name.equals(theExistingMutation.getName())
			&& this.sortableAttributeCompoundSchemaMutation.getName()
			.equals(theExistingMutation.getSortableAttributeCompoundSchemaMutation().getName()
			)) {
			if (this.sortableAttributeCompoundSchemaMutation
				instanceof CombinableLocalEntitySchemaMutation combinableMutation) {
				final MutationCombinationResult<LocalEntitySchemaMutation> result = combineCompoundMutations(
					currentCatalogSchema, currentEntitySchema, combinableMutation,
					theExistingMutation.getSortableAttributeCompoundSchemaMutation()
				);
				if (result == null) {
					return null;
				} else {
					final LocalEntitySchemaMutation[] compoundMutations = result.current();
					final LocalEntitySchemaMutation[] current;
					if (ArrayUtils.isEmpty(compoundMutations)) {
						current = null;
					} else {
						current = new LocalEntitySchemaMutation[compoundMutations.length];
						for (int i = 0; i < compoundMutations.length; i++) {
							current[i] = toReferenceMutation(compoundMutations[i], theExistingMutation);
						}
					}
					return new MutationCombinationResult<>(
						result.discarded(),
						result.origin() == null ? null : toReferenceMutation(result.origin(), theExistingMutation),
						current
					);
				}
			} else {
				return null;
			}
		} else {
			return null;
		}
	}

	/**
	 * Combines the wrapped compound mutation with the compound mutation of the existing wrapper. A re-created
	 * compound is compared with the compound of this reference: the compound mutation on its own would look the name
	 * up among the compounds of the entity, which may have no such compound or a different one.
	 *
	 * @param currentCatalogSchema the catalog schema
	 * @param currentEntitySchema  the entity schema holding the reference
	 * @param combinableMutation   the wrapped compound mutation
	 * @param existingMutation     the compound mutation of the existing wrapper
	 * @return the combination result at the level of compounds, or null when the mutations do not combine
	 */
	@Nullable
	private MutationCombinationResult<LocalEntitySchemaMutation> combineCompoundMutations(
		@Nonnull CatalogSchemaContract currentCatalogSchema,
		@Nonnull EntitySchemaContract currentEntitySchema,
		@Nonnull CombinableLocalEntitySchemaMutation combinableMutation,
		@Nonnull ReferenceSortableAttributeCompoundSchemaMutation existingMutation
	) {
		if (
			combinableMutation instanceof CreateSortableAttributeCompoundSchemaMutation createCompoundMutation &&
				existingMutation instanceof RemoveSortableAttributeCompoundSchemaMutation
		) {
			final Optional<SortableAttributeCompoundSchemaContract> removedCompound = getReferenceCompound(currentEntitySchema);
			return removedCompound.isPresent()
				? createCompoundMutation.combineWithRemovalOf(currentEntitySchema, removedCompound.get())
				: null;
		} else {
			return combinableMutation.combineWith(
				currentCatalogSchema, currentEntitySchema, (LocalEntitySchemaMutation) existingMutation
			);
		}
	}

	/**
	 * Returns the compound of this reference the wrapped compound mutation targets. A reflected reference is asked
	 * for the compounds it declares itself, because the inherited ones are not available while the reflected
	 * reference is not bound to its original.
	 *
	 * @param entitySchema the entity schema holding the reference
	 * @return the compound, or empty when the reference or the compound does not exist
	 */
	@Nonnull
	private Optional<SortableAttributeCompoundSchemaContract> getReferenceCompound(@Nonnull EntitySchemaContract entitySchema) {
		final String compoundName = this.sortableAttributeCompoundSchemaMutation.getName();
		final Optional<ReferenceSchemaContract> reference = entitySchema.getReference(this.name);
		if (reference.isEmpty()) {
			return Optional.empty();
		} else if (reference.get() instanceof ReflectedReferenceSchema reflectedReference) {
			return Optional.ofNullable(reflectedReference.getDeclaredSortableAttributeCompounds().get(compoundName));
		} else {
			return reference.get().getSortableAttributeCompound(compoundName);
		}
	}

	/**
	 * Lifts a compound mutation returned by the combination of the wrapped compound mutations back to the level of
	 * references. The two wrapped mutations stand for their own wrappers: the schema builder recognizes the examined
	 * and the existing mutation by identity, and an equal copy in their place would make it combine the same pair
	 * again forever. Any other compound mutation gets a new wrapper.
	 *
	 * @param compoundMutation the compound mutation to lift
	 * @param existingMutation the existing wrapper this mutation is combined with
	 * @return the mutation at the level of references
	 */
	@Nonnull
	private LocalEntitySchemaMutation toReferenceMutation(
		@Nonnull LocalEntitySchemaMutation compoundMutation,
		@Nonnull ModifyReferenceSortableAttributeCompoundSchemaMutation existingMutation
	) {
		if (compoundMutation == this.sortableAttributeCompoundSchemaMutation) {
			return this;
		} else if (compoundMutation == existingMutation.getSortableAttributeCompoundSchemaMutation()) {
			return existingMutation;
		} else {
			return new ModifyReferenceSortableAttributeCompoundSchemaMutation(
				this.name, (ReferenceSortableAttributeCompoundSchemaMutation) compoundMutation
			);
		}
	}

	@Nullable
	@Override
	public ReferenceSchemaContract mutate(
		@Nonnull EntitySchemaContract entitySchema,
		@Nullable ReferenceSchemaContract referenceSchema,
		@Nonnull ConsistencyChecks consistencyChecks
	) {
		return this.sortableAttributeCompoundSchemaMutation.mutate(
			entitySchema, referenceSchema,
			referenceSchema instanceof ReflectedReferenceSchemaContract rrsc && !rrsc.isReflectedReferenceAvailable() ?
				ConsistencyChecks.SKIP : consistencyChecks
		);
	}

	@Nonnull
	@Override
	public String containerName() {
		return super.containerName() + "." + this.sortableAttributeCompoundSchemaMutation.getName();
	}

	@Override
	public String toString() {
		return "Modify entity reference `" + this.name + "` schema, " +
			StringUtils.uncapitalize(this.sortableAttributeCompoundSchemaMutation.toString());
	}
}
