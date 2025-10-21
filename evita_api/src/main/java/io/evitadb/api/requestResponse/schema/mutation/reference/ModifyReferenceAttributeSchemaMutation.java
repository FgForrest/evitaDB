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

import io.evitadb.api.exception.InvalidSchemaMutationException;
import io.evitadb.api.requestResponse.schema.CatalogSchemaContract;
import io.evitadb.api.requestResponse.schema.EntitySchemaContract;
import io.evitadb.api.requestResponse.schema.ReferenceSchemaContract;
import io.evitadb.api.requestResponse.schema.ReflectedReferenceSchemaContract;
import io.evitadb.api.requestResponse.schema.builder.InternalSchemaBuilderHelper.MutationCombinationResult;
import io.evitadb.api.requestResponse.schema.mutation.AttributeSchemaMutation;
import io.evitadb.api.requestResponse.schema.mutation.CombinableLocalEntitySchemaMutation;
import io.evitadb.api.requestResponse.schema.mutation.LocalEntitySchemaMutation;
import io.evitadb.api.requestResponse.schema.mutation.attribute.ReferenceAttributeSchemaMutation;
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
import java.util.Optional;

/**
 * Mutation is a holder for a single {@link AttributeSchemaMutation} that affect any
 * of {@link ReferenceSchemaContract#getAttributes()} in the {@link EntitySchemaContract}.
 * Mutation implements {@link CombinableLocalEntitySchemaMutation} allowing to resolve conflicts with the same mutation
 * combination if it is placed twice in the mutation pipeline.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2022
 */
@ThreadSafe
@Immutable
@EqualsAndHashCode(callSuper = true)
public class ModifyReferenceAttributeSchemaMutation extends AbstractModifyReferenceDataSchemaMutation
	implements CombinableLocalEntitySchemaMutation {
	@Serial private static final long serialVersionUID = -5779012919587623154L;
	@Nonnull @Getter private final ReferenceAttributeSchemaMutation attributeSchemaMutation;

	public ModifyReferenceAttributeSchemaMutation(@Nonnull String name, @Nonnull ReferenceAttributeSchemaMutation attributeSchemaMutation) {
		super(name);
		Assert.isTrue(attributeSchemaMutation instanceof AttributeSchemaMutation, "The mutation must implement AttributeSchemaMutation interface!");
		this.attributeSchemaMutation = attributeSchemaMutation;
	}

	@Nullable
	@Override
	public MutationCombinationResult<LocalEntitySchemaMutation> combineWith(
		@Nonnull CatalogSchemaContract currentCatalogSchema,
		@Nonnull EntitySchemaContract currentEntitySchema,
		@Nonnull LocalEntitySchemaMutation existingMutation
	) {
		if (existingMutation instanceof ModifyReferenceAttributeSchemaMutation theExistingMutation && this.name.equals(theExistingMutation.getName())
				&& this.attributeSchemaMutation.getName().equals(theExistingMutation.getAttributeSchemaMutation().getName())) {
			if (this.attributeSchemaMutation instanceof CombinableLocalEntitySchemaMutation combinableAttributeCombinationMutation) {
				final MutationCombinationResult<LocalEntitySchemaMutation> result = combinableAttributeCombinationMutation.combineWith(
					currentCatalogSchema, currentEntitySchema, (LocalEntitySchemaMutation) theExistingMutation.getAttributeSchemaMutation()
				);
				if (result == null) {
					return null;
				} else {
					final LocalEntitySchemaMutation origin;
					if (result.origin() == null) {
						origin = null;
					} else if (result.origin() == combinableAttributeCombinationMutation) {
						origin = theExistingMutation;
					} else {
						origin = new ModifyReferenceAttributeSchemaMutation(this.name, (ReferenceAttributeSchemaMutation) result.origin());
					}
					final LocalEntitySchemaMutation[] current;
					if (ArrayUtils.isEmpty(result.current())) {
						current = result.current();
					} else {
						current = Arrays.stream(result.current())
							.map(it -> {
								if (it == ((ModifyReferenceAttributeSchemaMutation) existingMutation).getAttributeSchemaMutation()) {
									return existingMutation;
								} else {
									return new ModifyReferenceAttributeSchemaMutation(this.name, (ReferenceAttributeSchemaMutation) it);
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
	public ReferenceSchemaContract mutate(@Nonnull EntitySchemaContract entitySchema, @Nullable ReferenceSchemaContract referenceSchema, @Nonnull ConsistencyChecks consistencyChecks) {
		return this.attributeSchemaMutation.mutate(
			entitySchema, referenceSchema,
			referenceSchema instanceof ReflectedReferenceSchemaContract rrsc && !rrsc.isReflectedReferenceAvailable() ?
				ConsistencyChecks.SKIP : consistencyChecks
		);
	}

	@Nonnull
	@Override
	public EntitySchemaContract mutate(@Nonnull CatalogSchemaContract catalogSchema, @Nullable EntitySchemaContract entitySchema) {
		Assert.isPremiseValid(entitySchema != null, "Entity schema is mandatory!");
		final Optional<ReferenceSchemaContract> existingReferenceSchema = entitySchema.getReference(this.name);
		if (existingReferenceSchema.isEmpty()) {
			// ups, the reference schema is missing
			throw new InvalidSchemaMutationException(
				"The reference `" + this.name + "` is not defined in entity `" + entitySchema.getName() + "` schema!"
			);
		} else {
			final ReferenceSchemaContract theSchema = existingReferenceSchema.get();
			final ReferenceSchemaContract updatedSchema = mutate(entitySchema, theSchema);
			Assert.isPremiseValid(updatedSchema != null, "Updated reference schema is not expected to be null!");
			return replaceReferenceSchema(entitySchema, theSchema, updatedSchema);
		}
	}

	@Nonnull
	@Override
	public String containerName() {
		return super.containerName() + "." + this.attributeSchemaMutation.containerName();
	}

	@Override
	public String toString() {
		return "Modify entity reference `" + this.name + "` schema, " +
			StringUtils.uncapitalize(this.attributeSchemaMutation.toString());
	}
}
