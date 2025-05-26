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

package io.evitadb.store.entity.model.schema;

import io.evitadb.api.CatalogContract;
import io.evitadb.api.requestResponse.schema.CatalogSchemaContract;
import io.evitadb.api.requestResponse.schema.dto.CatalogSchema;
import io.evitadb.api.requestResponse.schema.dto.EntitySchema;
import io.evitadb.exception.GenericEvitaInternalError;
import io.evitadb.store.model.StoragePart;
import io.evitadb.store.service.KeyCompressor;
import io.evitadb.utils.NamingConvention;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.Serial;
import java.util.Map;
import java.util.function.Supplier;

import static java.util.Optional.ofNullable;

/**
 * Storage part envelops {@link CatalogSchemaContract}. Storage part has always id fixed to 1 because there is no other
 * schema in the entity collection than this one.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
public record CatalogSchemaStoragePart(CatalogSchema catalogSchema) implements StoragePart {
	@Serial private static final long serialVersionUID = 3691044557054386677L;
	private static final ThreadLocal<CatalogContract> CATALOG_ACCESSOR = new ThreadLocal<>();
	private static final ThreadLocal<NameWithVariants> CATALOG_NAME_TO_REPLACE_ACCESSOR = new ThreadLocal<>();

	/**
	 * Method returns reference to the {@link CatalogContract} instance initialized by
	 * {@link #deserializeWithCatalog(CatalogContract, Supplier)} method.
	 */
	@Nonnull
	public static CatalogContract getDeserializationContextCatalog() {
		return ofNullable(CATALOG_ACCESSOR.get())
			.orElseThrow(() -> new GenericEvitaInternalError("Catalog should be already set via CatalogSchemaStoragePart.deserializeWithCatalog"));
	}

	/**
	 * Method initializes link to {@link CatalogContract} for purposes of {@link CatalogSchema} deserialization that needs
	 * to provide function that allows accessing all {@link EntitySchema} present in the {@link CatalogContract}.
	 */
	public static <R> R deserializeWithCatalog(@Nonnull CatalogContract catalog, @Nonnull Supplier<R> lambda) {
		try {
			CATALOG_ACCESSOR.set(catalog);
			return lambda.get();
		} finally {
			CATALOG_ACCESSOR.remove();
		}
	}

	/**
	 * Method returns overridden catalog name initialized by {@link #serializeWithCatalogName(String, Map, Supplier)}
	 * method in case the catalog is being replaces = renamed.
	 */
	@Nullable
	public static String getSerializationCatalogName() {
		return ofNullable(CATALOG_NAME_TO_REPLACE_ACCESSOR.get())
			.map(NameWithVariants::name)
			.orElse(null);
	}

	/**
	 * Method returns overridden catalog name variants initialized by
	 * {@link #serializeWithCatalogName(String, Map, Supplier)} method in case the catalog is being replaces = renamed.
	 */
	@Nullable
	public static Map<NamingConvention, String> getSerializationCatalogNameVariants() {
		return ofNullable(CATALOG_NAME_TO_REPLACE_ACCESSOR.get())
			.map(NameWithVariants::nameVariants)
			.orElse(null);
	}

	/**
	 * Method sets an overridden catalog / schema name for purposes of {@link CatalogSchema} serialization. By this
	 * mechanism we override immutable catalog name property in {@link CatalogSchema} in a scenario when catalog is
	 * being replaced.
	 */
	public static <R> R serializeWithCatalogName(@Nonnull String catalogName, @Nonnull Map<NamingConvention, String> catalogNameVariants, @Nonnull Supplier<R> lambda) {
		try {
			CATALOG_NAME_TO_REPLACE_ACCESSOR.set(
				new NameWithVariants(catalogName, catalogNameVariants)
			);
			return lambda.get();
		} finally {
			CATALOG_NAME_TO_REPLACE_ACCESSOR.remove();
		}
	}

	@Nonnull
	@Override
	public Long getStoragePartPK() {
		return 1L;
	}

	@Override
	public long computeUniquePartIdAndSet(@Nonnull KeyCompressor keyCompressor) {
		return 1L;
	}

	@Nonnull
	@Override
	public String toString() {
		return "CatalogSchemaStoragePart{" +
			"schema=" + this.catalogSchema.getName() +
			'}';
	}

	private record NameWithVariants(@Nonnull String name, @Nonnull Map<NamingConvention, String> nameVariants) {}

}
