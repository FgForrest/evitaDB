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

package io.evitadb.driver;

import io.evitadb.api.exception.CollectionNotFoundException;
import io.evitadb.api.proxy.ProxyFactory;
import io.evitadb.api.proxy.impl.UnsatisfiedDependencyFactory;
import io.evitadb.api.requestResponse.schema.CatalogSchemaContract;
import io.evitadb.api.requestResponse.schema.EntitySchemaContract;
import io.evitadb.api.requestResponse.schema.EntitySchemaDecorator;
import io.evitadb.api.requestResponse.schema.SealedCatalogSchema;
import io.evitadb.api.requestResponse.schema.SealedEntitySchema;
import io.evitadb.api.requestResponse.schema.dto.CatalogSchema;
import io.evitadb.api.requestResponse.schema.dto.EntitySchema;
import io.evitadb.api.requestResponse.schema.dto.EntitySchemaProvider;
import io.evitadb.api.requestResponse.schema.mutation.CatalogSchemaMutation;
import io.evitadb.api.requestResponse.schema.mutation.SchemaMutation;
import io.evitadb.api.requestResponse.schema.mutation.catalog.ModifyEntitySchemaMutation;
import io.evitadb.api.requestResponse.schema.mutation.engine.ModifyCatalogSchemaMutation;
import io.evitadb.driver.requestResponse.schema.ClientCatalogSchemaDecorator;
import io.evitadb.utils.Assert;
import io.evitadb.utils.ClassUtils;
import io.evitadb.utils.CollectionUtils;
import io.evitadb.utils.ReflectionLookup;
import lombok.Getter;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.Collection;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.Supplier;

import static java.util.Optional.of;

/**
 * This class is a registry for previously fetched entity schemas to avoid excessive schema fetching from the client.
 * The entity schemas are used on the client side a lot, and it would be extremely slow to fetch them each time they
 * are necessary for query processing.
 *
 * So we cache the previously fetched schemas along with their version. The query results return the information about
 * the entity type and schema version but leave the entity schema out to keep the amount of transferred data low.
 * This cache allows to check whether we already have the entity schema for particular entity and its version present
 * in the client and if so - we just reuse it. If not, the entity schema is freshly checked.
 *
 * When the schema changes the version goes up and a new version is automatically pulled in. When logic on the client
 * stops using the previous schema version (there might be still some threads working with entities fetched with
 * the older schema version) the schema will remain idle consuming the precious memory space. Therefore, we check
 * in regular intervals whether there are unused entity schemas in the cache and if any is found, it is automatically
 * purged.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2022
 */
class EvitaEntitySchemaCache {
	/**
	 * The name of the catalog the cache is attached to.
	 */
	@Getter @Nonnull
	private final String catalogName;
	/**
	 * Contains the last known catalog version on the server side.
	 */
	private final AtomicLong lastKnownCatalogVersion = new AtomicLong(0L);
	/**
	 * Contains the last known catalog schema version on the server side.
	 */
	private final AtomicInteger lastKnownCatalogSchemaVersion = new AtomicInteger(0);
	/**
	 * Contains the references to entity schemas indexed by their {@link EntitySchema#getName()}.
	 */
	private final Map<SchemaCacheKey, SchemaWrapper> cachedSchemas = new ConcurrentHashMap<>(64);
	/**
	 * Contains the latest entity schema index.
	 */
	private final AtomicReference<SchemaIndexWrapper> schemaIndex = new AtomicReference<>();
	/**
	 * Contains the timestamp of the last check for entity schemas in {@link #cachedSchemas} being obsolete.
	 */
	private final AtomicLong lastObsoleteCheck = new AtomicLong();
	/**
	 * Contains reference to the proxy factory that is used to create proxies for the entities.
	 */
	@Getter private final ProxyFactory proxyFactory;

	public EvitaEntitySchemaCache(@Nonnull String catalogName, @Nonnull ReflectionLookup reflectionLookup) {
		this.catalogName = catalogName;
		this.proxyFactory = ClassUtils.whenPresentOnClasspath(
			"one.edee.oss.proxycian.bytebuddy.ByteBuddyProxyGenerator",
			() -> (ProxyFactory) Class.forName("io.evitadb.api.proxy.impl.ProxycianFactory")
				.getConstructor(ReflectionLookup.class)
				.newInstance(reflectionLookup)
		).orElse(UnsatisfiedDependencyFactory.INSTANCE);
	}

