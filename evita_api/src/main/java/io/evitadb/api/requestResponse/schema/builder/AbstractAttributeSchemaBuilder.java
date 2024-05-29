/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2023-2024
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
import io.evitadb.api.requestResponse.schema.mutation.attribute.ModifyAttributeSchemaDefaultValueMutation;
import io.evitadb.api.requestResponse.schema.mutation.attribute.ModifyAttributeSchemaDeprecationNoticeMutation;
import io.evitadb.api.requestResponse.schema.mutation.attribute.ModifyAttributeSchemaDescriptionMutation;
import io.evitadb.api.requestResponse.schema.mutation.attribute.ModifyAttributeSchemaTypeMutation;
import io.evitadb.api.requestResponse.schema.mutation.attribute.SetAttributeSchemaFilterableMutation;
import io.evitadb.api.requestResponse.schema.mutation.attribute.SetAttributeSchemaLocalizedMutation;
import io.evitadb.api.requestResponse.schema.mutation.attribute.SetAttributeSchemaNullableMutation;
import io.evitadb.api.requestResponse.schema.mutation.attribute.SetAttributeSchemaSortableMutation;
import io.evitadb.api.requestResponse.schema.mutation.attribute.SetAttributeSchemaUniqueMutation;
import io.evitadb.dataType.EvitaDataTypes;
import io.evitadb.dataType.Predecessor;
import io.evitadb.exception.GenericEvitaInternalError;
import io.evitadb.utils.Assert;
import io.evitadb.utils.ReflectionLookup;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.Serial;
import java.io.Serializable;
import java.util.Currency;
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
						baseSchema.getName(),
						EvitaDataTypes.toTargetType(defaultValue, wrappedForm, currentSchema.getIndexedDecimalPlaces())
					)
				)
			);
		} else {
			this.updatedSchemaDirty = updateMutationImpact(
				this.updatedSchemaDirty,
				addMutations(
					new ModifyAttributeSchemaDefaultValueMutation(
						baseSchema.getName(),
						null
					)
				)
			);
		}
		return (T) this;
	}

	@Override
	@Nonnull
	public T filterable() {
		this.updatedSchemaDirty = updateMutationImpact(
			this.updatedSchemaDirty,
			addMutations(
				new SetAttributeSchemaFilterableMutation(
					baseSchema.getName(),
					true
				)
			)
		);
		return (T) this;
	}

	@Override
	@Nonnull
	public T filterable(@Nonnull BooleanSupplier decider) {
		this.updatedSchemaDirty = updateMutationImpact(
			this.updatedSchemaDirty,
			addMutations(
				new SetAttributeSchemaFilterableMutation(
					baseSchema.getName(),
					decider.getAsBoolean()
				)
			)
		);
		return (T) this;
	}

	@Override
	@Nonnull
	public T unique() {
		this.updatedSchemaDirty = updateMutationImpact(
			this.updatedSchemaDirty,
			addMutations(
				new SetAttributeSchemaUniqueMutation(
					baseSchema.getName(),
					AttributeUniquenessType.UNIQUE_WITHIN_COLLECTION
				)
			)
		);
		return (T) this;
	}

	@Override
	@Nonnull
	public T uniqueWithinLocale() {
		this.updatedSchemaDirty = updateMutationImpact(
			this.updatedSchemaDirty,
			addMutations(
				new SetAttributeSchemaUniqueMutation(
					baseSchema.getName(),
					AttributeUniquenessType.UNIQUE_WITHIN_COLLECTION_LOCALE
				)
			)
		);
		return (T) this;
	}

	@Override
	@Nonnull
	public T unique(@Nonnull BooleanSupplier decider) {
		this.updatedSchemaDirty = updateMutationImpact(
			this.updatedSchemaDirty,
			addMutations(
				new SetAttributeSchemaUniqueMutation(
					baseSchema.getName(),
					decider.getAsBoolean() ?
						AttributeUniquenessType.UNIQUE_WITHIN_COLLECTION : AttributeUniquenessType.NOT_UNIQUE
				)
			)
		);
		return (T) this;
	}

	@Override
	@Nonnull
	public T uniqueWithinLocale(@Nonnull BooleanSupplier decider) {
		this.updatedSchemaDirty = updateMutationImpact(
			this.updatedSchemaDirty,
			addMutations(
				new SetAttributeSchemaUniqueMutation(
					baseSchema.getName(),
					decider.getAsBoolean() ?
						AttributeUniquenessType.UNIQUE_WITHIN_COLLECTION_LOCALE : AttributeUniquenessType.NOT_UNIQUE
				)
			)
		);
		return (T) this;
	}

	@Override
	@Nonnull
	public T sortable() {
		this.updatedSchemaDirty = updateMutationImpact(
			this.updatedSchemaDirty,
			addMutations(
				new SetAttributeSchemaSortableMutation(
					baseSchema.getName(),
					true
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
					baseSchema.getName(),
					decider.getAsBoolean()
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
					baseSchema.getName(),
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
					baseSchema.getName(),
					decider.getAsBoolean()
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
					baseSchema.getName(),
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
					baseSchema.getName(),
					decider.getAsBoolean()
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
					baseSchema.getName(),
					toAttributeMutation().stream()
						.filter(it -> it instanceof ModifyAttributeSchemaTypeMutation)
						.map(it -> ((ModifyAttributeSchemaTypeMutation) it).getType())
						.findFirst()
						.orElseGet(() -> (Class) baseSchema.getType()),
					indexedDecimalPlaces
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
					baseSchema.getName(),
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
					baseSchema.getName(),
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
					baseSchema.getName(),
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
			for (int i = lastMutationReflectedInSchema; i < attributeMutations.size(); i++) {
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
	protected abstract Class<S> getAttributeSchemaType();

	/**
	 * Method allows adding specific mutation on the fly.
	 */
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
			!currentSchema.isSortable() ||
				plainType.isPrimitive() ||
				Comparable.class.isAssignableFrom(plainType) ||
				Predecessor.class.isAssignableFrom(plainType),
			() -> new InvalidSchemaMutationException("Data type `" + currentSchema.getType() + "` in attribute schema `" + currentSchema.getName() + "` must implement Comparable (or must be Predecessor) in order to be usable for sort index!")
		);
		Assert.isTrue(
			!(currentSchema.isSortable() && currentSchema.getType().isArray()),
			() -> new InvalidSchemaMutationException("Attribute `" + currentSchema.getName() + "` is sortable but also an array. Arrays cannot be handled by sorting algorithm!")
		);
		Assert.isTrue(
			!(currentSchema.isFilterable() || currentSchema.isUnique()) ||
				plainType.isPrimitive() ||
				Comparable.class.isAssignableFrom(plainType) ||
				Currency.class.isAssignableFrom(plainType) ||
				Locale.class.isAssignableFrom(plainType),
			() -> new InvalidSchemaMutationException("Data type `" + currentSchema.getType() + "` in attribute schema `" + currentSchema.getName() + "` must implement Comparable in order to be usable for filter / unique index!")
		);
		Assert.isTrue(
			!(currentSchema.isFilterable() && currentSchema.isUnique()),
			() -> new InvalidSchemaMutationException("Attribute `" + currentSchema.getName() + "` cannot be both unique and filterable. Unique attributes are implicitly filterable!")
		);
	}

}
