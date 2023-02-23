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

import io.evitadb.api.exception.InvalidSchemaMutationException;
import io.evitadb.api.requestResponse.schema.AttributeSchemaContract;
import io.evitadb.api.requestResponse.schema.AttributeSchemaEditor;
import io.evitadb.api.requestResponse.schema.Cardinality;
import io.evitadb.api.requestResponse.schema.CatalogSchemaContract;
import io.evitadb.api.requestResponse.schema.EntitySchemaContract;
import io.evitadb.api.requestResponse.schema.ReferenceSchemaContract;
import io.evitadb.api.requestResponse.schema.ReferenceSchemaEditor;
import io.evitadb.api.requestResponse.schema.dto.ReferenceSchema;
import io.evitadb.api.requestResponse.schema.mutation.EntitySchemaMutation;
import io.evitadb.api.requestResponse.schema.mutation.ReferenceSchemaMutation;
import io.evitadb.api.requestResponse.schema.mutation.attribute.RemoveAttributeSchemaMutation;
import io.evitadb.api.requestResponse.schema.mutation.reference.CreateReferenceSchemaMutation;
import io.evitadb.api.requestResponse.schema.mutation.reference.ModifyReferenceSchemaDeprecationNoticeMutation;
import io.evitadb.api.requestResponse.schema.mutation.reference.ModifyReferenceSchemaDescriptionMutation;
import io.evitadb.api.requestResponse.schema.mutation.reference.ModifyReferenceSchemaRelatedEntityGroupMutation;
import io.evitadb.api.requestResponse.schema.mutation.reference.SetReferenceSchemaFacetedMutation;
import io.evitadb.api.requestResponse.schema.mutation.reference.SetReferenceSchemaFilterableMutation;
import io.evitadb.exception.EvitaInternalError;
import io.evitadb.utils.Assert;
import lombok.experimental.Delegate;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.Serial;
import java.io.Serializable;
import java.util.Collection;
import java.util.LinkedList;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;

import static java.util.Optional.ofNullable;

