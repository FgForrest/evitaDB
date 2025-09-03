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

package io.evitadb.api.requestResponse.data.structure;

import io.evitadb.api.exception.InvalidMutationException;
import io.evitadb.api.requestResponse.data.AttributesContract;
import io.evitadb.api.requestResponse.data.ReferenceEditor.ReferenceBuilder;
import io.evitadb.api.requestResponse.data.SealedEntity;
import io.evitadb.api.requestResponse.data.mutation.attribute.AttributeMutation;
import io.evitadb.api.requestResponse.data.mutation.attribute.UpsertAttributeMutation;
import io.evitadb.api.requestResponse.data.mutation.reference.InsertReferenceMutation;
import io.evitadb.api.requestResponse.data.mutation.reference.ReferenceAttributeMutation;
import io.evitadb.api.requestResponse.data.mutation.reference.ReferenceKey;
import io.evitadb.api.requestResponse.data.mutation.reference.ReferenceMutation;
import io.evitadb.api.requestResponse.data.mutation.reference.SetReferenceGroupMutation;
import io.evitadb.api.requestResponse.schema.AttributeSchemaContract;
import io.evitadb.api.requestResponse.schema.Cardinality;
import io.evitadb.api.requestResponse.schema.EntitySchemaContract;
import io.evitadb.api.requestResponse.schema.ReferenceSchemaContract;
import io.evitadb.utils.Assert;
import lombok.Getter;
import lombok.experimental.Delegate;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.Serial;
import java.io.Serializable;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Stream;

import static java.util.Optional.of;
import static java.util.Optional.ofNullable;

