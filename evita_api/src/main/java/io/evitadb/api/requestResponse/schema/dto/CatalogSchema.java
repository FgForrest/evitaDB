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

package io.evitadb.api.requestResponse.schema.dto;

import io.evitadb.api.requestResponse.schema.AttributeSchemaContract;
import io.evitadb.api.requestResponse.schema.CatalogSchemaContract;
import io.evitadb.api.requestResponse.schema.EntitySchemaContract;
import io.evitadb.api.requestResponse.schema.GlobalAttributeSchemaContract;
import io.evitadb.utils.NamingConvention;
import lombok.EqualsAndHashCode;
import lombok.Getter;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import javax.annotation.concurrent.Immutable;
import javax.annotation.concurrent.ThreadSafe;
import java.io.Serial;
import java.util.Collections;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

import static io.evitadb.api.requestResponse.schema.dto.EntitySchema._internalGenerateNameVariantIndex;
import static java.util.Optional.ofNullable;

/**
 * Catalog schema contains structural information about one Evita catalog. It maintains the collection of globally
 * unique attributes shared among multiple {@link EntitySchemaContract}.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2022
 * @see io.evitadb.api.CatalogContract
 */
@Immutable
@ThreadSafe
@EqualsAndHashCode(of = {"version", "name"})
public final class CatalogSchema implements CatalogSchemaContract {
	@Serial private static final long serialVersionUID = -1582409928666780012L;

	@Getter private final int version;
	@Getter @Nonnull private final String name;
	@Getter private final Map<NamingConvention, String> nameVariants;
	@Getter @Nullable private final String description;
	@Nonnull private final Map<String, GlobalAttributeSchema> attributes;
	/**
	 * Index of attribute names that allows to quickly lookup attribute schemas by attribute name in specific naming
	 * convention. Key is the name in specific name convention, value is array of size {@link NamingConvention#values()}
	 * where reference to {@link AttributeSchemaContract} is placed on index of naming convention that matches the key.
	 */
	private final Map<String, GlobalAttributeSchema[]> attributeNameIndex;
	/**
	 * Function allows accessing actual entity schemas of catalog entity collections.
	 */
	@Getter
	@Nonnull
	private final Function<String, EntitySchemaContract> entitySchemaAccessor;

	/**
	 * This method is for internal purposes only. It could be used for reconstruction of AttributeSchema from
	 * different package than current, but still internal code of the Evita ecosystems.
	 *
	 * Do not use this method from in the client code!
	 */
	public static CatalogSchema _internalBuild(
		@Nonnull String name,
		@Nonnull Map<NamingConvention, String> nameVariants,
		@Nonnull Function<String, EntitySchemaContract> entitySchemaAccessor
	) {
		return new CatalogSchema(
			1, name, nameVariants, null,
			Collections.emptyMap(),
			entitySchemaAccessor
		);
	}

	/**
	 * This method is for internal purposes only. It could be used for reconstruction of AttributeSchema from
	 * different package than current, but still internal code of the Evita ecosystems.
	 *
	 * Do not use this method from in the client code!
	 */
	public static CatalogSchema _internalBuild(
		int version,
		@Nonnull String name,
		@Nonnull Map<NamingConvention, String> nameVariants,
		@Nullable String description,
		@Nonnull Map<String, GlobalAttributeSchemaContract> attributes,
		@Nonnull Function<String, EntitySchemaContract> entitySchemaAccessor
	) {
		return new CatalogSchema(
			version, name, nameVariants, description,
			attributes,
			entitySchemaAccessor
		);
	}

	/**
	 * This method is for internal purposes only. It could be used for reconstruction of CatalogSchema from
	 * different package than current, but still internal code of the Evita ecosystems.
	 *
	 * Do not use this method from in the client code!
	 */
	@Nonnull
	public static CatalogSchema _internalBuild(
		@Nonnull CatalogSchemaContract baseSchema
	) {
		return baseSchema instanceof CatalogSchema catalogSchema ?
			catalogSchema :
			new CatalogSchema(
				baseSchema.getVersion(),
				baseSchema.getName(),
				baseSchema.getNameVariants(),
				baseSchema.getDescription(),
				baseSchema.getAttributes(),
				entityType -> baseSchema.getEntitySchema(entityType).orElse(null)
			);
	}