	/**
	 * Updates the last known catalog version with the specified version.
	 *
	 * @param version the version to set as the last known catalog version
	 */
	public void updateLastKnownCatalogVersion(long version) {
		this.lastKnownCatalogVersion.set(version);
	}

	/**
	 * Updates the last known catalog version with the specified version.
	 *
	 * @param version the version to set as the last known catalog version
	 */
	public void updateLastKnownCatalogVersion(long version, int catalogSchemaVersion) {
		this.lastKnownCatalogVersion.set(version);
		this.lastKnownCatalogSchemaVersion.set(catalogSchemaVersion);
	}

	/**
	 * Returns the last known catalog version.
	 *
	 * @return the last known catalog version
	 */
	public long getLastKnownCatalogVersion() {
		return this.lastKnownCatalogVersion.get();
	}

	/**
	 * Method analyzes all mutations and resets locally cached values for schemas, that get modified by any of passed
	 * mutations. Applying schema mutation ultimately leads to schema change and thus the locally cached schema
	 * needs to be purged and re-fetched from the server again.
	 */
	public void analyzeMutations(@Nonnull SchemaMutation... schemaMutation) {
		for (SchemaMutation mutation : schemaMutation) {
			if (mutation instanceof ModifyEntitySchemaMutation entitySchemaMutation) {
				removeLatestEntitySchema(entitySchemaMutation.getName());
			} else if (mutation instanceof ModifyCatalogSchemaMutation entityRelatedMutation) {
				analyzeMutations(entityRelatedMutation.getSchemaMutations());
			} else if (mutation instanceof CatalogSchemaMutation) {
				removeLatestCatalogSchema();
			}
		}
	}

	/**
	 * Returns the latest known catalog schema on the client side. If it's missing, the schema is retrieved using
	 * `schemaAccessor` lambda and cached.
	 */
	@Nonnull
	public SealedCatalogSchema getLatestCatalogSchema(
		@Nonnull Supplier<CatalogSchema> schemaAccessor,
		@Nonnull EntitySchemaProvider entitySchemaAccessor
	) {
		final long now = System.currentTimeMillis();
		// frequently apply obsolete check
		executeObsoleteCheck(now);
		// attempt to retrieve schema from the client side cache
		final SchemaWrapper schemaWrapper = this.cachedSchemas.get(LatestCatalogSchema.INSTANCE);
		if (schemaWrapper == null || schemaWrapper.getCatalogSchema().version() < this.lastKnownCatalogSchemaVersion.get()) {
			// if not found or versions don't match - re-fetch the contents
			final CatalogSchema schemaRelevantToSession = schemaAccessor.get();
			final SchemaWrapper newCachedValue = new SchemaWrapper(schemaRelevantToSession, now);
			setLatestCatalogSchemaInternal(newCachedValue);
			return new ClientCatalogSchemaDecorator(schemaRelevantToSession, entitySchemaAccessor);
		} else {
			// if found in cache, update last used timestamp
			schemaWrapper.used();
			return new ClientCatalogSchemaDecorator(schemaWrapper.getCatalogSchema(), entitySchemaAccessor);
		}
	}

	/**
	 * Method allows to set passed {@link CatalogSchemaContract} as the latest known catalog schema form. Method should
	 * be used when the catalog schema is freshly fetched from the server side.
	 */
	public void setLatestCatalogSchema(@Nonnull CatalogSchema catalogSchema) {
		setLatestCatalogSchemaInternal(
			new SchemaWrapper(catalogSchema, System.currentTimeMillis())
		);
	}

	/**
	 * Method resets tha last known {@link CatalogSchemaContract} to NULL. This will force to fetch actual schema from
	 * the server side next time, it's asked for it.
	 */
	public void removeLatestCatalogSchema() {
		this.schemaIndex.set(null);
		this.cachedSchemas.remove(LatestCatalogSchema.INSTANCE);
	}

