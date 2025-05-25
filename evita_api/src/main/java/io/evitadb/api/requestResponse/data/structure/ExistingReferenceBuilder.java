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

import io.evitadb.api.requestResponse.data.AttributesContract;
import io.evitadb.api.requestResponse.data.ReferenceContract;
import io.evitadb.api.requestResponse.data.ReferenceEditor.ReferenceBuilder;
import io.evitadb.api.requestResponse.data.SealedEntity;
import io.evitadb.api.requestResponse.data.mutation.LocalMutation;
import io.evitadb.api.requestResponse.data.mutation.attribute.AttributeMutation;
import io.evitadb.api.requestResponse.data.mutation.reference.ReferenceAttributeMutation;
import io.evitadb.api.requestResponse.data.mutation.reference.ReferenceKey;
import io.evitadb.api.requestResponse.data.mutation.reference.ReferenceMutation;
import io.evitadb.api.requestResponse.data.mutation.reference.RemoveReferenceGroupMutation;
import io.evitadb.api.requestResponse.data.mutation.reference.RemoveReferenceMutation;
import io.evitadb.api.requestResponse.data.mutation.reference.SetReferenceGroupMutation;
import io.evitadb.api.requestResponse.schema.AttributeSchemaContract;
import io.evitadb.api.requestResponse.schema.AttributeSchemaProvider;
import io.evitadb.api.requestResponse.schema.Cardinality;
import io.evitadb.api.requestResponse.schema.EntitySchemaContract;
import io.evitadb.api.requestResponse.schema.ReferenceSchemaContract;
import io.evitadb.exception.EvitaInvalidUsageException;
import lombok.Getter;
import lombok.experimental.Delegate;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.Serial;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiPredicate;
import java.util.stream.Stream;

import static java.util.Optional.empty;
import static java.util.Optional.of;
import static java.util.Optional.ofNullable;