/**
 * Internal {@link ReferenceSchema} builder used solely from within {@link InternalEntitySchemaBuilder}.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
public final class ReferenceSchemaBuilder
	implements ReferenceSchemaEditor.ReferenceSchemaBuilder, InternalSchemaBuilderHelper {
	@Serial private static final long serialVersionUID = -6435272035844056999L;

	private final CatalogSchemaContract catalogSchema;
	private final EntitySchemaContract entitySchema;
	private final ReferenceSchemaContract baseSchema;
	private final List<EntitySchemaMutation> mutations = new LinkedList<>();
	private boolean updatedSchemaDirty;
	private ReferenceSchemaContract updatedSchema;

	ReferenceSchemaBuilder(
		@Nonnull CatalogSchemaContract catalogSchema,
		@Nonnull EntitySchemaContract entitySchema,
		@Nullable ReferenceSchemaContract existingSchema,
		@Nonnull String name,
		@Nonnull String entityType,
		boolean entityTypeRelatesToEntity,
		@Nonnull Cardinality cardinality,
		@Nonnull List<EntitySchemaMutation> mutations,
		boolean createNew
	) {
		this.catalogSchema = catalogSchema;
		this.entitySchema = entitySchema;
		this.baseSchema = existingSchema == null ?
			ReferenceSchema._internalBuild(
				name, entityType, entityTypeRelatesToEntity, cardinality, null, false, false, false
			) :
			existingSchema;
		if (createNew) {
			this.mutations.add(
				new CreateReferenceSchemaMutation(
					baseSchema.getName(),
					baseSchema.getDescription(),
					baseSchema.getDeprecationNotice(),
					cardinality,
					entityType,
					entityTypeRelatesToEntity,
					baseSchema.getReferencedGroupType(),
					baseSchema.isReferencedGroupTypeManaged(),
					baseSchema.isFilterable(),
					baseSchema.isFaceted()
				)
			);
		}
		mutations.stream()
			.filter(it -> it instanceof ReferenceSchemaMutation referenceSchemaMutation &&
				(name.equals(referenceSchemaMutation.getName()) && !(referenceSchemaMutation instanceof CreateReferenceSchemaMutation)))
			.forEach(this.mutations::add);
	}

	@Override
	@Nonnull
	public ReferenceSchemaBuilder withDescription(@Nullable String description) {
		this.updatedSchemaDirty = addMutations(
			this.catalogSchema, this.entitySchema, this.mutations,
			new ModifyReferenceSchemaDescriptionMutation(getName(), description)
		);
		return this;
	}

	@Override
	@Nonnull
	public ReferenceSchemaBuilder deprecated(@Nonnull String deprecationNotice) {
		this.updatedSchemaDirty = addMutations(
			this.catalogSchema, this.entitySchema, this.mutations,
			new ModifyReferenceSchemaDeprecationNoticeMutation(getName(), deprecationNotice)
		);
		return this;
	}

	@Override
	@Nonnull
	public ReferenceSchemaBuilder notDeprecatedAnymore() {
		this.updatedSchemaDirty = addMutations(
			this.catalogSchema, this.entitySchema, this.mutations,
			new ModifyReferenceSchemaDeprecationNoticeMutation(getName(), null)
		);
		return this;
	}

	@Override
	public ReferenceSchemaBuilder withGroupType(@Nonnull String groupType) {
		this.updatedSchemaDirty = addMutations(
			this.catalogSchema, this.entitySchema, this.mutations,
			new ModifyReferenceSchemaRelatedEntityGroupMutation(getName(), groupType, false)
		);
		return this;
	}

	@Override
	public ReferenceSchemaBuilder withGroupTypeRelatedToEntity(@Nonnull String groupType) {
		this.updatedSchemaDirty = addMutations(
			this.catalogSchema, this.entitySchema, this.mutations,
			new ModifyReferenceSchemaRelatedEntityGroupMutation(getName(), groupType, true)
		);
		return this;
	}

	@Override
	public ReferenceSchemaBuilder withoutGroupType() {
		this.updatedSchemaDirty = addMutations(
			this.catalogSchema, this.entitySchema, this.mutations,
			new ModifyReferenceSchemaRelatedEntityGroupMutation(getName(), null, false)
		);
		return this;
	}

	@Override
	public ReferenceSchemaBuilder filterable() {
		this.updatedSchemaDirty = addMutations(
			this.catalogSchema, this.entitySchema, this.mutations,
			new SetReferenceSchemaFilterableMutation(getName(), true)
		);
		return this;
	}

	@Override
	public ReferenceSchemaBuilder nonFilterable() {
		this.updatedSchemaDirty = addMutations(
			this.catalogSchema, this.entitySchema, this.mutations,
			new SetReferenceSchemaFilterableMutation(getName(), false)
		);
		return this;
	}

	@Override
	public ReferenceSchemaBuilder faceted() {
		if (toInstance().isFilterable()) {
			this.updatedSchemaDirty = addMutations(
				this.catalogSchema, this.entitySchema, this.mutations,
				new SetReferenceSchemaFacetedMutation(getName(), true)
			);
		} else {
			this.updatedSchemaDirty = addMutations(
				this.catalogSchema, this.entitySchema, this.mutations,
				new SetReferenceSchemaFilterableMutation(getName(), true),
				new SetReferenceSchemaFacetedMutation(getName(), true)
			);
		}
		return this;
	}

	@Override
	public ReferenceSchemaBuilder nonFaceted() {
		this.updatedSchemaDirty = addMutations(
			this.catalogSchema, this.entitySchema, this.mutations,
			new SetReferenceSchemaFacetedMutation(getName(), false)
		);
		return this;
	}

	@Override
	@Nonnull
	public ReferenceSchemaBuilder withAttribute(@Nonnull String attributeName, @Nonnull Class<? extends Serializable> ofType) {
		return withAttribute(attributeName, ofType, null);
	}

	@Nonnull
	@Override
	public ReferenceSchemaBuilder withAttribute(
		@Nonnull String attributeName,
		@Nonnull Class<? extends Serializable> ofType,
		@Nullable Consumer<AttributeSchemaEditor.AttributeSchemaBuilder> whichIs
	) {
		final Optional<AttributeSchemaContract> existingAttribute = getAttribute(attributeName);
		final AttributeSchemaBuilder attributeSchemaBuilder =
			existingAttribute
				.map(it -> {
					Assert.isTrue(
						ofType.equals(it.getType()),
						() -> new InvalidSchemaMutationException(
							"Attribute " + attributeName + " has already assigned type " + it.getType() +
								", cannot change this type to: " + ofType + "!"
						)
					);
					return new AttributeSchemaBuilder(entitySchema, it);
				})
				.orElseGet(() -> new AttributeSchemaBuilder(entitySchema, attributeName, ofType));

		ofNullable(whichIs).ifPresent(it -> it.accept(attributeSchemaBuilder));
		final AttributeSchemaContract attributeSchema = attributeSchemaBuilder.toInstance();
		checkSortableTraits(attributeName, attributeSchema);

		// check the names in all naming conventions are unique in the catalog schema
		checkNamesAreUniqueInAllNamingConventions(this.getAttributes().values(), attributeSchema);

		if (existingAttribute.map(it -> !it.equals(attributeSchema)).orElse(true)) {
			this.updatedSchemaDirty = addMutations(
				this.catalogSchema, this.entitySchema, this.mutations,
				attributeSchemaBuilder
					.toReferenceMutation(getName())
					.stream()
					.map(it -> (EntitySchemaMutation) it)
					.toArray(EntitySchemaMutation[]::new)
			);
		}
		return this;
	}

	@Override
	@Nonnull
	public ReferenceSchemaBuilder withoutAttribute(@Nonnull String attributeName) {
		this.updatedSchemaDirty = addMutations(
			this.catalogSchema, this.entitySchema, this.mutations,
			new RemoveAttributeSchemaMutation(attributeName)
		);
		return this;
	}

	/**
	 * Builds new instance of immutable {@link ReferenceSchemaContract} filled with updated configuration.
	 */
	@Delegate(types = ReferenceSchemaContract.class)
	@Nonnull
	public ReferenceSchemaContract toInstance() {
		if (this.updatedSchema == null || this.updatedSchemaDirty) {
			ReferenceSchemaContract currentSchema = this.baseSchema;
			for (EntitySchemaMutation mutation : this.mutations) {
				currentSchema = ((ReferenceSchemaMutation)mutation).mutate(entitySchema, currentSchema);
				if (currentSchema == null) {
					throw new EvitaInternalError("Attribute unexpectedly removed from inside!");
				}
			}
			this.updatedSchema = currentSchema;
			this.updatedSchemaDirty = false;
		}
		return this.updatedSchema;
	}

	@Override
	@Nonnull
	public Collection<EntitySchemaMutation> toMutation() {
		return this.mutations;
	}

}