	/**
	 * Retrieves schema for passed `entityType`. The schema version is compared with the passed required `version`
	 * and if the version doesn't match new schema is retrieved using `schemaAccessor` lambda` and cached.
	 */
	@Nonnull
	public Optional<EntitySchema> getEntitySchema(
		@Nonnull String entityType,
		int version,
		@Nonnull Function<String, Optional<EntitySchema>> schemaAccessor
	) {
		return fetchEntitySchema(
			new EntitySchemaWithVersion(entityType, version),
			schemaWrapper -> schemaWrapper == null || schemaWrapper.getEntitySchema().version() != version,
			schemaAccessor
		);
	}

	/**
	 * Retrieves schema for passed `entityType`. The schema version is compared with the passed required `version`
	 * and if the version doesn't match new schema is retrieved using `schemaAccessor` lambda` and cached.
	 */
	@Nonnull
	public SealedEntitySchema getEntitySchemaOrThrowException(
		@Nonnull String entityType,
		int version,
		@Nonnull Function<String, Optional<EntitySchema>> schemaAccessor,
		@Nonnull Supplier<CatalogSchemaContract> catalogSchemaSupplier
	) {
		return getEntitySchema(entityType, version, schemaAccessor)
			.map(it -> {
				// if currently observed latest entity schema is older than the fetched one, remove it
				getLatestEntitySchema(entityType, schemaAccessor, catalogSchemaSupplier)
					.ifPresent(latestSchema -> {
						if (latestSchema.version() < version) {
							removeLatestEntitySchema(entityType);
						}
					});
				return new EntitySchemaDecorator(catalogSchemaSupplier, it);
			})
			.orElseThrow(() -> new CollectionNotFoundException(entityType));
	}

	/**
	 * Returns the latest known schema for passed `entityType` on the client side. If it's missing, the schema is
	 * retrieved using `schemaAccessor` lambda` and cached.
	 */
	@Nonnull
	public Optional<SealedEntitySchema> getLatestEntitySchema(
		@Nonnull String entityType,
		@Nonnull Function<String, Optional<EntitySchema>> schemaAccessor,
		@Nonnull Supplier<CatalogSchemaContract> catalogSchemaSupplier
	) {
		return fetchEntitySchema(
			new LatestEntitySchema(entityType),
			Objects::isNull,
			schemaAccessor
		)
			.map(it -> new EntitySchemaDecorator(catalogSchemaSupplier, it));
	}

	/**
	 * Method allows to set passed {@link EntitySchemaContract} as the latest known entity schema form. Method should
	 * be used when the entity schema is freshly fetched from the server side.
	 */
	public void setLatestEntitySchema(@Nonnull EntitySchema entitySchema) {
		setLatestEntitySchemaInternal(
			new LatestEntitySchema(entitySchema.getName()),
			new SchemaWrapper(entitySchema, System.currentTimeMillis())
		);
	}

	/**
	 * Method resets tha last known {@link EntitySchemaContract} to NULL. This will force to fetch actual schema from
	 * the server side next time, it's asked for it.
	 */
	public void removeLatestEntitySchema(@Nonnull String entityType) {
		this.schemaIndex.set(null);
		this.cachedSchemas.remove(new LatestEntitySchema(entityType));
	}

	@Nonnull
	public Map<String, EntitySchemaContract> getLatestEntitySchemaIndex(
		@Nonnull Supplier<Collection<String>> entitySchemaNamesAccessor,
		@Nonnull Function<String, Optional<EntitySchema>> schemaAccessor,
		@Nonnull Supplier<CatalogSchemaContract> catalogSchemaSupplier
	) {
		final long now = System.currentTimeMillis();
		// frequently apply obsolete check
		executeObsoleteCheck(now);
		SchemaIndexWrapper schemaIndexWrapper = this.schemaIndex.get();
		if (schemaIndexWrapper == null || schemaIndexWrapper.isObsolete(now)) {
			final Collection<String> entitySchemaNames = entitySchemaNamesAccessor.get();
			final Map<String, EntitySchemaContract> newIndex = CollectionUtils.createHashMap(entitySchemaNames.size());
			for (String entitySchemaName : entitySchemaNames) {
				getLatestEntitySchema(
					entitySchemaName, schemaAccessor, catalogSchemaSupplier
				).ifPresent(it -> newIndex.put(it.getName(), it));
			}
			schemaIndexWrapper = new SchemaIndexWrapper(newIndex, now);
			this.schemaIndex.set(schemaIndexWrapper);
		}

		return schemaIndexWrapper.getIndex();
	}

