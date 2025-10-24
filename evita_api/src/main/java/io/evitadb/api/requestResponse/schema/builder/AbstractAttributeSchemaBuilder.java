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

import io.evitadb.api.exception.InvalidSchemaMutationException;
import io.evitadb.api.requestResponse.data.Versioned;
import io.evitadb.api.requestResponse.schema.AttributeSchemaContract;
import io.evitadb.api.requestResponse.schema.AttributeSchemaEditor;
import io.evitadb.api.requestResponse.schema.CatalogSchemaContract;
import io.evitadb.api.requestResponse.schema.EntitySchemaContract;
import io.evitadb.api.requestResponse.schema.dto.AttributeUniquenessType;
import io.evitadb.api.requestResponse.schema.mutation.AttributeSchemaMutation;
import io.evitadb.api.requestResponse.schema.mutation.attribute.*;
import io.evitadb.dataType.EvitaDataTypes;
import io.evitadb.dataType.Predecessor;
import io.evitadb.dataType.ReferencedEntityPredecessor;
import io.evitadb.dataType.Scope;
import io.evitadb.exception.GenericEvitaInternalError;
import io.evitadb.utils.ArrayUtils;
import io.evitadb.utils.Assert;
import io.evitadb.utils.ReflectionLookup;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.Serial;
import java.io.Serializable;
import java.util.Arrays;
import java.util.Currency;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.function.BooleanSupplier;

