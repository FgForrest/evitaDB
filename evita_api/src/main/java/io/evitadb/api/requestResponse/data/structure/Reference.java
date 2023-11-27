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

import io.evitadb.api.query.filter.FacetHaving;
import io.evitadb.api.requestResponse.data.AttributesContract;
import io.evitadb.api.requestResponse.data.ReferenceContract;
import io.evitadb.api.requestResponse.data.ReferenceEditor.ReferenceBuilder;
import io.evitadb.api.requestResponse.data.SealedEntity;
import io.evitadb.api.requestResponse.data.mutation.reference.ReferenceKey;
import io.evitadb.api.requestResponse.extraResult.FacetSummary.FacetStatistics;
import io.evitadb.api.requestResponse.schema.AttributeSchemaContract;
import io.evitadb.api.requestResponse.schema.AttributeSchemaProvider;
import io.evitadb.api.requestResponse.schema.Cardinality;
import io.evitadb.api.requestResponse.schema.EntitySchemaContract;
import io.evitadb.api.requestResponse.schema.ReferenceSchemaContract;
import io.evitadb.api.requestResponse.schema.dto.ReferenceSchema;
import io.evitadb.exception.EvitaInvalidUsageException;
import lombok.EqualsAndHashCode;
import lombok.experimental.Delegate;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import javax.annotation.concurrent.Immutable;
import javax.annotation.concurrent.ThreadSafe;
import java.io.Serial;
import java.io.Serializable;
import java.util.Collection;
import java.util.Collections;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import static java.util.Optional.ofNullable;

