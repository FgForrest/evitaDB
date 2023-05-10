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

package io.evitadb.api.requestResponse.data;

import io.evitadb.api.query.filter.HierarchyWithin;
import io.evitadb.api.query.require.HierarchyContent;
import io.evitadb.api.query.require.HierarchyOfReference;
import io.evitadb.api.query.require.HierarchyOfSelf;
import io.evitadb.api.requestResponse.EvitaRequest;
import io.evitadb.api.requestResponse.data.mutation.reference.ReferenceKey;
import io.evitadb.api.requestResponse.data.structure.Entity;
import io.evitadb.api.requestResponse.data.structure.Reference;
import io.evitadb.api.requestResponse.schema.EntitySchemaContract;
import io.evitadb.utils.MemoryMeasuringConstants;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.Collection;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Contract for classes that allow reading information about {@link Entity} instance.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
public interface EntityContract extends EntityClassifierWithParent, ContentComparator<EntityContract>, AttributesContract, AssociatedDataContract, PricesContract, Versioned, Droppable {

	/**
	 * Returns schema of the entity, that fully describes its structure and capabilities. Schema is up-to-date to the
	 * moment entity was fetched from evitaDB.
	 */
	@Nonnull
	EntitySchemaContract getSchema();

	/**
	 * Returns primary key of the entity that is UNIQUE among all other entities of the same type.
	 * Primary key may be null only when entity is created in case evitaDB is responsible for automatically assigning
	 * new primary key. Once entity is stored into evitaDB it MUST have non-null primary key. So the NULL can be
	 * returned only in the rare case when new entity is created in the client code and hasn't yet been stored to
	 * evitaDB.
	 */
	@Nullable
	Integer getPrimaryKey();

	/**
	 * Returns hierarchy information about the entity. Hierarchy information allows to compose hierarchy tree composed
	 * of entities of the same type. Referenced entity is always entity of the same type. Referenced entity must be
	 * already present in the evitaDB and must also have hierarchy placement set. Root `parentPrimaryKey` (i.e. parent
	 * for top-level hierarchical placements) is null.
	 *
	 * Entities may be organized in hierarchical fashion. That means that entity may refer to single parent entity and
	 * may be referred by multiple child entities. Hierarchy is always composed of entities of same type.
	 * Each entity must be part of at most single hierarchy (tree).
	 * Hierarchy can limit returned entities by using filtering constraints {@link HierarchyWithin}. It's also used for
	 * computation of extra data - such as {@link HierarchyOfSelf}. It can also invert type of returned entities in case
	 * requirement {@link HierarchyOfReference} is used.
	 */
	@Nonnull
	OptionalInt getParent();

	/**
	 * Returns parent entity body. The entity fetch needs to be triggered using {@link HierarchyContent} requirement.
	 * The property allows to fetch entire parent axis of the entity to the root if requested.
	 */
	@Nonnull
	Optional<EntityClassifierWithParent> getParentEntity();

	/**
	 * Returns collection of {@link Reference} of this entity. The references represent relations to other evitaDB
	 * entities or external entities in different systems.
	 */
	@Nonnull
	Collection<ReferenceContract> getReferences();

	/**
	 * Returns collection of {@link Reference} to certain type of other entities. References represent relations to
	 * other evitaDB entities or external entities in different systems.
	 */
	@Nonnull
	Collection<ReferenceContract> getReferences(@Nonnull String referenceName);

	/**
	 * Returns single {@link Reference} instance that is referencing passed entity type with certain primary key.
	 * The references represent relations to other evitaDB entities or external entities in different systems.
	 */
	@Nonnull
	Optional<ReferenceContract> getReference(@Nonnull String referenceName, int referencedEntityId);

	/**
	 * Returns set of locales this entity has any of localized data in. Although {@link EntitySchemaContract#getLocales()} may
	 * support wider range of the locales, this method returns only those that are used by data of this very entity
	 * instance.
	 */
	@Nonnull
	Set<Locale> getAllLocales();

	/**
	 * Returns set of locales this entity has any of localized data in. The method further limits the output of
	 * {@link #getAllLocales()} by returning only those locales that were requested by the query. The locales here
	 * reflect the {@link EvitaRequest#getLocale()} and {@link EvitaRequest#getRequiredLocales()}.
	 */
	@Nonnull
	Set<Locale> getLocales();