	@Nonnull
	private Optional<EntitySchema> fetchEntitySchema(
		@Nonnull EntitySchemaCacheKey cacheKey,
		@Nonnull Predicate<SchemaWrapper> shouldReFetch,
		@Nonnull Function<String, Optional<EntitySchema>> schemaAccessor
	) {
		final long now = System.currentTimeMillis();
		// frequently apply obsolete check
		executeObsoleteCheck(now);
		// attempt to retrieve schema from the client side cache
		final SchemaWrapper schemaWrapper = this.cachedSchemas.get(cacheKey);
		if (shouldReFetch.test(schemaWrapper)) {
			// if not found or versions don't match - re-fetch the contents
			final Optional<EntitySchema> schemaRelevantToSession = schemaAccessor.apply(cacheKey.entityType());
			schemaRelevantToSession.ifPresent(it -> {
				final SchemaWrapper newCachedValue = new SchemaWrapper(it, now);
				this.cachedSchemas.put(
					new EntitySchemaWithVersion(cacheKey.entityType(), it.version()),
					newCachedValue
				);
				setLatestEntitySchemaInternal(cacheKey, newCachedValue);
			});
			return schemaRelevantToSession;
		} else {
			// if found in cache, update last used timestamp
			schemaWrapper.used();
			return of(schemaWrapper.getEntitySchema());
		}
	}

	/**
	 * Entity schemas that hasn't been used for a long time are considered obsolete and are removed from
	 * the cache.
	 *
	 * @param now current date and time
	 */
	private void executeObsoleteCheck(long now) {
		final long lastCheck = this.lastObsoleteCheck.get();
		if (lastCheck + 60 * 1000 < now) {
			if (this.lastObsoleteCheck.compareAndSet(lastCheck, now)) {
				this.cachedSchemas.values().removeIf(entitySchemaWrapper -> entitySchemaWrapper.isObsolete(now));
			}
		}
	}

	/**
	 * Updates the cache with the latest catalog schema provided in the {@code newCachedValue}.
	 * If the schema does not exist for the {@link LatestCatalogSchema#INSTANCE} key, it is added to the cache.
	 * If a schema already exists, it is replaced only if the version in {@code newCachedValue} is newer than the
	 * version of the existing one.
	 *
	 * @param newCachedValue the wrapper containing the new catalog schema and associated details to be added to the cache
	 */
	private void setLatestCatalogSchemaInternal(@Nonnull SchemaWrapper newCachedValue) {
		final SchemaWrapper previousValue = this.cachedSchemas.putIfAbsent(LatestCatalogSchema.INSTANCE, newCachedValue);
		if (previousValue != null && previousValue.getCatalogSchema().version() < newCachedValue.getCatalogSchema().version()) {
			// if the previous value was older, replace it with the new one
			this.cachedSchemas.put(LatestCatalogSchema.INSTANCE, newCachedValue);
		}
	}

	/**
	 * Updates the cache with the latest entity schema provided in the {@code newCachedValue}.
	 * If the schema does not exist for the given {@code cacheKey}, it is added to the cache.
	 * If a schema already exists, it is replaced only if the version in {@code newCachedValue}
	 * is newer than the existing one.
	 *
	 * @param cacheKey the unique key representing the entity schema to be updated
	 * @param newCachedValue the wrapper containing the new entity schema and associated details to be added to the cache
	 */
	private void setLatestEntitySchemaInternal(
		@Nonnull EntitySchemaCacheKey cacheKey,
		@Nonnull SchemaWrapper newCachedValue
	) {
		// initialize the latest known entity schema if missing
		final LatestEntitySchema latestEntitySchema = new LatestEntitySchema(cacheKey.entityType());
		final SchemaWrapper previousValue = this.cachedSchemas.putIfAbsent(latestEntitySchema, newCachedValue);
		// if not missing verify the stored value is really the latest one and if not rewrite it
		if (previousValue != null && previousValue.getEntitySchema().version() < newCachedValue.getEntitySchema().version()) {
			this.cachedSchemas.put(latestEntitySchema, newCachedValue);
		}
	}

	/**
	 * Interface shared among all schema caching keys.
	 */
	private interface SchemaCacheKey {

	}