/**
 * References refer to other entities (of same or different entity type).
 * Allows entity filtering (but not sorting) of the entities by using {@link FacetHaving} query
 * and statistics computation if when {@link FacetStatistics} requirement is used. Reference
 * is uniquely represented by int positive number (max. 2<sup>63</sup>-1) and {@link Serializable} entity type and can be
 * part of multiple reference groups, that are also represented by int and {@link Serializable} entity type.
 *
 * Reference id in one entity is unique and belongs to single reference group id. Among multiple entities reference may be part
 * of different reference groups. Referenced entity type may represent type of another Evita entity or may refer
 * to anything unknown to Evita that posses unique int key and is maintained by external systems (fe. tag assignment,
 * group assignment, category assignment, stock assignment and so on). Not all these data needs to be present in
 * Evita.
 *
 * References may carry additional key-value data linked to this entity relation (fe. item count present on certain stock).
 *
 * Class is immutable on purpose - we want to support caching the entities in a shared cache and accessed by many threads.
 * For altering the contents use {@link ReferenceBuilder}.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
@Immutable
@ThreadSafe
@EqualsAndHashCode(of = {"version", "referenceKey"})
public class Reference implements ReferenceContract {
	@Serial private static final long serialVersionUID = -2624502273901281240L;

	/**
	 * Entity schema definition.
	 */
	private final EntitySchemaContract entitySchema;
	/**
	 * Contains version of this object and gets increased with any entity update. Allows to execute
	 * optimistic locking i.e. avoiding parallel modifications.
	 */
	private final int version;
	/**
	 * Contains primary unique identifier of the Reference. The business key consists of
	 * {@link ReferenceSchemaContract#getName()} and {@link Entity#getPrimaryKey()}.
	 */
	private final ReferenceKey referenceKey;
	/**
	 * Contains information about reference cardinality. This value is usually NULL except the case when the reference
	 * is created for the first time and {@link io.evitadb.api.requestResponse.schema.EvolutionMode#ADDING_REFERENCES} is allowed.
	 */
	private final Cardinality referenceCardinality;
	/**
	 * Contains information about target entity type. This value is usually NULL except the case when the reference
	 * is created for the first time and {@link io.evitadb.api.requestResponse.schema.EvolutionMode#ADDING_REFERENCES} is allowed.
	 */
	private final String referencedEntityType;
	/**
	 * Reference to the Evita {@link Entity} or any external entity not maintained by Evita.
	 * Facet group aggregates facets of the same type - for example by color, size, brand or whatever else.
	 */
	private final GroupEntityReference group;
	/**
	 * Properties valid only for this relation. Can be used to carry information about order (i.e. order of the entity
	 * "product" in certain "category" entity, same "product" may have entirely different order in relation to different
	 * "category").
	 */
	@Delegate(types = AttributesContract.class)
	private final Attributes<AttributeSchemaContract> attributes;
	/**
	 * Contains TRUE if facet reference was dropped - i.e. removed. Facets are not removed (unless tidying process
	 * does it), but are lying among other facets with tombstone flag. Dropped facets can be overwritten by
	 * a new value continuing with the versioning where it was stopped for the last time.
	 */
	private final boolean dropped;

	/**
	 * Creates new reference with given parameters. This method is used only as a temporal schema until it's created.
	 */
	@Nonnull
	public static ReferenceSchema createImplicitSchema(
		@Nonnull String referenceName,
		@Nullable String referencedEntityType,
		@Nullable Cardinality cardinality,
		@Nullable GroupEntityReference group
	) {
		return ReferenceSchema._internalBuild(
			referenceName, referencedEntityType, false, cardinality,
			ofNullable(group).map(GroupEntityReference::getType).orElse(null), false,
			false, false
		);
	}

	public Reference(
		@Nonnull EntitySchemaContract entitySchema,
		@Nonnull String referenceName,
		int referencedEntityPrimaryKey,
		@Nullable String referencedEntityType,
		@Nullable Cardinality cardinality,
		@Nullable GroupEntityReference group
	) {
		this.version = 1;
		this.entitySchema = entitySchema;
		this.referenceKey = new ReferenceKey(referenceName, referencedEntityPrimaryKey);
		this.referenceCardinality = cardinality;
		this.referencedEntityType = referencedEntityType;
		this.group = group;
		final Optional<ReferenceSchemaContract> reference = entitySchema.getReference(referenceName);
		this.attributes = new ReferenceAttributes(
			entitySchema,
			reference.orElseGet(
				() -> createImplicitSchema(referenceName, referencedEntityType, cardinality, group)
			),
			Collections.emptyMap(),
			reference
				.map(AttributeSchemaProvider::getAttributes)
				.orElse(Collections.emptyMap())
		);
		this.dropped = false;
	}

	public Reference(
		@Nonnull EntitySchemaContract entitySchema,
		int version,
		@Nonnull String referenceName,
		int referencedEntityPrimaryKey,
		@Nullable String referencedEntityType,
		@Nullable Cardinality cardinality,
		@Nullable GroupEntityReference group,
		boolean dropped
	) {
		this.entitySchema = entitySchema;
		this.version = version;
		this.referenceKey = new ReferenceKey(referenceName, referencedEntityPrimaryKey);
		this.referenceCardinality = cardinality;
		this.referencedEntityType = referencedEntityType;
		this.group = group;
		final Optional<ReferenceSchemaContract> reference = entitySchema.getReference(referenceName);
		this.attributes = new ReferenceAttributes(
			entitySchema,
			reference.orElseGet(
				() -> createImplicitSchema(referenceName, referencedEntityType, cardinality, group)
			),
			Collections.emptyMap(),
			reference
				.map(AttributeSchemaProvider::getAttributes)
				.orElse(Collections.emptyMap())
		);
		this.dropped = dropped;
	}

	public Reference(
		@Nonnull EntitySchemaContract entitySchema,
		int version,
		@Nonnull String referenceName,
		int referencedEntityPrimaryKey,
		@Nullable String referencedEntityType,
		@Nullable Cardinality cardinality,
		@Nullable GroupEntityReference group,
		@Nonnull Attributes<AttributeSchemaContract> attributes,
		boolean dropped
	) {
		this.entitySchema = entitySchema;
		this.version = version;
		this.referenceKey = new ReferenceKey(referenceName, referencedEntityPrimaryKey);
		this.referenceCardinality = cardinality;
		this.referencedEntityType = referencedEntityType;
		this.group = group;
		this.attributes = attributes;
		this.dropped = dropped;
	}

	public Reference(
		@Nonnull EntitySchemaContract entitySchema,
		int version,
		@Nonnull String referenceName,
		int referencedEntityPrimaryKey,
		@Nullable String referencedEntityType,
		@Nullable Cardinality cardinality,
		@Nullable GroupEntityReference group,
		@Nonnull Map<AttributeKey, AttributeValue> attributes,
		boolean dropped
	) {
		this.entitySchema = entitySchema;
		this.version = version;
		this.referenceKey = new ReferenceKey(referenceName, referencedEntityPrimaryKey);
		this.referenceCardinality = cardinality;
		this.referencedEntityType = referencedEntityType;
		this.group = group;
		final Optional<ReferenceSchemaContract> reference = entitySchema.getReference(referenceName);
		this.attributes = new ReferenceAttributes(
			entitySchema,
			reference.orElseGet(
				() -> createImplicitSchema(referenceName, referencedEntityType, cardinality, group)
			),
			attributes,
			reference
				.map(AttributeSchemaProvider::getAttributes)
				.orElse(Collections.emptyMap())
		);
		this.dropped = dropped;
	}

	public Reference(
		@Nonnull EntitySchemaContract entitySchema,
		int version,
		@Nonnull String referenceName,
		int referencedEntityPrimaryKey,
		@Nullable String referencedEntityType,
		@Nullable Cardinality cardinality,
		@Nullable GroupEntityReference group,
		@Nonnull Collection<AttributeValue> attributes,
		boolean dropped
	) {
		this.entitySchema = entitySchema;
		this.version = version;
		this.referenceKey = new ReferenceKey(referenceName, referencedEntityPrimaryKey);
		this.referenceCardinality = cardinality;
		this.referencedEntityType = referencedEntityType;
		this.group = group;
		final Optional<ReferenceSchemaContract> reference = entitySchema.getReference(referenceName);
		this.attributes = new ReferenceAttributes(
			entitySchema,
			reference.orElseGet(
				() -> createImplicitSchema(referenceName, referencedEntityType, cardinality, group)
			),
			attributes,
			reference
				.map(AttributeSchemaProvider::getAttributes)
				.orElse(Collections.emptyMap())
		);
		this.dropped = dropped;
	}

	public Reference(
		@Nonnull EntitySchemaContract entitySchema,
		@Nonnull String referenceName,
		int referencedEntityPrimaryKey,
		@Nullable String referencedEntityType,
		@Nullable Cardinality cardinality,
		@Nullable GroupEntityReference group,
		@Nonnull Attributes<AttributeSchemaContract> attributes
	) {
		this.entitySchema = entitySchema;
		this.version = 1;
		this.referenceKey = new ReferenceKey(referenceName, referencedEntityPrimaryKey);
		this.referenceCardinality = cardinality;
		this.referencedEntityType = referencedEntityType;
		this.group = group;
		this.attributes = attributes;
		this.dropped = false;
	}

	@Nonnull
	@Override
	public ReferenceKey getReferenceKey() {
		return referenceKey;
	}

	@Nonnull
	@Override
	public Optional<SealedEntity> getReferencedEntity() {
		return Optional.empty();
	}

	@Nonnull
	@Override
	public String getReferencedEntityType() {
		return Objects.requireNonNull(
			getReferenceSchema()
				.map(ReferenceSchemaContract::getReferencedEntityType)
				.orElse(referencedEntityType)
		);
	}

	@Nonnull
	@Override
	public Cardinality getReferenceCardinality() {
		return Objects.requireNonNull(
			getReferenceSchema()
				.map(ReferenceSchemaContract::getCardinality)
				.orElse(referenceCardinality)
		);
	}

	@Nonnull
	@Override
	public Optional<GroupEntityReference> getGroup() {
		return ofNullable(group);
	}

	@Nonnull
	@Override
	public Optional<SealedEntity> getGroupEntity() {
		return Optional.empty();
	}

	@Nonnull
	@Override
	public Optional<ReferenceSchemaContract> getReferenceSchema() {
		return this.entitySchema.getReference(referenceKey.referenceName());
	}

	@Nonnull
	@Override
	public ReferenceSchemaContract getReferenceSchemaOrThrow() {
		return getReferenceSchema()
			.orElseThrow(() -> new EvitaInvalidUsageException("Reference schema is not available!"));
	}

	@Override
	public boolean dropped() {
		return dropped;
	}

	@Override
	public int version() {
		return version;
	}

	@Override
	public String toString() {
		return (dropped ? "❌ " : "") +
			"References `" + referenceKey.referenceName() + "` " + referenceKey.primaryKey() +
			(group == null ? "" : " in " + group) +
			(attributes.attributesAvailable() ? ", attrs: " + attributes : "");
	}

}
