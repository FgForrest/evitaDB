/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2023-2024
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
import io.evitadb.api.proxy.impl.UnsatisfiedDependencyFactory;
import io.evitadb.api.requestResponse.data.EntityContract;
import io.evitadb.api.requestResponse.data.SealedEntity;
import io.evitadb.api.requestResponse.schema.EntitySchemaContract;
import io.evitadb.utils.ClassUtils;
import io.evitadb.utils.ReflectionLookup;

import javax.annotation.Nonnull;
import java.util.Map;

/**
 * Factory interface for creating proxy instances that wrap sealed entities, enabling client applications
 * to work with custom POJOs, records, or interfaces instead of the generic {@link SealedEntity} API.
 *
 * **Design Philosophy:**
 *
 * The proxy factory uses runtime bytecode generation (via Proxycian/ByteBuddy library) to dynamically create
 * proxy classes that implement or extend client-defined types. These proxies delegate method calls to the
 * underlying sealed entity, automatically mapping:
 * - Entity attributes to POJO fields or interface getters
 * - Associated data to corresponding methods
 * - References to nested proxy instances or collections
 * - Prices to price-related methods
 *
 * **Pluggable Implementation:**
 *
 * The factory has a fallback mechanism: if Proxycian library is not present on classpath, the factory returns
 * {@link UnsatisfiedDependencyFactory} which throws an exception when attempting to create proxies. This allows
 * EvitaDB to function without proxy support if the optional dependency is not included.
 *
 * **Usage Context:**
 *
 * This factory is used internally by {@link EvitaSessionContract} when client code invokes query methods with
 * a specific return type parameter (e.g., `session.queryEntity(MyProduct.class, query)`). The factory is also
 * used for nested entity proxies (e.g., referenced entities, parent entities).
 *
 * **Thread-Safety:**
 *
 * Proxy recipes (class definitions) are cached globally and thread-safe. Individual proxy instances are not
 * thread-safe unless the underlying entity is immutable (sealed).
 *
 * @see ProxyReferenceFactory
 * @see SealedEntityProxy
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2023
 */
public interface ProxyFactory {

	/**
	 * Creates a proxy factory instance using Proxycian (ByteBuddy) library if available on classpath.
	 *
	 * This method checks for the presence of `one.edee.oss.proxycian.bytebuddy.ByteBuddyProxyGenerator` class
	 * and instantiates {@link io.evitadb.api.proxy.impl.ProxycianFactory} if found. If the library is not
	 * present, returns {@link UnsatisfiedDependencyFactory} which throws an exception on any proxy creation
	 * attempt.
	 *
	 * @param reflectionLookup reflection lookup instance for introspecting client classes and analyzing
	 *                         their structure (fields, methods, constructors)
	 * @return fully functional proxy factory if Proxycian is on classpath, or no-op factory that throws
	 *         exceptions otherwise
	 */
	@Nonnull
	static ProxyFactory createInstance(@Nonnull ReflectionLookup reflectionLookup) {
		return ClassUtils.whenPresentOnClasspath(
			"one.edee.oss.proxycian.bytebuddy.ByteBuddyProxyGenerator",
			() -> (ProxyFactory) Class.forName("io.evitadb.api.proxy.impl.ProxycianFactory")
				.getConstructor(ReflectionLookup.class)
				.newInstance(reflectionLookup)
		).orElse(UnsatisfiedDependencyFactory.INSTANCE);
	}

	/**
	 * Creates a proxy instance that wraps a sealed entity and implements the specified contract type.
	 *
	 * The proxy automatically maps entity data (attributes, associated data, references, prices) to the methods
	 * and fields of `expectedType`. The mapping follows these rules:
	 * - Method names are analyzed to determine the target entity property (e.g., `getName()` → `name` attribute)
	 * - Field names in POJOs are mapped directly to entity properties
	 * - Referenced entities can be returned as nested proxies if requested by method return type
	 * - Collections are automatically mapped to arrays, Lists, or Sets as appropriate
	 *
	 * The proxy respects the fetch requirements from the original query - if an attribute/reference was not
	 * fetched, accessing it through the proxy will throw an exception or return null depending on the contract.
	 *
	 * @param expectedType the interface, abstract class, or POJO class that the proxy should implement/extend;
	 *                     must be a valid proxy contract (see {@link EntityClassInvalidException} for constraints)
	 * @param entity the sealed entity to wrap (typically retrieved from a query result)
	 * @param referencedEntitySchemas map of entity schemas for all entity types that might be referenced by
	 *                                this entity; used to create nested proxies for referenced entities
	 * @param <T> the type of the client-defined contract
	 * @return a proxy instance that implements `expectedType` and delegates to the underlying sealed entity
	 * @throws EntityClassInvalidException if the proxy contract is invalid (e.g., concrete class without
	 *         accessible constructor, sealed class, incompatible method signatures)
	 */
	@Nonnull
	<T> T createEntityProxy(
		@Nonnull Class<T> expectedType,
		@Nonnull EntityContract entity,
		@Nonnull Map<String, EntitySchemaContract> referencedEntitySchemas
	) throws EntityClassInvalidException;

}