	/**
	 * This method is for internal purposes only. It could be used for reconstruction of CatalogSchema from
	 * different package than current, but still internal code of the Evita ecosystems.
	 *
	 * Do not use this method from in the client code!
	 */
	@Nonnull
	public static CatalogSchema _internalBuildWithUpdatedVersion(
		@Nonnull CatalogSchemaContract baseSchema
	) {
		return new CatalogSchema(
				baseSchema.getVersion() + 1,
				baseSchema.getName(),
				baseSchema.getNameVariants(),
				baseSchema.getDescription(),
				baseSchema.getAttributes(),
				entityType -> baseSchema.getEntitySchema(entityType).orElse(null)
			);
	}

	/**
	 * Method converts the "unknown" contract implementation and converts it to the "known" {@link GlobalAttributeSchema}
	 * so that the catalog schema can access the internal API of it.
	 */
	@Nonnull
	static GlobalAttributeSchema toAttributeSchema(@Nonnull GlobalAttributeSchemaContract attributeSchemaContract) {
		//noinspection unchecked,rawtypes
		return attributeSchemaContract instanceof GlobalAttributeSchema attributeSchema ?
			attributeSchema :
			GlobalAttributeSchema._internalBuild(
				attributeSchemaContract.getName(),
				attributeSchemaContract.getNameVariants(),
				attributeSchemaContract.getDescription(),
				attributeSchemaContract.getDeprecationNotice(),
				attributeSchemaContract.isUnique(),
				attributeSchemaContract.isUniqueGlobally(),
				attributeSchemaContract.isFilterable(),
				attributeSchemaContract.isSortable(),
				attributeSchemaContract.isLocalized(),
				attributeSchemaContract.isNullable(),
				(Class) attributeSchemaContract.getType(),
				attributeSchemaContract.getDefaultValue(),
				attributeSchemaContract.getIndexedDecimalPlaces()
			);
	}

	private CatalogSchema(
		int version,
		@Nonnull String name,
		@Nonnull Map<NamingConvention, String> nameVariants,
		@Nullable String description,
		@Nonnull Map<String, GlobalAttributeSchemaContract> attributes,
		@Nonnull Function<String, EntitySchemaContract> entitySchemaAccessor
	) {
		this.version = version;
		this.name = name;
		this.nameVariants = nameVariants;
		this.description = description;
		this.attributes = attributes.entrySet()
			.stream()
			.collect(
				Collectors.toMap(
					Entry::getKey,
					it -> toAttributeSchema(it.getValue())
				)
			);
		;
		this.attributeNameIndex = _internalGenerateNameVariantIndex(this.attributes.values(), GlobalAttributeSchema::getNameVariants);
		this.entitySchemaAccessor = entitySchemaAccessor;
	}

	@Nonnull
	@Override
	public Optional<EntitySchemaContract> getEntitySchema(@Nonnull String entityType) {
		return ofNullable(entitySchemaAccessor.apply(entityType));
	}

	@Override
	@Nonnull
	public String getNameVariant(@Nonnull NamingConvention namingConvention) {
		return this.nameVariants.get(namingConvention);
	}

	@Nonnull
	@Override
	public Map<String, GlobalAttributeSchemaContract> getAttributes() {
		// we need EntitySchema to provide access to provide access to internal representations - i.e. whoever has
		// reference to EntitySchema should have access to other internal schema representations as well
		// unfortunately, the Generics in Java is just stupid, and we cannot provide subtype at the place of supertype
		// collection, so we have to work around that issue using generics stripping
		//noinspection unchecked,rawtypes
		return (Map) this.attributes;
	}

	@Nonnull
	@Override
	public Optional<GlobalAttributeSchemaContract> getAttribute(@Nonnull String attributeName) {
		return ofNullable(this.attributes.get(attributeName));
	}

	@Nonnull
	@Override
	public Optional<GlobalAttributeSchemaContract> getAttributeByName(@Nonnull String attributeName, @Nonnull NamingConvention namingConvention) {
		return ofNullable(attributeNameIndex.get(attributeName))
			.map(it -> it[namingConvention.ordinal()]);
	}

	@Override
	public boolean differsFrom(CatalogSchemaContract otherObject) {
		if (this == otherObject) return false;
		return !(
			version == otherObject.getVersion() &&
				name.equals(otherObject.getName()) &&
				attributes.equals(otherObject.getAttributes())
		);
	}

}
