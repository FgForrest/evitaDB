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

package io.evitadb.api.requestResponse.data.structure;

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
import io.evitadb.exception.EvitaInvalidUsageException;
import io.evitadb.utils.Assert;
import lombok.Getter;
import lombok.experimental.Delegate;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.Serial;
import java.io.Serializable;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.function.BiPredicate;
import java.util.stream.Stream;

import static java.util.Optional.ofNullable;

/**
 * Builder that is used to create new {@link Reference} instance.
 * Due to performance reasons (see {@link DirectWriteOrOperationLog} microbenchmark) there is special implementation
 * for the situation when entity is newly created. In this case we know everything is new and we don't need to closely
 * monitor the changes so this can speed things up.
 *
 * @author Jan Novotn?? (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
public class InitialReferenceBuilder implements ReferenceBuilder {
	@Serial private static final long serialVersionUID = 2225492596172273289L;

	private final EntitySchemaContract entitySchema;
	private final BiPredicate<String, String> uniqueAttributePredicate;
	@Getter private final ReferenceKey referenceKey;
	@Getter private final Cardinality referenceCardinality;
	@Getter private final String referencedEntityType;
	@Delegate(types = AttributesContract.class)
	private final AttributesBuilder attributesBuilder;
	@Getter private String groupType;
	@Getter private Integer groupId;

	static void verifyAttributeIsInSchemaAndTypeMatch(
		@Nonnull EntitySchemaContract entitySchema,
		@Nullable ReferenceSchemaContract referenceSchema,
		@Nonnull String attributeName,
		@Nullable Class<? extends Serializable> aClass
	) {
		final AttributeSchemaContract attributeSchema = ofNullable(referenceSchema)
			.flatMap(it -> it.getAttribute(attributeName))
			.orElse(null);
		InitialAttributesBuilder.verifyAttributeIsInSchemaAndTypeMatch(entitySchema, referenceSchema, attributeName, aClass, null, attributeSchema);
	}

	static void verifyAttributeIsInSchemaAndTypeMatch(
		@Nonnull EntitySchemaContract entitySchema,
		@Nullable ReferenceSchemaContract referenceSchema,
		@Nonnull String attributeName,
		@Nullable Class<? extends Serializable> aClass,
		@Nonnull Locale locale
	) {
		final AttributeSchemaContract attributeSchema = ofNullable(referenceSchema)
			.flatMap(it -> it.getAttribute(attributeName))
			.orElse(null);
		InitialAttributesBuilder.verifyAttributeIsInSchemaAndTypeMatch(entitySchema, referenceSchema, attributeName, aClass, locale, attributeSchema);
	}

	static void verifySortableAttributeUniqueness(
		@Nullable ReferenceSchemaContract referenceSchema,
		@Nonnull String referenceName,
		@Nonnull String attributeName,
		@Nonnull BiPredicate<String, String> uniqueAttributePredicate
	) {
		final AttributeSchemaContract attributeSchema = ofNullable(referenceSchema)
			.flatMap(it -> it.getAttribute(attributeName))
			.orElse(null);
		if (attributeSchema != null && attributeSchema.isSortable()) {
			Assert.isTrue(
				!uniqueAttributePredicate.test(referenceName, attributeName),
				"Attribute " + attributeName + " is sortable and only single reference of type " + referenceName + " may use it!" +
					" In this entity there is already " + attributeName + " present on another reference of this type!"
			);
		}
	}

	public <T extends BiPredicate<String, String> & Serializable> InitialReferenceBuilder(
		@Nonnull EntitySchemaContract entitySchema,
		@Nonnull String referenceName,
		int referencedEntityPrimaryKey,
		@Nullable Cardinality referenceCardinality,
		@Nullable String referencedEntityType,
		@Nonnull T uniqueAttributePredicate
	) {
		this.entitySchema = entitySchema;
		this.referenceKey = new ReferenceKey(referenceName, referencedEntityPrimaryKey);
		this.referenceCardinality = referenceCardinality;
		this.referencedEntityType = referencedEntityType;
		this.uniqueAttributePredicate = uniqueAttributePredicate;
		this.groupId = null;
		this.groupType = null;
		this.attributesBuilder = new InitialAttributesBuilder(entitySchema, true);
	}

	@Override
	public boolean isDropped() {
		return false;
	}

	@Override
	public int getVersion() {
		return 1;
	}

	@Nonnull
	@Override
	public Optional<ReferenceSchemaContract> getReferenceSchema() {
		return this.entitySchema.getReference(referenceKey.referenceName());
	}

	@Nonnull
	@Override
	public ReferenceSchemaContract getReferenceSchemaOrThrow() {
		return this.entitySchema.getReference(referenceKey.referenceName())
			.orElseThrow(() -> new EvitaInvalidUsageException("Reference schema is not available!"));
	}

	@Nonnull
	@Override
	public Optional<SealedEntity> getReferencedEntity() {
		return Optional.empty();
	}

	@Nonnull
	public ReferenceBuilder setGroup(int primaryKey) {
		this.groupId = primaryKey;
		return this;
	}

	@Nonnull
	public ReferenceBuilder setGroup(@Nullable String referencedEntity, int primaryKey) {
		this.groupType = referencedEntity;
		this.groupId = primaryKey;
		return this;
	}

	@Nonnull
	@Override
	public Optional<GroupEntityReference> getGroup() {
		return ofNullable(groupId).map(it -> new GroupEntityReference(groupType, it, 1, false));
	}

	@Nonnull
	@Override
	public Optional<SealedEntity> getGroupEntity() {
		return Optional.empty();
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
		attributesBuilder.removeAttribute(attributeName);
		return this;
	}

	@Nonnull
	@Override
	public <T extends Serializable> ReferenceBuilder setAttribute(@Nonnull String attributeName, @Nullable T attributeValue) {
		if (attributeValue == null) {
			return removeAttribute(attributeName);
		} else {
			final ReferenceSchemaContract referenceSchema = entitySchema.getReference(this.referenceKey.referenceName()).orElse(null);
			verifyAttributeIsInSchemaAndTypeMatch(entitySchema, referenceSchema, attributeName, attributeValue.getClass());
			verifySortableAttributeUniqueness(referenceSchema, this.referenceKey.referenceName(), attributeName, uniqueAttributePredicate);
			attributesBuilder.setAttribute(attributeName, attributeValue);
			return this;
		}
	}

	@Nonnull
	@Override
	public <T extends Serializable> ReferenceBuilder setAttribute(@Nonnull String attributeName, @Nullable T[] attributeValue) {
		if (attributeValue == null) {
			return removeAttribute(attributeName);
		} else {
			final ReferenceSchemaContract referenceSchema = entitySchema.getReference(this.referenceKey.referenceName()).orElse(null);
			verifyAttributeIsInSchemaAndTypeMatch(entitySchema, referenceSchema, attributeName, attributeValue.getClass());
			attributesBuilder.setAttribute(attributeName, attributeValue);
			return this;
		}
	}

	@Nonnull
	@Override
	public ReferenceBuilder removeAttribute(@Nonnull String attributeName, @Nonnull Locale locale) {
		attributesBuilder.removeAttribute(attributeName, locale);
		return this;
	}

	@Nonnull
	@Override
	public <T extends Serializable> ReferenceBuilder setAttribute(@Nonnull String attributeName, @Nonnull Locale locale, @Nullable T attributeValue) {
		if (attributeValue == null) {
			return removeAttribute(attributeName, locale);
		} else {
			final ReferenceSchemaContract referenceSchema = entitySchema.getReference(this.referenceKey.referenceName()).orElse(null);
			verifyAttributeIsInSchemaAndTypeMatch(entitySchema, referenceSchema, attributeName, attributeValue.getClass(), locale);
			verifySortableAttributeUniqueness(referenceSchema, this.referenceKey.referenceName(), attributeName, uniqueAttributePredicate);
			attributesBuilder.setAttribute(attributeName, locale, attributeValue);
			return this;
		}
	}

	@Nonnull
	@Override
	public <T extends Serializable> ReferenceBuilder setAttribute(@Nonnull String attributeName, @Nonnull Locale locale, @Nullable T[] attributeValue) {
		if (attributeValue == null) {
			return removeAttribute(attributeName, locale);
		} else {
			final ReferenceSchemaContract referenceSchema = entitySchema.getReference(this.referenceKey.referenceName()).orElse(null);
			verifyAttributeIsInSchemaAndTypeMatch(entitySchema, referenceSchema, attributeName, attributeValue.getClass(), locale);
			attributesBuilder.setAttribute(attributeName, locale, attributeValue);
			return this;
		}
	}

	@Nonnull
	@Override
	public ReferenceBuilder mutateAttribute(@Nonnull AttributeMutation mutation) {
		final ReferenceSchemaContract referenceSchema = entitySchema.getReference(this.referenceKey.referenceName()).orElse(null);
		verifyAttributeIsInSchemaAndTypeMatch(entitySchema, referenceSchema, mutation.getAttributeKey().getAttributeName(), null);
		attributesBuilder.mutateAttribute(mutation);
		return this;
	}

	@Nonnull
	@Override
	public Stream<? extends ReferenceMutation<?>> buildChangeSet() {
		return Stream.concat(
			Stream.of(
					new InsertReferenceMutation(referenceKey, referenceCardinality, referencedEntityType),
					groupId == null ? null : new SetReferenceGroupMutation(referenceKey, groupType, groupId)
				)
				.filter(Objects::nonNull),
			attributesBuilder.getAttributeValues()
				.stream()
				.map(x ->
					new ReferenceAttributeMutation(
						referenceKey,
						new UpsertAttributeMutation(x.getKey(), Objects.requireNonNull(x.getValue()))
					)
				)
		);
	}

	@Nonnull
	@Override
	public Reference build() {
		return new Reference(
			entitySchema,
			1,
			referenceKey.referenceName(),
			referenceKey.primaryKey(),
			referencedEntityType, referenceCardinality,
			getGroup().orElse(null),
			attributesBuilder.build(),
			false
		);
	}

}
