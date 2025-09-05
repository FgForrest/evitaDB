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

package io.evitadb.api.requestResponse.data;

import io.evitadb.api.exception.ContextMissingException;
import io.evitadb.api.exception.EntityIsNotHierarchicalException;
import io.evitadb.api.query.require.HierarchyContent;
import io.evitadb.api.requestResponse.EvitaRequest;
import io.evitadb.api.requestResponse.data.mutation.reference.ReferenceKey;
import io.evitadb.api.requestResponse.data.structure.Entity;
import io.evitadb.api.requestResponse.schema.EntityAttributeSchemaContract;
import io.evitadb.api.requestResponse.schema.EntitySchemaContract;
import io.evitadb.dataType.Scope;
import io.evitadb.utils.MemoryMeasuringConstants;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.Collection;
import java.util.Collections;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import static java.util.Optional.of;

/**
 * Contract for classes that allow reading information about {@link Entity} instance.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
public interface EntityContract extends EntityClassifierWithParent,
	ContentComparator<EntityContract>,
	AttributesContract<EntityAttributeSchemaContract>,
	AssociatedDataContract,
	PricesContract,
	ReferencesContract,
	Versioned,
	Droppable {

	/**
	 * Returns schema of the entity, that fully describes its structure and capabilities. Schema is up-to-date to the
	 * moment entity was fetched from evitaDB.
	 *
	 * @return schema of the entity type
	 */
	@Nonnull
	EntitySchemaContract getSchema();

	/**
	 * Returns primary key of the entity that is UNIQUE among all other entities of the same type.
	 * Primary key may be null only when entity is created in case evitaDB is responsible for automatically assigning
	 * new primary key. Once entity is stored into evitaDB it MUST have non-null primary key. So the NULL can be
	 * returned only in the rare case when new entity is created in the client code and hasn't yet been stored to
	 * evitaDB.
	 *
	 * @return primary key of the entity or null if the entity is not yet stored in evitaDB
	 */
	@Nullable
	Integer getPrimaryKey();

	/**
	 * Returns true if entity hierarchy was fetched along with the entity. Calling this method before calling any
	 * other method that requires prices to be fetched will allow you to avoid {@link ContextMissingException}.
	 *
	 * Method also returns false if the entity is not allowed to be hierarchical by the schema. Checking this method
	 * also allows you to avoid {@link EntityIsNotHierarchicalException} in such case.
	 *
	 * @return true if hierarchy was fetched along with the entity
	 */
	boolean parentAvailable();

	/**
	 * Returns parent entity body. The entity fetch needs to be triggered using {@link HierarchyContent} requirement.
	 * The property allows to fetch entire parent axis of the entity to the root if requested.
	 *
	 * @return identification of the parent entity or empty if the entity is root
	 * @throws EntityIsNotHierarchicalException when {@link EntitySchemaContract#isWithHierarchy()} is false
	 * @throws ContextMissingException          when {@link HierarchyContent} is not part of the query requirements
	 */
	@Nonnull
	Optional<EntityClassifierWithParent> getParentEntity()
		throws EntityIsNotHierarchicalException, ContextMissingException;

	/**
	 * Returns set of locales this entity has any of localized data in. Although {@link EntitySchemaContract#getLocales()} may
	 * support wider range of the locales, this method returns only those that are used by data of this very entity
	 * instance.
	 *
	 * @return set of locales used by the entity
	 */
	@Nonnull
	Set<Locale> getAllLocales();

	/**
	 * Returns set of locales this entity has any of localized data in. The method further limits the output of
	 * {@link #getAllLocales()} by returning only those locales that were requested by the query. The locales here
	 * reflect the {@link EvitaRequest#getLocale()} and {@link EvitaRequest#getRequiredLocales()}.
	 *
	 * @return locales this entity has any of localized data in
	 */
	@Nonnull
	Set<Locale> getLocales();

	/**
	 * Returns the scope the entity is part of.
	 *
	 * @see Scope
	 * @return scope of the entity - either archived or live
	 */
	@Nonnull
	Scope getScope();

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
			(parentAvailable() && getParentEntity().isPresent() ? MemoryMeasuringConstants.INT_SIZE : 0) +
			// locales
			getLocales().stream().mapToInt(it -> MemoryMeasuringConstants.REFERENCE_SIZE).sum() +
			// attributes
			(!attributesAvailable() ? 0 : getAttributeValues().stream().mapToInt(AttributeValue::estimateSize).sum()) +
			// attributes
			(!associatedDataAvailable() ? 0 : getAssociatedDataValues().stream().mapToInt(AssociatedDataValue::estimateSize).sum()) +
			// price inner record handling
			MemoryMeasuringConstants.BYTE_SIZE +
			// prices
			(!pricesAvailable() ? 0 : getPrices().stream().mapToInt(PriceContract::estimateSize).sum()) +
			// references
			(!referencesAvailable() ? 0 : getReferences().stream().mapToInt(ReferenceContract::estimateSize).sum());

	}

	/**
	 * Method returns true if any entity inner data differs from other entity.
	 */
	@Override
	default boolean differsFrom(@Nullable EntityContract otherEntity) {
		if (this == otherEntity) return false;
		if (otherEntity == null) return true;

		if (!Objects.equals(getPrimaryKey(), otherEntity.getPrimaryKey())) return true;
		if (version() != otherEntity.version()) return true;
		if (dropped() != otherEntity.dropped()) return true;
		if (!getType().equals(otherEntity.getType())) return true;
		if (parentAvailable() != otherEntity.parentAvailable()) return true;
		if (parentAvailable()) {
			if (getParentEntity().isPresent() != otherEntity.getParentEntity().isPresent()) return true;
			if (getParentEntity().isPresent() && !Objects.equals(getParentEntity().get().getPrimaryKey(), otherEntity.getParentEntity().get().getPrimaryKey())) return true;
		}
		if (AttributesContract.anyAttributeDifferBetween(this, otherEntity)) return true;
		if (AssociatedDataContract.anyAssociatedDataDifferBetween(this, otherEntity)) return true;
		if (PricesContract.anyPriceOrStrategyDifferBetween(this, otherEntity)) return true;
		if (!getLocales().equals(otherEntity.getLocales())) return true;

		final Collection<ReferenceContract> thisReferences = referencesAvailable() ? getReferences() : Collections.emptyList();
		final Collection<ReferenceContract> otherReferences = otherEntity.referencesAvailable() ? otherEntity.getReferences() : Collections.emptyList();
		if (thisReferences.size() != otherReferences.size()) return true;
		for (ReferenceContract thisReference : thisReferences) {
			final ReferenceKey thisKey = thisReference.getReferenceKey();
			if (otherEntity.getReference(thisKey)
				.map(thisReference::differsFrom)
				.orElse(true)
			) {
				return true;
			}
		}
		return false;
	}

	/**
	 * Method prints details about entity as single line. Use in {@link Object#toString()} implementations.
	 */
	default String describe() {
		final Set<Locale> locales = getLocales();
		return (dropped() ? "❌ " : "") +
			"Entity " + getType() + " ID=" + getPrimaryKey() +
			(parentAvailable() ? getParentEntity().map(it -> ", ↰ " + it.getPrimaryKey()).orElse("") : "") +
			(referencesAvailable() ? ", " + of(getReferences()).filter(it -> !it.isEmpty()).map(it -> it.stream().map(ReferenceContract::toString).collect(Collectors.joining(", "))).orElse("") : "") +
			(attributesAvailable() ? of(getAttributeValues()).filter(it -> !it.isEmpty()).map(it -> ", " + it.stream().map(AttributeValue::toString).collect(Collectors.joining(", "))).orElse("") : "") +
			(associatedDataAvailable() ? of(getAssociatedDataValues()).filter(it -> !it.isEmpty()).map(it -> ", " + it.stream().map(AssociatedDataValue::toString).collect(Collectors.joining(", "))).orElse("") : "") +
			(pricesAvailable() ? of(getPrices()).filter(it -> !it.isEmpty()).map(it -> ", " + it.stream().map(Object::toString).collect(Collectors.joining(", "))).orElse(null) : "") +
			(locales.isEmpty() ? "" : ", localized to " + locales.stream().map(Locale::toString).collect(Collectors.joining(", ")));
	}

}