/**
 * Abstract parent for builders that produce {@link AttributeSchemaContract} or its extensions.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
@SuppressWarnings("unchecked")
public abstract sealed class AbstractAttributeSchemaBuilder<T extends AttributeSchemaEditor<T>, S extends AttributeSchemaContract>
	implements AttributeSchemaEditor<T>, InternalSchemaBuilderHelper
	permits AttributeSchemaBuilder, EntityAttributeSchemaBuilder, GlobalAttributeSchemaBuilder {
	@Serial private static final long serialVersionUID = -1519084392486171781L;
	protected final S baseSchema;
	protected final CatalogSchemaContract catalogSchema;
	protected final EntitySchemaContract entitySchema;
	protected MutationImpact updatedSchemaDirty = MutationImpact.NO_IMPACT;
	protected S updatedSchema;
	private int lastMutationReflectedInSchema = 0;

	AbstractAttributeSchemaBuilder(
		@Nullable CatalogSchemaContract catalogSchema,
		@Nullable EntitySchemaContract entitySchema,
		@Nonnull S existingSchema
	) {
		Assert.isTrue(
			EvitaDataTypes.isSupportedTypeOrItsArray(existingSchema.getType()),
			"Data type " + existingSchema.getType().getName() + " is not supported."
		);
		Assert.isTrue(catalogSchema != null || entitySchema != null, "Either catalog name or entity type must be present!");
		Assert.isTrue(!(catalogSchema != null && entitySchema != null), "Either catalog name or entity type must be present, but not both!");
		this.catalogSchema = catalogSchema;
		this.entitySchema = entitySchema;
		this.baseSchema = existingSchema;
	}

	@Override
	@Nonnull
	public T withDefaultValue(
		@Nullable Serializable defaultValue) {
		if (defaultValue != null) {
			final Class<? extends Serializable> wrappedForm = EvitaDataTypes.toWrappedForm(defaultValue.getClass());
			final S currentSchema = toInstance();
			final Class<? extends Serializable> expectedType = currentSchema.getType();
			Assert.isTrue(
				expectedType.equals(wrappedForm),
				"Passed default value doesn't match the type `" + expectedType + "`!"
			);

			this.updatedSchemaDirty = updateMutationImpact(
				this.updatedSchemaDirty,
				addMutations(
					new ModifyAttributeSchemaDefaultValueMutation(
						this.baseSchema.getName(),
						EvitaDataTypes.toTargetType(defaultValue, wrappedForm, currentSchema.getIndexedDecimalPlaces())
					)
				)
			);
		} else {
			this.updatedSchemaDirty = updateMutationImpact(
				this.updatedSchemaDirty,
				addMutations(
					new ModifyAttributeSchemaDefaultValueMutation(
						this.baseSchema.getName(),
						null
					)
				)
			);
		}
		return (T) this;
	}

	@Override
	@Nonnull
	public T filterableInScope(@Nonnull Scope... inScope) {
		this.updatedSchemaDirty = updateMutationImpact(
			this.updatedSchemaDirty,
			addMutations(
				new SetAttributeSchemaFilterableMutation(
					this.baseSchema.getName(),
					inScope
				)
			)
		);
		return (T) this;
	}

	@Nonnull
	@Override
	public T nonFilterableInScope(@Nonnull Scope... inScope) {
		final EnumSet<Scope> excludedScopes = ArrayUtils.toEnumSet(Scope.class, inScope);
		this.updatedSchemaDirty = updateMutationImpact(
			this.updatedSchemaDirty,
			addMutations(
				new SetAttributeSchemaFilterableMutation(
					this.baseSchema.getName(),
					Arrays.stream(Scope.values())
						.filter(it -> !isFilterableInScope(it) || !excludedScopes.contains(it))
						.toArray(Scope[]::new)
				)
			)
		);
		return (T) this;
	}

	@Nonnull
	@Override
	public T uniqueInScope(@Nonnull Scope... inScope) {
		this.updatedSchemaDirty = updateMutationImpact(
			this.updatedSchemaDirty,
			addMutations(
				new SetAttributeSchemaUniqueMutation(
					this.baseSchema.getName(),
					Arrays.stream(inScope)
						.map(it -> new ScopedAttributeUniquenessType(it, AttributeUniquenessType.UNIQUE_WITHIN_COLLECTION))
						.toArray(ScopedAttributeUniquenessType[]::new)
				)
			)
		);
		return (T) this;
	}

	@Nonnull
	@Override
	public T nonUniqueInScope(@Nonnull Scope... inScope) {
		final EnumSet<Scope> excludedScopes = ArrayUtils.toEnumSet(Scope.class, inScope);
		this.updatedSchemaDirty = updateMutationImpact(
			this.updatedSchemaDirty,
			addMutations(
				new SetAttributeSchemaSortableMutation(
					this.baseSchema.getName(),
					Arrays.stream(Scope.values())
						.filter(it -> !isUniqueInScope(it) || !excludedScopes.contains(it))
						.toArray(Scope[]::new)
				)
			)
		);
		return (T) this;
	}

	@Override
	@Nonnull
	public T uniqueWithinLocaleInScope(@Nonnull Scope... inScope) {
		this.updatedSchemaDirty = updateMutationImpact(
			this.updatedSchemaDirty,
			addMutations(
				new SetAttributeSchemaUniqueMutation(
					this.baseSchema.getName(),
					Arrays.stream(inScope)
						.map(it -> new ScopedAttributeUniquenessType(it, AttributeUniquenessType.UNIQUE_WITHIN_COLLECTION_LOCALE))
						.toArray(ScopedAttributeUniquenessType[]::new)
				)
			)
		);
		return (T) this;
	}

	@Nonnull
	@Override
	public T nonUniqueWithinLocaleInScope(@Nonnull Scope... inScope) {
		nonUniqueInScope(inScope);
		return (T) this;
	}

	@Nonnull
	@Override
	public T sortableInScope(@Nonnull Scope... inScope) {
		this.updatedSchemaDirty = updateMutationImpact(
			this.updatedSchemaDirty,
			addMutations(
				new SetAttributeSchemaSortableMutation(
					this.baseSchema.getName(),
					inScope
				)
			)
		);
		return (T) this;
	}

	@Nonnull
	@Override
	public T nonSortableInScope(@Nonnull Scope... inScope) {
		final EnumSet<Scope> excludedScopes = ArrayUtils.toEnumSet(Scope.class, inScope);
		this.updatedSchemaDirty = updateMutationImpact(
			this.updatedSchemaDirty,
			addMutations(
				new SetAttributeSchemaSortableMutation(
					this.baseSchema.getName(),
					Arrays.stream(Scope.values())
						.filter(it -> !isSortableInScope(it) || !excludedScopes.contains(it))
						.toArray(Scope[]::new)
				)
			)
		);
		return (T) this;
	}

	@Override
	@Nonnull
	public T sortable(@Nonnull BooleanSupplier decider) {
		this.updatedSchemaDirty = updateMutationImpact(
			this.updatedSchemaDirty,
			addMutations(
				new SetAttributeSchemaSortableMutation(
					this.baseSchema.getName(),
					decider.getAsBoolean() ? Scope.DEFAULT_SCOPES : Scope.NO_SCOPE
				)
			)
		);
		return (T) this;
	}

	@Override
	@Nonnull
	public T localized() {
		this.updatedSchemaDirty = updateMutationImpact(
			this.updatedSchemaDirty,
			addMutations(
				new SetAttributeSchemaLocalizedMutation(
					this.baseSchema.getName(),
					true
				)
			)
		);
		return (T) this;
	}

	@Override
	@Nonnull
	public T localized(@Nonnull BooleanSupplier decider) {
		this.updatedSchemaDirty = updateMutationImpact(
			this.updatedSchemaDirty,
			addMutations(
				new SetAttributeSchemaLocalizedMutation(
					this.baseSchema.getName(),
					decider.getAsBoolean()
				)
			)
		);
		return (T) this;
	}

	@Override
	public T nonLocalized() {
		this.updatedSchemaDirty = updateMutationImpact(
			this.updatedSchemaDirty,
			addMutations(
				new SetAttributeSchemaLocalizedMutation(
					this.baseSchema.getName(),
					false
				)
			)
		);
		return (T) this;
	}

	@Override
	@Nonnull
	public T nullable() {
		this.updatedSchemaDirty = updateMutationImpact(
			this.updatedSchemaDirty,
			addMutations(
				new SetAttributeSchemaNullableMutation(
					this.baseSchema.getName(),
					true
				)
			)
		);
		return (T) this;
	}

	@Nonnull
	@Override
	public T nullable(@Nonnull BooleanSupplier decider) {
		this.updatedSchemaDirty = updateMutationImpact(
			this.updatedSchemaDirty,
			addMutations(
				new SetAttributeSchemaNullableMutation(
					this.baseSchema.getName(),
					decider.getAsBoolean()
				)
			)
		);
		return (T) this;
	}

	@Nonnull
	@Override
	public T nonNullable() {
		this.updatedSchemaDirty = updateMutationImpact(
			this.updatedSchemaDirty,
			addMutations(
				new SetAttributeSchemaNullableMutation(
					this.baseSchema.getName(),
					false
				)
			)
		);
		return (T) this;
	}

	@Override
	@Nonnull
	public T indexDecimalPlaces(int indexedDecimalPlaces) {
		//noinspection rawtypes
		this.updatedSchemaDirty = updateMutationImpact(
			this.updatedSchemaDirty,
			addMutations(
				new ModifyAttributeSchemaTypeMutation(
					this.baseSchema.getName(),
					toAttributeMutation().stream()
						.filter(ModifyAttributeSchemaTypeMutation.class::isInstance)
						.map(it -> ((ModifyAttributeSchemaTypeMutation) it).getType())
						.findFirst()
						.orElseGet(() -> (Class) this.baseSchema.getType()),
					indexedDecimalPlaces
				)
			)
		);
		return (T) this;
	}

	@Override
	@Nonnull
	public T representative() {
		this.updatedSchemaDirty = updateMutationImpact(
			this.updatedSchemaDirty,
			addMutations(
				new SetAttributeSchemaRepresentativeMutation(
					this.baseSchema.getName(),
					true
				)
			)
		);
		return (T) this;
	}

	@Nonnull
	@Override
	public T representative(@Nonnull BooleanSupplier decider) {
		this.updatedSchemaDirty = updateMutationImpact(
			this.updatedSchemaDirty,
			addMutations(
				new SetAttributeSchemaRepresentativeMutation(
					this.baseSchema.getName(),
					decider.getAsBoolean()
				)
			)
		);
		return (T) this;
	}

	@Override
	@Nonnull
	public T withDescription(@Nullable String description) {
		this.updatedSchemaDirty = updateMutationImpact(
			this.updatedSchemaDirty,
			addMutations(
				new ModifyAttributeSchemaDescriptionMutation(
					this.baseSchema.getName(),
					description
				)
			)
		);
		return (T) this;
	}

	@Override
	@Nonnull
	public T deprecated(@Nonnull String deprecationNotice) {
		this.updatedSchemaDirty = updateMutationImpact(
			this.updatedSchemaDirty,
			addMutations(
				new ModifyAttributeSchemaDeprecationNoticeMutation(
					this.baseSchema.getName(),
					deprecationNotice
				)
			)
		);
		return (T) this;
	}

	@Override
	@Nonnull
	public T notDeprecatedAnymore() {
		this.updatedSchemaDirty = updateMutationImpact(
			this.updatedSchemaDirty,
			addMutations(
				new ModifyAttributeSchemaDeprecationNoticeMutation(
					this.baseSchema.getName(),
					null
				)
			)
		);
		return (T) this;
	}

	/**
	 * Creates attribute schema instance.
	 */
	@Nonnull
	public S toInstance() {
		if (this.updatedSchema == null || this.updatedSchemaDirty != MutationImpact.NO_IMPACT) {
			// if the dirty flat is set to modified previous we need to start from the base schema again
			// and reapply all mutations
			if (this.updatedSchemaDirty == MutationImpact.MODIFIED_PREVIOUS) {
				this.lastMutationReflectedInSchema = 0;
			}
			// if the last mutation reflected in the schema is zero we need to start from the base schema
			// else we can continue modification last known updated schema by adding additional mutations
			S currentSchema = this.lastMutationReflectedInSchema == 0 ?
				this.baseSchema : this.updatedSchema;

			final List<AttributeSchemaMutation> attributeMutations = toAttributeMutation();
			// apply the mutations not reflected in the schema
			for (int i = this.lastMutationReflectedInSchema; i < attributeMutations.size(); i++) {
				final AttributeSchemaMutation mutation = attributeMutations.get(i);
				currentSchema = mutation.mutate(null, currentSchema, getAttributeSchemaType());
				if (currentSchema == null) {
					throw new GenericEvitaInternalError("Attribute unexpectedly removed from inside!");
				}
			}
			validate(currentSchema);
			this.updatedSchema = currentSchema;
			this.updatedSchemaDirty = MutationImpact.NO_IMPACT;
			this.lastMutationReflectedInSchema = attributeMutations.size();
		}
		return this.updatedSchema;
	}

	/**
	 * Returns the type of the attribute this builder builds.
	 */
	@Nonnull
	protected abstract Class<S> getAttributeSchemaType();

	/**
	 * Method allows adding specific mutation on the fly.
	 */
	@Nonnull
	protected abstract MutationImpact addMutations(@Nonnull AttributeSchemaMutation mutation);

	/**
	 * Returns collection of {@link AttributeSchemaMutation} instances describing what changes occurred in the builder
	 * and which should be applied on the existing parent schema in particular version.
	 * Each mutation increases {@link Versioned#version()} of the modified object and allows to detect race
	 * conditions based on "optimistic locking" mechanism in very granular way.
	 */
	@Nonnull
	protected abstract List<AttributeSchemaMutation> toAttributeMutation();

	/**
	 * Method validates the consistency of an attribute schema.
	 * It basically checks the compatibility of the data type for filter/unique/sort index purposes.
	 */
	private void validate(@Nonnull S currentSchema) {
		final Class<?> plainType = ReflectionLookup.getSimpleType(currentSchema.getType());
		Assert.isTrue(
			!currentSchema.isSortableInAnyScope() ||
				plainType.isPrimitive() ||
				Comparable.class.isAssignableFrom(plainType) ||
				Currency.class.isAssignableFrom(plainType) ||
				Locale.class.isAssignableFrom(plainType) ||
				Predecessor.class.isAssignableFrom(plainType) ||
				ReferencedEntityPredecessor.class.isAssignableFrom(plainType),
			() -> new InvalidSchemaMutationException("Data type `" + currentSchema.getType() + "` in attribute schema `" + currentSchema.getName() + "` must implement Comparable (or must be Predecessor/ReferencedEntityPredecessor) in order to be usable for sort index!")
		);
		Assert.isTrue(
			!(currentSchema.isSortableInAnyScope() && currentSchema.getType().isArray()),
			() -> new InvalidSchemaMutationException("Attribute `" + currentSchema.getName() + "` is sortable but also an array. Arrays cannot be handled by sorting algorithm!")
		);
		Assert.isTrue(
			!(currentSchema.isFilterableInAnyScope() || currentSchema.isUniqueInAnyScope()) ||
				plainType.isPrimitive() ||
				Comparable.class.isAssignableFrom(plainType) ||
				Currency.class.isAssignableFrom(plainType) ||
				Locale.class.isAssignableFrom(plainType),
			() -> new InvalidSchemaMutationException("Data type `" + currentSchema.getType() + "` in attribute schema `" + currentSchema.getName() + "` must implement Comparable in order to be usable for filter / unique index!")
		);
		Assert.isTrue(
			!(currentSchema.isFilterableInAnyScope() && currentSchema.isUniqueInAnyScope()),
			() -> new InvalidSchemaMutationException("Attribute `" + currentSchema.getName() + "` cannot be both unique and filterable. Unique attributes are implicitly filterable!")
		);
	}

}
