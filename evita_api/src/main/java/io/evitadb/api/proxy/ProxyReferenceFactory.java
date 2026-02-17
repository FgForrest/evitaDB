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

package io.evitadb.api.proxy;

import io.evitadb.api.EvitaSessionContract;
import io.evitadb.api.exception.EntityClassInvalidException;
import io.evitadb.api.requestResponse.data.EntityContract;
import io.evitadb.api.requestResponse.data.ReferenceContract;
import io.evitadb.api.requestResponse.data.SealedEntity;
import io.evitadb.api.requestResponse.schema.AttributeSchemaContract;
import io.evitadb.api.requestResponse.schema.EntitySchemaContract;

import javax.annotation.Nonnull;
import java.util.Map;

/**
 * Factory interface for creating proxy instances that wrap entity references, enabling client applications
 * to work with custom POJOs, records, or interfaces for reference objects instead of the generic
 * {@link ReferenceContract} API.
 *
 * **Purpose:**
 *
 * References in evitaDB connect entities to other entities (e.g., product → category, product → brand) and
 * can carry their own attributes (e.g., "orderInCategory" attribute on product-category reference). This factory
 * creates proxies that expose reference-specific data through a client-defined contract, enabling type-safe
 * access to:
 * - Reference attributes (custom data on the relationship itself)
 * - Referenced entity primary key and type
 * - Referenced entity body (if fetched with `entityFetch` in the query)
 * - Group entity (if the reference has a group)
 *
 * **Integration with Main Entity Proxies:**
 *
 * Reference proxies are typically accessed through methods on the main entity proxy (e.g., `product.getCategories()`).
 * The factory needs the `mainType` parameter to correctly handle nested proxy creation when the reference
 * itself references another entity that should also be proxied.
 *
 * **Usage Context:**
 *
 * This factory is used internally by {@link io.evitadb.api.proxy.impl.ProxycianFactory} when a client-defined
 * entity proxy contains methods that return reference objects (either single references or collections).
 *
 * @see ProxyFactory
 * @see SealedEntityReferenceProxy
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2023
 */
public interface ProxyReferenceFactory {

	/**
	 * Creates a proxy instance that wraps an entity reference and implements the specified contract type.
	 *
	 * The proxy automatically maps reference data to the methods and fields of `expectedType`:
	 * - Reference attributes (e.g., "orderInCategory") are mapped to getter methods or fields
	 * - Referenced entity can be accessed if fetched with `entityFetch` in the original query
	 * - Group entity can be accessed if fetched with `entityGroupFetch` in the original query
	 * - Primary keys of referenced and group entities are always available
	 *
	 * The proxy respects the fetch requirements from the original query. Attempting to access data that was not
	 * fetched will result in exceptions or null values depending on the contract definition.
	 *
	 * @param mainType the proxy contract class of the main (owner) entity; used to resolve nested proxy types
	 *                 when the reference itself contains methods that return other proxied entities
	 * @param expectedType the interface, abstract class, or POJO class that the reference proxy should
	 *                     implement/extend; must be a valid proxy contract
	 * @param entity the owner entity that contains this reference (used for context and building reference
	 *               mutations)
	 * @param referencedEntitySchemas map of entity schemas for all entity types that might be referenced;
	 *                                used to create nested proxies for referenced entity bodies
	 * @param reference the actual reference instance to wrap (contains primary keys, attributes, and optional
	 *                  entity body)
	 * @param referenceAttributeTypes map of attribute schemas for attributes defined on this reference type;
	 *                                shared across all references of the same type (e.g., all "categories"
	 *                                references)
	 * @param <T> the type of the client-defined reference contract
	 * @return a proxy instance that implements `expectedType` and delegates to the underlying reference
	 * @throws EntityClassInvalidException if the proxy contract is invalid (e.g., sealed class, incompatible
	 *         method signatures, missing constructors)
	 */
	@Nonnull
	<T> T createEntityReferenceProxy(
		@Nonnull Class<?> mainType,
		@Nonnull Class<T> expectedType,
		@Nonnull EntityContract entity,
		@Nonnull Map<String, EntitySchemaContract> referencedEntitySchemas,
		@Nonnull ReferenceContract reference,
		@Nonnull Map<String, AttributeSchemaContract> referenceAttributeTypes
	) throws EntityClassInvalidException;

}