/**
 * Builder that is used to create new {@link Reference} instance.
 * Due to performance reasons (see `DirectWriteOrOperationLog` microbenchmark) there is special implementation
 * for the situation when entity is newly created. In this case we know everything is new and we don't need to closely
 * monitor the changes so this can speed things up.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
public class InitialReferenceBuilder implements ReferenceBuilder {
	@Serial private static final long serialVersionUID = 2225492596172273289L;

	/**
	 * Schema of the owning entity; used to resolve and validate reference schema and attributes.
	 */
	private final EntitySchemaContract entitySchema;
	/**
	 * Schema of the reference being built.
	 */
	private final ReferenceSchemaContract referenceSchema;
	/**
	 * Delegate that builds and stores reference attributes during initial entity creation.
	 */
	@Delegate(types = AttributesContract.class)
	private final InitialReferenceAttributesBuilder attributesBuilder;
	/**
	 * Unique key of the reference being built (reference name and referenced entity primary key).
	 */
	@Getter private ReferenceKey referenceKey;
	/**
	 * Optional type of the group entity this reference is assigned to.
	 */
	@Nullable @Getter private String groupType;
	/**
	 * Optional primary key of the group entity this reference is assigned to.
	 */
	@Nullable @Getter private Integer groupId;

	/**
	 * Creates a builder for a new reference during initial entity creation.
	 *
	 * @param entitySchema               schema of the owning entity
	 * @param referenceName              name of the reference in schema
	 * @param referencedEntityPrimaryKey primary key of the referenced entity
	 * @param internalPrimaryKey         internal primary key used during build process
	 */
	public InitialReferenceBuilder(
		@Nonnull EntitySchemaContract entitySchema,
		@Nonnull ReferenceSchemaContract referenceSchema,
		@Nonnull String referenceName,
		int referencedEntityPrimaryKey,
		int internalPrimaryKey,
		@Nonnull Map<String, AttributeSchemaContract> attributeTypes
	) {
		this.entitySchema = entitySchema;
		this.referenceSchema = referenceSchema;
		this.referenceKey = new ReferenceKey(referenceName, referencedEntityPrimaryKey, internalPrimaryKey);
		this.groupId = null;
		this.groupType = null;
		this.attributesBuilder = new InitialReferenceAttributesBuilder(
			entitySchema,
			referenceSchema,
			attributeTypes
		);
	}


	@Override
	public boolean dropped() {
		return false;
	}


	@Override
	public int version() {
		return 1;
	}

	@Nonnull
	@Override
	public String getReferencedEntityType() {
		return this.referenceSchema.getReferencedEntityType();
	}

	@Nonnull
	@Override
	public Cardinality getReferenceCardinality() {
		return this.referenceSchema.getCardinality();
	}

	@Nonnull
	@Override
	public Optional<SealedEntity> getReferencedEntity() {
		return Optional.empty();
	}

	@Nonnull
	@Override
	public Optional<GroupEntityReference> getGroup() {
		return ofNullable(this.groupId)
			.map(it -> {
				Assert.isTrue(
					this.groupType != null,
					() -> new InvalidMutationException("Group type must be provided when the group type is not yet persisted in the reference schema!")
				);
				return new GroupEntityReference(this.groupType, it, 1, false);
			});
	}

	@Nonnull
	@Override
	public Optional<SealedEntity> getGroupEntity() {
		return Optional.empty();
	}

	@Nonnull
	@Override
	public Optional<ReferenceSchemaContract> getReferenceSchema() {
		return of(this.referenceSchema);
	}

	@Nonnull
	@Override
	public ReferenceSchemaContract getReferenceSchemaOrThrow() {
		return this.referenceSchema;
	}

	/**
	 * Assigns this reference to a group by its primary key.
	 *
	 * @param primaryKey primary key of the group entity
	 * @return this builder for chaining
	 */
	@Nonnull
	public ReferenceBuilder setGroup(int primaryKey) {
		this.groupId = primaryKey;
		return this;
	}

	/**
	 * Assigns this reference to a group specified by its type and primary key.
	 *
	 * @param referencedEntity type of the group entity (nullable when resolvable from schema)
	 * @param primaryKey       primary key of the group entity
	 * @return this builder for chaining
	 */
	@Nonnull
	public ReferenceBuilder setGroup(@Nullable String referencedEntity, int primaryKey) {
		this.groupType = referencedEntity;
		this.groupId = primaryKey;
		return this;
	}

	@Nonnull
	@Override
	public ReferenceBuilder removeGroup() {
		this.groupId = null;
		return this;
	}


	@Nonnull
	@Override
	public ReferenceBuilder removeAttribute(@Nonnull String attributeName) {
		this.attributesBuilder.removeAttribute(attributeName);
		return this;
	}


	@Nonnull
	@Override
	public <T extends Serializable> ReferenceBuilder setAttribute(
		@Nonnull String attributeName,
		@Nullable T attributeValue
	) {
		this.attributesBuilder.setAttribute(attributeName, attributeValue);
		return this;
	}


	@Nonnull
	@Override
	public <T extends Serializable> ReferenceBuilder setAttribute(
		@Nonnull String attributeName,
		@Nullable T[] attributeValue
	) {
		this.attributesBuilder.setAttribute(attributeName, attributeValue);
		return this;
	}


	@Nonnull
	@Override
	public ReferenceBuilder removeAttribute(@Nonnull String attributeName, @Nonnull Locale locale) {
		this.attributesBuilder.removeAttribute(attributeName, locale);
		return this;
	}


	@Nonnull
	@Override
	public <T extends Serializable> ReferenceBuilder setAttribute(
		@Nonnull String attributeName,
		@Nonnull Locale locale,
		@Nullable T attributeValue
	) {
		this.attributesBuilder.setAttribute(attributeName, locale, attributeValue);
		return this;
	}


	@Nonnull
	@Override
	public <T extends Serializable> ReferenceBuilder setAttribute(
		@Nonnull String attributeName,
		@Nonnull Locale locale,
		@Nullable T[] attributeValue
	) {
		this.attributesBuilder.setAttribute(attributeName, locale, attributeValue);
		return this;
	}

	@Nonnull
	@Override
	public ReferenceBuilder mutateAttribute(@Nonnull AttributeMutation mutation) {
		this.attributesBuilder.mutateAttribute(mutation);
		return this;
	}

	@Nonnull
	@Override
	public Stream<? extends ReferenceMutation<?>> buildChangeSet() {
		return Stream.concat(
			Stream.of(
				      new InsertReferenceMutation(this.referenceKey, this.referenceSchema.getCardinality(), this.referenceSchema.getReferencedEntityType()),
				      this.groupId == null ?
					      null :
					      new SetReferenceGroupMutation(this.referenceKey, this.groupType, this.groupId)
			      )
			      .filter(Objects::nonNull),
			this.attributesBuilder.getAttributeValues()
			                      .stream()
			                      .map(x ->
				                           new ReferenceAttributeMutation(
					                           this.referenceKey,
					                           new UpsertAttributeMutation(x.key(), Objects.requireNonNull(x.value()))
				                           )
			                      )
		);
	}

	@Override
	public boolean hasChanges() {
		return true;
	}

	@Nonnull
	@Override
	public Reference build() {
		return new Reference(
			this.referenceSchema,
			1,
			this.referenceKey,
			getGroup().orElse(null),
			this.attributesBuilder.build(),
			false
		);
	}

	/**
	 * This method allows to lazily set / or reset the referenced entity primary key. This method is considered as
	 * a part of internal API and should not be used outside of the EvitaDB core.
	 *
	 * @param referencedEntityPrimaryKey primary key of the referenced entity
	 */
	public void setReferencedEntityPrimaryKey(int referencedEntityPrimaryKey) {
		this.referenceKey = new ReferenceKey(this.referenceKey.referenceName(), referencedEntityPrimaryKey);
	}

}
