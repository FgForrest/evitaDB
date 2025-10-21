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
import io.evitadb.api.requestResponse.data.ReferencesEditor.ReferencesBuilder;
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
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

import static java.util.Optional.empty;
import static java.util.Optional.of;
import static java.util.Optional.ofNullable;

/**
 * Builder that is used to alter existing {@link Reference}.
 *
 * The builder accumulates attribute and group mutations for an already existing reference and can
 * produce a new immutable {@link Reference} instance reflecting those changes. If no change is
 * staged, {@code build()} returns the original reference instance.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
public class ExistingReferenceBuilder implements ReferenceBuilder, Serializable {
	@Serial private static final long serialVersionUID = 4611697377656713570L;
	/**
	 * Reference key of the reference being mutated.
	 */
	private final ReferenceKey referenceKey;
	/**
	 * Reference instance this builder wraps and mutates logically. Acts as the baseline state that
	 * mutations are applied against when producing the final {@link Reference} in {@code build()}.
	 */
	@Getter private final ReferenceContract baseReference;
	/**
	 * Entity schema of the owning entity. Used to resolve reference schemas and validate mutations.
	 */
	@Getter private final EntitySchemaContract entitySchema;
	/**
	 * Delegate that handles attribute mutations for this reference. Exposed via {@link AttributesContract}
	 * through Lombok {@code @Delegate} to provide attribute editing API.
	 */
	@Delegate(types = AttributesContract.class)
	private final ExistingReferenceAttributesBuilder attributesBuilder;
	/**
	 * Pending mutation changing the reference group (set/remove). When null, no group change is staged.
	 */
	private ReferenceMutation<?> referenceGroupMutation;

	/**
	 * Creates a builder for an existing reference without any pre-applied mutations.
	 *
	 * The provided `attributeTypes` may speed up schema resolution of attributes for this
	 * reference; if empty, schemas may be created implicitly if allowed by the schema.
	 *
	 * @param baseReference  reference to wrap and mutate, must be non-null
	 * @param entitySchema   schema of the owning entity, must be non-null
	 * @param attributeTypes known attribute schemas by name for the reference, must be non-null
	 */
	public ExistingReferenceBuilder(
		@Nonnull ReferenceContract baseReference,
		@Nonnull EntitySchemaContract entitySchema,
		@Nonnull Map<String, AttributeSchemaContract> attributeTypes
	) {
		this(
			baseReference.getReferenceKey(),
			baseReference,
			entitySchema,
			Collections.emptyList(),
			attributeTypes
		);
	}

	/**
	 * Creates a builder for an existing reference without any pre-applied mutations.
	 *
	 * The provided `attributeTypes` may speed up schema resolution of attributes for this
	 * reference; if empty, schemas may be created implicitly if allowed by the schema.
	 *
	 * @param referenceKey  reference key of the reference to wrap and mutate, must be non-null
	 * @param baseReference reference to wrap and mutate, must be non-null
	 * @param entitySchema schema of the owning entity, must be non-null
	 * @param attributeTypes known attribute schemas by name for the reference, must be non-null
	 */
	public ExistingReferenceBuilder(
		@Nonnull ReferenceKey referenceKey,
		@Nonnull ReferenceContract baseReference,
		@Nonnull EntitySchemaContract entitySchema,
		@Nonnull Map<String, AttributeSchemaContract> attributeTypes
	) {
		this(
			referenceKey,
			baseReference,
			entitySchema,
			Collections.emptyList(),
			attributeTypes
		);
	}

	/**
	 * Creates a builder initialized with an existing set of local mutations.
	 *
	 * Supported mutation types are:
	 * - {@link AttributeMutation} for attribute changes on the reference
	 * - {@link SetReferenceGroupMutation} and {@link RemoveReferenceMutation} for group changes
	 *
	 * Any other mutation type will cause an {@link io.evitadb.exception.EvitaInvalidUsageException}.
	 *
	 * @param baseReference  reference to wrap and mutate, must be non-null
	 * @param entitySchema   schema of the owning entity, must be non-null
	 * @param mutations      local mutations to apply, must be non-null
	 * @param attributeTypes known attribute schemas by name for the reference, must be non-null
	 */
	public ExistingReferenceBuilder(
		@Nonnull ReferenceContract baseReference,
		@Nonnull EntitySchemaContract entitySchema,
		@Nonnull Collection<? extends LocalMutation<?, ?>> mutations,
		@Nonnull Map<String, AttributeSchemaContract> attributeTypes
	) {
		this(
			baseReference.getReferenceKey(),
			baseReference,
			entitySchema,
			mutations,
			attributeTypes
		);
	}

	/**
	 * Creates a builder initialized with an existing set of local mutations.
	 *
	 * Supported mutation types are:
	 * - {@link AttributeMutation} for attribute changes on the reference
	 * - {@link SetReferenceGroupMutation} and {@link RemoveReferenceMutation} for group changes
	 *
	 * Any other mutation type will cause an {@link io.evitadb.exception.EvitaInvalidUsageException}.
	 *
	 * @param referenceKey  reference key of the reference to wrap and mutate, must be non-null
	 * @param baseReference  reference to wrap and mutate, must be non-null
	 * @param entitySchema   schema of the owning entity, must be non-null
	 * @param mutations      local mutations to apply, must be non-null
	 * @param attributeTypes known attribute schemas by name for the reference, must be non-null
	 */
	public ExistingReferenceBuilder(
		@Nonnull ReferenceKey referenceKey,
		@Nonnull ReferenceContract baseReference,
		@Nonnull EntitySchemaContract entitySchema,
		@Nonnull Collection<? extends LocalMutation<?, ?>> mutations,
		@Nonnull Map<String, AttributeSchemaContract> attributeTypes
	) {
		this.referenceKey = referenceKey;
		this.baseReference = baseReference;
		this.entitySchema = entitySchema;
		final List<AttributeMutation> attributeMutations = new ArrayList<>(mutations.size());
		for (LocalMutation<?, ?> mutation : mutations) {
			if (mutation instanceof ReferenceAttributeMutation ram) {
				attributeMutations.add(ram.getAttributeMutation());
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
				() -> ReferencesBuilder.createImplicitSchema(
					entitySchema,
					baseReference.getReferenceName(),
					baseReference.getReferencedEntityType(),
					baseReference.getReferenceCardinality(),
					baseReference.getGroup().orElse(null)
				)
			),
			baseReference.getAttributeValues(),
			attributeTypes,
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
		return this.referenceKey;
	}

	@Nonnull
	@Override
	public Optional<SealedEntity> getReferencedEntity() {
		return this.baseReference.getReferencedEntity();
	}

	@Nonnull
	@Override
	public String getReferencedEntityType() {
		return this.baseReference.getReferencedEntityType();
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
			.map(it -> ofNullable(this.referenceGroupMutation).map(
				fgm -> fgm.mutateLocal(this.entitySchema, this.baseReference).getGroup()).orElseGet(() -> of(it)))
			.orElseGet(() -> ofNullable(this.referenceGroupMutation).flatMap(
				fgm -> fgm.mutateLocal(this.entitySchema, this.baseReference).getGroup()));
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
			this.getReferenceKey(),
			null, primaryKey
		);
		return this;
	}

	@Nonnull
	@Override
	public ReferenceBuilder setGroup(@Nullable String referencedEntity, int primaryKey) {
		this.referenceGroupMutation = new SetReferenceGroupMutation(
			this.getReferenceKey(),
			referencedEntity, primaryKey
		);
		return this;
	}

	@Nonnull
	@Override
	public ReferenceBuilder removeGroup() {
		this.referenceGroupMutation = new RemoveReferenceGroupMutation(
			this.getReferenceKey()
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
	public <T extends Serializable> ReferenceBuilder setAttribute(
		@Nonnull String attributeName, @Nullable T attributeValue) {
		this.attributesBuilder.setAttribute(attributeName, attributeValue);
		return this;
	}

	@Nonnull
	@Override
	public <T extends Serializable> ReferenceBuilder setAttribute(
		@Nonnull String attributeName, @Nullable T[] attributeValue) {
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
		@Nonnull String attributeName, @Nonnull Locale locale, @Nullable T attributeValue) {
		this.attributesBuilder.setAttribute(attributeName, locale, attributeValue);
		return this;
	}

	@Nonnull
	@Override
	public <T extends Serializable> ReferenceBuilder setAttribute(
		@Nonnull String attributeName, @Nonnull Locale locale, @Nullable T[] attributeValue) {
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
		final AtomicReference<ReferenceContract> builtReference = new AtomicReference<>(this.baseReference);
		return Stream.concat(
				this.referenceGroupMutation == null ?
					Stream.empty() :
					Stream.of(this.referenceGroupMutation)
						.filter(it -> {
							final ReferenceContract existingValue = builtReference.get();
							final ReferenceContract newReference = this.referenceGroupMutation.mutateLocal(
								this.entitySchema, existingValue);
							builtReference.set(newReference);
							return existingValue == null || newReference.version() > existingValue.version();
						}),
				this.attributesBuilder
					.buildChangeSet()
					.map(it ->
						     new ReferenceAttributeMutation(
							     this.getReferenceKey(),
							     it
						     )
					)
			)
			.filter(Objects::nonNull);
	}

	@Override
	public boolean hasChanges() {
		return this.referenceGroupMutation != null || this.attributesBuilder.isThereAnyChangeInMutations();
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
				this.baseReference.getReferenceSchemaOrThrow(),
				version() + 1,
				getReferenceKey(),
				newGroup.orElse(null),
				newAttributes,
				false
			);
		} else {
			return this.baseReference;
		}
	}

}
