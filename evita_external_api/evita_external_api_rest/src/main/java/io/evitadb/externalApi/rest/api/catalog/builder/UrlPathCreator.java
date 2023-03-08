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

package io.evitadb.externalApi.rest.api.catalog.builder;

import io.evitadb.api.CatalogContract;
import io.evitadb.externalApi.api.catalog.dataApi.model.CatalogDataApiRootDescriptor;
import io.evitadb.externalApi.api.model.EndpointDescriptor;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;

import javax.annotation.Nonnull;

import static io.evitadb.externalApi.api.ExternalApiNamingConventions.URL_NAME_NAMING_CONVENTION;

/**
 * Creates URL paths for catalog or entity
 *
 * @author Martin Veska (veska@fg.cz), FG Forrest a.s. (c) 2022
 */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public class UrlPathCreator {
	public static final String REST_BASE_PATH = "rest";
	public static final String URL_PATH_SEPARATOR = "/";
	public static final String URL_PRIMARY_KEY_PATH_VARIABLE = "{primaryKey}";
	public static final String URL_LOCALE_PATH_VARIABLE = "{locale}";

	/**
	 * Creates URL path based on entity name
	 * @param entitySchemaBuildingContext
	 * @param urlWithLocale when <code>true</code> then locale will be part of URL path
	 * @return
	 */
	public static String createUrlPathToEntity(@Nonnull CollectionRestBuildingContext entitySchemaBuildingContext, boolean urlWithLocale) {
		return (urlWithLocale ? URL_PATH_SEPARATOR + URL_LOCALE_PATH_VARIABLE :"") + URL_PATH_SEPARATOR + entitySchemaBuildingContext.getSchema().getNameVariant(URL_NAME_NAMING_CONVENTION);
	}

	/**
	 * Creates URL path for entity upsert or delete operation
	 * @param entitySchemaBuildingContext
	 * @param withPrimaryKeyInPath when <code>true</code> then <strong>primary key</strong> will be part of URL
	 * @return
	 */
	public static String createUrlPathToEntityMutation(@Nonnull CollectionRestBuildingContext entitySchemaBuildingContext, boolean withPrimaryKeyInPath) {
		return URL_PATH_SEPARATOR + entitySchemaBuildingContext.getSchema().getNameVariant(URL_NAME_NAMING_CONVENTION) +
			(withPrimaryKeyInPath?UrlPathCreator.URL_PATH_SEPARATOR + UrlPathCreator.URL_PRIMARY_KEY_PATH_VARIABLE:"");
	}

	/**
	 * Creates URL path based on entity name and endpoint descriptor. Name of endpoint descriptor is used as path suffix.
	 * @param entitySchemaBuildingContext
	 * @param endpointDescriptor
	 * @param urlWithLocale when <code>true</code> than locale will be part of URL path
	 * @return
	 */
	public static String createUrlPathToEntity(@Nonnull CollectionRestBuildingContext entitySchemaBuildingContext,
	                                           @Nonnull EndpointDescriptor endpointDescriptor, boolean urlWithLocale) {
		return createUrlPathToEntity(entitySchemaBuildingContext, urlWithLocale) + URL_PATH_SEPARATOR + endpointDescriptor.operation(URL_NAME_NAMING_CONVENTION);
	}

	/**
	 * Creates URL path to unknown entity
	 * @param urlWithLocale when <code>true</code> than locale will be part of URL path
	 * @return
	 */
	public static String createUrlPathToUnknownEntity(boolean urlWithLocale) {
		return (urlWithLocale?URL_PATH_SEPARATOR + URL_LOCALE_PATH_VARIABLE :"") + URL_PATH_SEPARATOR +
			CatalogDataApiRootDescriptor.GET_UNKNOWN_ENTITY.classifier(URL_NAME_NAMING_CONVENTION) + URL_PATH_SEPARATOR +
			CatalogDataApiRootDescriptor.GET_UNKNOWN_ENTITY.operation(URL_NAME_NAMING_CONVENTION);
	}

	/**
	 * Creates URL path to unknown entity list
	 * @param urlWithLocale when <code>true</code> than locale will be part of URL path
	 * @return
	 */
	public static String createUrlPathToUnknownEntityList(boolean urlWithLocale) {
		return (urlWithLocale?URL_PATH_SEPARATOR + URL_LOCALE_PATH_VARIABLE :"") + URL_PATH_SEPARATOR +
			CatalogDataApiRootDescriptor.LIST_UNKNOWN_ENTITY.classifier(URL_NAME_NAMING_CONVENTION) + URL_PATH_SEPARATOR +
			CatalogDataApiRootDescriptor.LIST_UNKNOWN_ENTITY.operation(URL_NAME_NAMING_CONVENTION);
	}

	/**
	 * Creates URL path based on entity name and endpoint descriptor. Name of endpoint descriptor is used as path suffix.
	 */
	public static String createUrlPathToEntitySchema(@Nonnull CollectionRestBuildingContext entitySchemaBuildingContext,
	                                                 @Nonnull EndpointDescriptor endpointDescriptor) {
		return URL_PATH_SEPARATOR +
			entitySchemaBuildingContext.getSchema().getNameVariant(URL_NAME_NAMING_CONVENTION) +
			URL_PATH_SEPARATOR +
			endpointDescriptor.operation(URL_NAME_NAMING_CONVENTION);
	}

	/**
	 * Creates base URL path to Collections.
	 * @return
	 */
	public static String createBaseUrlPathToCollections() {
		return URL_PATH_SEPARATOR + CatalogDataApiRootDescriptor.COLLECTIONS.operation(URL_NAME_NAMING_CONVENTION);
	}

	/**
	 * Creates URL path based on catalog name
	 * @param catalog
	 * @return
	 */
	public static String createBaseUrlPathToCatalog(@Nonnull CatalogContract catalog) {
		return URL_PATH_SEPARATOR + REST_BASE_PATH + URL_PATH_SEPARATOR + getCatalogUrlPathName(catalog);
	}

	/**
	 * Creates identifier based on catalog name which can be used as part of URL path
	 *
	 * @param catalog
	 * @return
	 */
	public static String getCatalogUrlPathName(@Nonnull CatalogContract catalog) {
		return catalog.getSchema().getNameVariants().get(URL_NAME_NAMING_CONVENTION);
	}
}
