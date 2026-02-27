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
import io.evitadb.api.requestResponse.schema.builder.InternalSchemaBuilderHelper.MutationCombinationResult;
import io.evitadb.api.requestResponse.schema.mutation.CombinableLocalEntitySchemaMutation;
import io.evitadb.api.requestResponse.schema.mutation.LocalEntitySchemaMutation;
import io.evitadb.api.requestResponse.schema.mutation.SortableAttributeCompoundSchemaMutation;
import io.evitadb.api.requestResponse.schema.mutation.sortableAttributeCompound.ReferenceSortableAttributeCompoundSchemaMutation;
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
import java.util.Arrays;

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
				final MutationCombinationResult<LocalEntitySchemaMutation> result =
					combinableMutation.combineWith(
						currentCatalogSchema,
						currentEntitySchema,
						(LocalEntitySchemaMutation) theExistingMutation
							.getSortableAttributeCompoundSchemaMutation()
					);
				if (result == null) {
					return null;
				} else {
					final LocalEntitySchemaMutation origin;
					if (result.origin() == null) {
						origin = null;
					} else if (result.origin() == combinableMutation) {
						origin = theExistingMutation;
					} else {
						origin = new ModifyReferenceSortableAttributeCompoundSchemaMutation(
							this.name,
							(ReferenceSortableAttributeCompoundSchemaMutation) result.origin()
						);
					}
					final LocalEntitySchemaMutation[] current;
					if (ArrayUtils.isEmpty(result.current())) {
						current = result.current();
					} else {
						final ReferenceSortableAttributeCompoundSchemaMutation existingCompoundMutation =
							theExistingMutation.getSortableAttributeCompoundSchemaMutation();
						current = Arrays.stream(result.current())
							.map(it -> {
								if (it == existingCompoundMutation) {
									return existingMutation;
								} else {
									return new ModifyReferenceSortableAttributeCompoundSchemaMutation(
										this.name,
										(ReferenceSortableAttributeCompoundSchemaMutation) it
									);
								}
							})
							.toArray(LocalEntitySchemaMutation[]::new);
					}
					return new MutationCombinationResult<>(origin, current);
				}
			} else {
				return null;
			}
		} else {
			return null;
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