	/**
	 * Method returns gross estimation of the in-memory size of this instance. The estimation is expected not to be
	 * a precise one. Please use constants from {@link MemoryMeasuringConstants} for size computation.
	 */
	default int estimateSize() {
		return MemoryMeasuringConstants.OBJECT_HEADER_SIZE +
			// primary key
			MemoryMeasuringConstants.INT_SIZE +
			// version
			MemoryMeasuringConstants.INT_SIZE +
			// reference to the schema
			MemoryMeasuringConstants.REFERENCE_SIZE +
			// dropped
			MemoryMeasuringConstants.BYTE_SIZE +
			// type - we should assume the key is stored in memory only once (should be enum or String)
			MemoryMeasuringConstants.REFERENCE_SIZE +
			// hierarchical placement
			getParent().stream().mapToObj(it -> MemoryMeasuringConstants.INT_SIZE).findAny().orElse(0) +
			// locales
			getLocales().stream().mapToInt(it -> MemoryMeasuringConstants.REFERENCE_SIZE).sum() +
			// attributes
			getAttributeValues().stream().mapToInt(AttributeValue::estimateSize).sum() +
			// attributes
			getAssociatedDataValues().stream().mapToInt(AssociatedDataValue::estimateSize).sum() +
			// price inner record handling
			MemoryMeasuringConstants.BYTE_SIZE +
			// prices
			getPrices().stream().mapToInt(PriceContract::estimateSize).sum() +
			// references
			getReferences().stream().mapToInt(ReferenceContract::estimateSize).sum();

	}

	/**
	 * Method returns true if any entity inner data differs from other entity.
	 */
	@Override
	default boolean differsFrom(@Nullable EntityContract otherEntity) {
		if (this == otherEntity) return false;
		if (otherEntity == null) return true;

		if (!Objects.equals(getPrimaryKey(), otherEntity.getPrimaryKey())) return true;
		if (getVersion() != otherEntity.getVersion()) return true;
		if (isDropped() != otherEntity.isDropped()) return true;
		if (!getType().equals(otherEntity.getType())) return true;
		if (getParent().isPresent() != otherEntity.getParent().isPresent()) return true;
		if (getParent().isPresent() && getParent().getAsInt() != otherEntity.getParent().getAsInt()) return true;
		if (AttributesContract.anyAttributeDifferBetween(this, otherEntity)) return true;
		if (AssociatedDataContract.anyAssociatedDataDifferBetween(this, otherEntity)) return true;
		if (getPriceInnerRecordHandling() != otherEntity.getPriceInnerRecordHandling()) return true;
		if (PricesContract.anyPriceDifferBetween(this, otherEntity)) return true;
		if (!getLocales().equals(otherEntity.getLocales())) return true;

		final Collection<ReferenceContract> thisReferences = getReferences();
		final Collection<ReferenceContract> otherReferences = otherEntity.getReferences();
		if (thisReferences.size() != otherReferences.size()) return true;
		for (ReferenceContract thisReference : thisReferences) {
			final ReferenceKey thisKey = thisReference.getReferenceKey();
			if (otherEntity.getReference(thisKey.referenceName(), thisKey.primaryKey())
				.map(thisReference::differsFrom)
				.orElse(true)) {
				return true;
			}
		}
		return false;
	}

	/**
	 * Method prints details about entity as single line. Use in {@link Object#toString()} implementations.
	 */
	default String describe() {
		final Collection<ReferenceContract> references = getReferences();
		final Collection<AttributeValue> attributeValues = getAttributeValues();
		final Collection<AssociatedDataValue> associatedDataValues = getAssociatedDataValues();
		final Collection<PriceContract> prices = getPrices();
		final Set<Locale> locales = getLocales();
		return (isDropped() ? "❌ " : "") +
			"Entity " + getType() + " ID=" + getPrimaryKey() +
			getParent().stream().mapToObj(it -> ", ↰ " + it).findAny().orElse("") +
			(references.isEmpty() ? "" : ", " + references.stream().map(ReferenceContract::toString).collect(Collectors.joining(", "))) +
			(attributeValues.isEmpty() ? "" : ", " + attributeValues.stream().map(AttributeValue::toString).collect(Collectors.joining(", "))) +
			(associatedDataValues.isEmpty() ? "" : ", " + associatedDataValues.stream().map(AssociatedDataValue::toString).collect(Collectors.joining(", "))) +
			(prices.isEmpty() ? "" : ", " + prices.stream().map(Object::toString).collect(Collectors.joining(", "))) +
			(locales.isEmpty() ? "" : ", localized to " + locales.stream().map(Locale::toString).collect(Collectors.joining(", ")));
	}
}
