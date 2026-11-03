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

import io.evitadb.api.requestResponse.schema.AttributeSchemaContract;
import io.evitadb.api.requestResponse.schema.CatalogSchemaContract;
import io.evitadb.api.requestResponse.schema.EntitySchemaContract;
import io.evitadb.api.requestResponse.schema.ReferenceSchemaContract;
import io.evitadb.api.requestResponse.schema.ReflectedReferenceSchemaContract;
import io.evitadb.api.requestResponse.schema.builder.InternalSchemaBuilderHelper.MutationCombinationResult;
import io.evitadb.api.requestResponse.schema.dto.ReflectedReferenceSchema;
import io.evitadb.api.requestResponse.schema.mutation.AttributeSchemaMutation;
import io.evitadb.api.requestResponse.schema.mutation.CombinableLocalEntitySchemaMutation;
import io.evitadb.api.requestResponse.schema.mutation.LocalEntitySchemaMutation;
import io.evitadb.api.requestResponse.schema.mutation.attribute.CreateAttributeSchemaMutation;
import io.evitadb.api.requestResponse.schema.mutation.attribute.ReferenceAttributeSchemaMutation;
import io.evitadb.api.requestResponse.schema.mutation.attribute.RemoveAttributeSchemaMutation;
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
 * Mutation is a holder for a single {@link AttributeSchemaMutation} that affects any
 * of {@link ReferenceSchemaContract#getAttributes()} in the {@link EntitySchemaContract}.
 * Mutation implements {@link CombinableLocalEntitySchemaMutation} allowing to resolve
 * conflicts with the same mutation combination if it is placed twice in the mutation
 * pipeline.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2022
 */
@ThreadSafe
@Immutable
@EqualsAndHashCode(callSuper = true)
public class ModifyReferenceAttributeSchemaMutation
	extends AbstractModifyReferenceDataSchemaMutation
	implements CombinableLocalEntitySchemaMutation {
	@Serial private static final long serialVersionUID = -5779012919587623154L;
	@Nonnull @Getter private final ReferenceAttributeSchemaMutation attributeSchemaMutation;

	/**
	 * Creates a mutation that wraps the given attribute schema mutation and targets the
	 * reference identified by `name`.
	 *
	 * @param name                   the name of the reference whose attribute is being modified
	 * @param attributeSchemaMutation the attribute-level mutation to apply within the reference
	 */
	public ModifyReferenceAttributeSchemaMutation(
		@Nonnull String name,
		@Nonnull ReferenceAttributeSchemaMutation attributeSchemaMutation
	) {
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
		if (existingMutation instanceof ModifyReferenceAttributeSchemaMutation theExistingMutation
			&& this.name.equals(theExistingMutation.getName())
			&& this.attributeSchemaMutation.getName().equals(theExistingMutation.getAttributeSchemaMutation().getName())
		) {
			if (this.attributeSchemaMutation instanceof CombinableLocalEntitySchemaMutation combinableAttributeMutation) {
				final MutationCombinationResult<LocalEntitySchemaMutation> result = combineAttributeMutations(
					currentCatalogSchema, currentEntitySchema, combinableAttributeMutation,
					theExistingMutation.getAttributeSchemaMutation()
				);
				if (result == null) {
					return null;
				} else {
					final LocalEntitySchemaMutation[] attributeMutations = result.current();
					final LocalEntitySchemaMutation[] current;
					if (ArrayUtils.isEmpty(attributeMutations)) {
						current = null;
					} else {
						current = new LocalEntitySchemaMutation[attributeMutations.length];
						for (int i = 0; i < attributeMutations.length; i++) {
							current[i] = toReferenceMutation(attributeMutations[i], theExistingMutation);
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
	 * Combines the wrapped attribute mutation with the attribute mutation of the existing wrapper. A re-created
	 * attribute is compared with the attribute of this reference: the attribute mutation on its own would look the
	 * name up among the attributes of the entity, which may have no such attribute or a different one.
	 *
	 * @param currentCatalogSchema       the catalog schema
	 * @param currentEntitySchema        the entity schema holding the reference
	 * @param combinableAttributeMutation the wrapped attribute mutation
	 * @param existingAttributeMutation  the attribute mutation of the existing wrapper
	 * @return the combination result at the level of attributes, or null when the mutations do not combine
	 */
	@Nullable
	private MutationCombinationResult<LocalEntitySchemaMutation> combineAttributeMutations(
		@Nonnull CatalogSchemaContract currentCatalogSchema,
		@Nonnull EntitySchemaContract currentEntitySchema,
		@Nonnull CombinableLocalEntitySchemaMutation combinableAttributeMutation,
		@Nonnull ReferenceAttributeSchemaMutation existingAttributeMutation
	) {
		if (
			combinableAttributeMutation instanceof CreateAttributeSchemaMutation createAttributeMutation &&
				existingAttributeMutation instanceof RemoveAttributeSchemaMutation
		) {
			final Optional<AttributeSchemaContract> removedAttribute = getReferenceAttribute(currentEntitySchema);
			return removedAttribute.isPresent()
				? createAttributeMutation.combineWithRemovalOf(currentCatalogSchema, removedAttribute.get())
				: null;
		} else {
			return combinableAttributeMutation.combineWith(
				currentCatalogSchema, currentEntitySchema, (LocalEntitySchemaMutation) existingAttributeMutation
			);
		}
	}

	/**
	 * Returns the attribute of this reference the wrapped attribute mutation targets. A reflected reference is asked
	 * for the attributes it declares itself: an inherited attribute cannot be removed, and the inherited ones are not
	 * available while the reflected reference is not bound to its original.
	 *
	 * @param entitySchema the entity schema holding the reference
	 * @return the attribute, or empty when the reference or the attribute does not exist
	 */
	@Nonnull
	private Optional<AttributeSchemaContract> getReferenceAttribute(@Nonnull EntitySchemaContract entitySchema) {
		final String attributeName = this.attributeSchemaMutation.getName();
		final Optional<ReferenceSchemaContract> reference = entitySchema.getReference(this.name);
		if (reference.isEmpty()) {
			return Optional.empty();
		} else if (reference.get() instanceof ReflectedReferenceSchema reflectedReference) {
			return reflectedReference.getDeclaredAttribute(attributeName);
		} else {
			return reference.get().getAttribute(attributeName);
		}
	}

	/**
	 * Lifts an attribute mutation returned by the combination of the wrapped attribute mutations back to the level
	 * of references. The two wrapped mutations stand for their own wrappers, so the schema builder reads the result
	 * as it would read one of unwrapped mutations: it tells a kept existing mutation and a kept examined one by
	 * identity, and an equal copy would read as a replacement and as a new mutation to examine. Any other attribute
	 * mutation gets a new wrapper.
	 *
	 * @param attributeMutation the attribute mutation to lift
	 * @param existingMutation  the existing wrapper this mutation is combined with
	 * @return the mutation at the level of references
	 */
	@Nonnull
	private LocalEntitySchemaMutation toReferenceMutation(
		@Nonnull LocalEntitySchemaMutation attributeMutation,
		@Nonnull ModifyReferenceAttributeSchemaMutation existingMutation
	) {
		if (attributeMutation == this.attributeSchemaMutation) {
			return this;
		} else if (attributeMutation == existingMutation.getAttributeSchemaMutation()) {
			return existingMutation;
		} else {
			return new ModifyReferenceAttributeSchemaMutation(this.name, (ReferenceAttributeSchemaMutation) attributeMutation);
		}
	}

	@Nullable
	@Override
	public ReferenceSchemaContract mutate(
		@Nonnull EntitySchemaContract entitySchema,
		@Nullable ReferenceSchemaContract referenceSchema,
		@Nonnull ConsistencyChecks consistencyChecks
	) {
		return this.attributeSchemaMutation.mutate(
			entitySchema, referenceSchema,
			referenceSchema instanceof ReflectedReferenceSchemaContract rrsc && !rrsc.isReflectedReferenceAvailable() ?
				ConsistencyChecks.SKIP : consistencyChecks
		);
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