	/**
	 * Interface providing access to the `entityType` of the cached schema.
	 */
	private interface EntitySchemaCacheKey extends SchemaCacheKey {

		@Nonnull
		String entityType();

	}

	/**
	 * Combines {@link EntitySchema#getName()} with {@link EntitySchema#version()} in single tuple.
	 */
	record EntitySchemaWithVersion(
		@Nonnull String entityType,
		int version
	) implements EntitySchemaCacheKey { }

	/**
	 * Simple entity schema cache key for storing latest (current) entity schema of particular entity type.
	 */
	record LatestEntitySchema(
		@Nonnull String entityType
	) implements EntitySchemaCacheKey {	}

	/**
	 * Simple entity schema cache key for storing latest (current) catalog schema.
	 */
	record LatestCatalogSchema() implements SchemaCacheKey {
		public static final LatestCatalogSchema INSTANCE = new LatestCatalogSchema();
	}

	/**
	 * Combines {@link EntitySchema#getName()} with {@link EntitySchema#version()} in single tuple.
	 */
	static class SchemaWrapper {
		/**
		 * The entity schema is considered obsolete after 4 hours since last usage.
		 */
		private static final long OBSOLETE_INTERVAL = 4L * 60L * 60L * 1000L;
		/**
		 * The entity schema fetched from the server.
		 */
		@Nullable
		private final CatalogSchema catalogSchema;
		/**
		 * The entity schema fetched from the server.
		 */
		@Nullable
		private final EntitySchema entitySchema;
		/**
		 * Date and time ({@link System#currentTimeMillis()} of the moment when the entity schema was fetched from
		 * the server side.
		 */
		@Getter private final long fetched;
		/**
		 * Date and time ({@link System#currentTimeMillis()} of the moment when the entity schema was used for
		 * the last tim.
		 */
		private long lastUsed;

		SchemaWrapper(@Nonnull CatalogSchema catalogSchema, long fetched) {
			this.catalogSchema = catalogSchema;
			this.entitySchema = null;
			this.fetched = fetched;
			this.lastUsed = fetched;
		}

		SchemaWrapper(@Nonnull EntitySchema entitySchema, long fetched) {
			this.catalogSchema = null;
			this.entitySchema = entitySchema;
			this.fetched = fetched;
			this.lastUsed = fetched;
		}

		@Nonnull
		public CatalogSchema getCatalogSchema() {
			Assert.isPremiseValid(this.catalogSchema != null, "Catalog schema is not present in the wrapper.");
			return this.catalogSchema;
		}

		@Nonnull
		public EntitySchema getEntitySchema() {
			Assert.isPremiseValid(this.entitySchema != null, "Entity schema is not present in the wrapper.");
			return this.entitySchema;
		}

		/**
		 * Tracks the moment when the entity schema was used for the last time.
		 */
		void used() {
			this.lastUsed = System.currentTimeMillis();
		}

		/**
		 * Returns TRUE if the entity schema was used long ago (defined by the {@link #OBSOLETE_INTERVAL}.
		 */
		boolean isObsolete(long now) {
			return now - OBSOLETE_INTERVAL > this.lastUsed;
		}

	}

	/**
	 * Contains information about all latest entity schemas together with the fetch timestamp.
	 */
	static class SchemaIndexWrapper {
		/**
		 * The entity schema index is considered obsolete after 4 hours since last usage.
		 */
		private static final long OBSOLETE_INTERVAL = 4L * 60L * 60L * 100L;
		/**
		 * The entity schema index fetched from the server.
		 */
		@Nonnull
		@Getter private final Map<String, EntitySchemaContract> index;
		/**
		 * Date and time ({@link System#currentTimeMillis()} of the moment when the entity schema was fetched from
		 * the server side.
		 */
		@Getter private final long fetched;

		SchemaIndexWrapper(@Nonnull Map<String, EntitySchemaContract> index, long fetched) {
			this.index = index;
			this.fetched = fetched;
		}

		/**
		 * Returns TRUE if the entity schema was used long ago (defined by the {@link #OBSOLETE_INTERVAL}.
		 */
		boolean isObsolete(long now) {
			return now - OBSOLETE_INTERVAL > this.fetched;
		}

	}

}