/**
 * Builder that is used to alter existing {@link Reference}.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
public class ExistingReferenceBuilder implements ReferenceBuilder, Serializable {
	@Serial private static final long serialVersionUID = 4611697377656713570L;

	@Getter private final ReferenceContract baseReference;
	@Getter private final EntitySchemaContract entitySchema;
	@Delegate(types = AttributesContract.class)
	private final ExistingReferenceAttributesBuilder attributesBuilder;
	private ReferenceMutation<ReferenceKey> referenceGroupMutation;

	public <T extends BiPredicate<String, String> & Serializable> ExistingReferenceBuilder(
		@Nonnull ReferenceContract baseReference,
		@Nonnull EntitySchemaContract entitySchema
	) {
		this(baseReference, entitySchema, Collections.emptyList());
	}

	public <T extends BiPredicate<String, String> & Serializable> ExistingReferenceBuilder(
		@Nonnull ReferenceContract baseReference,
		@Nonnull EntitySchemaContract entitySchema,
		@Nonnull Collection<LocalMutation<?, ?>> mutations
	) {
		this.baseReference = baseReference;
		this.entitySchema = entitySchema;
		final List<AttributeMutation> attributeMutations = new ArrayList<>(mutations.size());
		for (LocalMutation<?, ?> mutation : mutations) {
			if (mutation instanceof AttributeMutation attributeMutation) {
				attributeMutations.add(attributeMutation);
			} else if (mutation instanceof SetReferenceGroupMutation referenceMutation) {
				this.referenceGroupMutation = referenceMutation;
			} else if (mutation instanceof RemoveReferenceMutation referenceMutation) {
				this.referenceGroupMutation = referenceMutation;
			} else {
				throw new EvitaInvalidUsageException("Unsupported mutation type: " + mutation.getClass().getName());
			}
		}
		this.attributesBuilder = new ExistingReferenceAttributesBuilder(
			entitySchema,
			baseReference.getReferenceSchema().orElseGet(
				() -> Reference.createImplicitSchema(
					baseReference.getReferenceName(),
					baseReference.getReferencedEntityType(),
					baseReference.getReferenceCardinality(),
					baseReference.getGroup().orElse(null)
				)
			),
			baseReference.getAttributeValues(),
			baseReference.getReferenceSchema()
				.map(AttributeSchemaProvider::getAttributes)
				.orElse(Collections.emptyMap()),
			true,
			attributeMutations
		);
	}

	@Override
	public boolean dropped() {
		return this.baseReference.dropped();
	}

	@Override
	public int version() {
		return this.baseReference.version();
	}

	@Nonnull
	@Override
	public ReferenceKey getReferenceKey() {
		return this.baseReference.getReferenceKey();
	}

	@Nonnull
	@Override
	public String getReferencedEntityType() {
		return this.baseReference.getReferencedEntityType();
	}

	@Nonnull
	@Override
	public Optional<SealedEntity> getReferencedEntity() {
		return this.baseReference.getReferencedEntity();
	}

	@Nonnull
	@Override
	public Cardinality getReferenceCardinality() {
		return this.baseReference.getReferenceCardinality();
	}

	@Nonnull
	@Override
	public Optional<GroupEntityReference> getGroup() {
		final Optional<GroupEntityReference> group = this.baseReference.getGroup()
			.map(it -> ofNullable(this.referenceGroupMutation).map(fgm -> fgm.mutateLocal(this.entitySchema, this.baseReference).getGroup()).orElseGet(() -> of(it)))
			.orElseGet(() -> ofNullable(this.referenceGroupMutation).flatMap(fgm -> fgm.mutateLocal(this.entitySchema, this.baseReference).getGroup()));
		return group.filter(GroupEntityReference::exists);
	}

	@Nonnull
	@Override
	public Optional<SealedEntity> getGroupEntity() {
		return Objects.equals(
			getGroup().map(GroupEntityReference::getPrimaryKey).orElse(null),
			this.baseReference.getGroup().map(GroupEntityReference::getPrimaryKey).orElse(null)
		)
			? this.baseReference.getGroupEntity() : empty();
	}

	@Nonnull
	@Override
	public Optional<ReferenceSchemaContract> getReferenceSchema() {
		return this.entitySchema.getReference(this.baseReference.getReferenceName());
	}

	@Nonnull
	@Override
	public ReferenceSchemaContract getReferenceSchemaOrThrow() {
		return this.entitySchema.getReference(this.baseReference.getReferenceName())
			.orElseThrow(() -> new EvitaInvalidUsageException("Reference schema is not available!"));
	}

	@Nonnull
	@Override
	public ReferenceBuilder setGroup(int primaryKey) {
		this.referenceGroupMutation = new SetReferenceGroupMutation(
			this.baseReference.getReferenceKey(),
			null, primaryKey
		);
		return this;
	}

	@Nonnull
	@Override
	public ReferenceBuilder setGroup(@Nullable String referencedEntity, int primaryKey) {
		this.referenceGroupMutation = new SetReferenceGroupMutation(
			this.baseReference.getReferenceKey(),
			referencedEntity, primaryKey
		);
		return this;
	}

	@Nonnull
	@Override
	public ReferenceBuilder removeGroup() {
		this.referenceGroupMutation = new RemoveReferenceGroupMutation(
			this.baseReference.getReferenceKey()
		);
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
	public <T extends Serializable> ReferenceBuilder setAttribute(@Nonnull String attributeName, @Nullable T attributeValue) {
		if (attributeValue == null) {
			return removeAttribute(attributeName);
		} else {
			final ReferenceSchemaContract referenceSchema = this.entitySchema.getReference(this.getReferenceName()).orElse(null);
			InitialReferenceBuilder.verifyAttributeIsInSchemaAndTypeMatch(
				this.entitySchema, referenceSchema, attributeName, attributeValue.getClass(), this.attributesBuilder.getLocationResolver()
			);
			this.attributesBuilder.setAttribute(attributeName, attributeValue);
			return this;
		}
	}

	@Nonnull
	@Override
	public <T extends Serializable> ReferenceBuilder setAttribute(@Nonnull String attributeName, @Nullable T[] attributeValue) {
		if (attributeValue == null) {
			return removeAttribute(attributeName);
		} else {
			final ReferenceSchemaContract referenceSchema = this.entitySchema.getReference(this.getReferenceName()).orElse(null);
			InitialReferenceBuilder.verifyAttributeIsInSchemaAndTypeMatch(
				this.entitySchema, referenceSchema, attributeName, attributeValue.getClass(), this.attributesBuilder.getLocationResolver()
			);
			this.attributesBuilder.setAttribute(attributeName, attributeValue);
			return this;
		}
	}

	@Nonnull
	@Override
	public ReferenceBuilder removeAttribute(@Nonnull String attributeName, @Nonnull Locale locale) {
		this.attributesBuilder.removeAttribute(attributeName, locale);
		return this;
	}

	@Nonnull
	@Override
	public <T extends Serializable> ReferenceBuilder setAttribute(@Nonnull String attributeName, @Nonnull Locale locale, @Nullable T attributeValue) {
		if (attributeValue == null) {
			return removeAttribute(attributeName, locale);
		} else {
			final ReferenceSchemaContract referenceSchema = this.entitySchema.getReference(this.getReferenceName()).orElse(null);
			InitialReferenceBuilder.verifyAttributeIsInSchemaAndTypeMatch(
				this.entitySchema, referenceSchema, attributeName, attributeValue.getClass(), locale, this.attributesBuilder.getLocationResolver()
			);
			this.attributesBuilder.setAttribute(attributeName, locale, attributeValue);
			return this;
		}
	}

	@Nonnull
	@Override
	public <T extends Serializable> ReferenceBuilder setAttribute(@Nonnull String attributeName, @Nonnull Locale locale, @Nullable T[] attributeValue) {
		if (attributeValue == null) {
			return removeAttribute(attributeName, locale);
		} else {
			final ReferenceSchemaContract referenceSchema = this.entitySchema.getReference(this.getReferenceName()).orElse(null);
			InitialReferenceBuilder.verifyAttributeIsInSchemaAndTypeMatch(
				this.entitySchema, referenceSchema, attributeName, attributeValue.getClass(), this.attributesBuilder.getLocationResolver()
			);
			this.attributesBuilder.setAttribute(attributeName, locale, attributeValue);
			return this;
		}
	}

	@Nonnull
	@Override
	public ReferenceBuilder mutateAttribute(@Nonnull AttributeMutation mutation) {
		this.attributesBuilder.mutateAttribute(mutation);
		return this;
	}

	@Override
	public boolean hasChanges() {
		return this.referenceGroupMutation != null || this.attributesBuilder.isThereAnyChangeInMutations();
	}

	@Nonnull
	@Override
	public Stream<? extends ReferenceMutation<?>> buildChangeSet() {
		final AtomicReference<ReferenceContract> builtReference = new AtomicReference<>(this.baseReference);
		return Stream.concat(
				this.referenceGroupMutation == null ?
					Stream.empty() :
					Stream.of(this.referenceGroupMutation)
						.filter(it -> {
							final ReferenceContract existingValue = builtReference.get();
							final ReferenceContract newReference = this.referenceGroupMutation.mutateLocal(this.entitySchema, existingValue);
							builtReference.set(newReference);
							return existingValue == null || newReference.version() > existingValue.version();
						}),
				this.attributesBuilder
					.buildChangeSet()
					.map(it ->
						new ReferenceAttributeMutation(
							this.baseReference.getReferenceKey(),
							it
						)
					)
			)
			.filter(Objects::nonNull);
	}

	@Nonnull
	@Override
	public ReferenceContract build() {
		final Optional<GroupEntityReference> newGroup = getGroup();
		final Attributes<AttributeSchemaContract> newAttributes = this.attributesBuilder.build();
		final boolean groupDiffers = this.baseReference.getGroup()
			.map(it -> it.differsFrom(newGroup.orElse(null)))
			.orElseGet(newGroup::isPresent);

		if (groupDiffers || this.attributesBuilder.isThereAnyChangeInMutations()) {
			return new Reference(
				this.entitySchema,
				version() + 1,
				getReferenceName(), getReferencedPrimaryKey(),
				getReferencedEntityType(), getReferenceCardinality(),
				newGroup.orElse(null),
				newAttributes,
				false
			);
		} else {
			return this.baseReference;
		}
	}
}
