/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2026
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


package io.evitadb.store.catalog;

import io.evitadb.api.index.EntityIndexType;
import io.evitadb.api.requestResponse.data.structure.RepresentativeReferenceKey;
import io.evitadb.api.requestResponse.schema.EntitySchemaContract;
import io.evitadb.api.requestResponse.schema.dto.EntitySchema;
import io.evitadb.core.collection.EntityCollection;
import io.evitadb.dataType.DateTimeRange;
import io.evitadb.exception.ObsoleteStorageProtocolException;
import io.evitadb.index.EntityIndexKey;
import io.evitadb.index.bitmap.Bitmap;
import io.evitadb.spi.store.catalog.header.model.CatalogHeader;
import io.evitadb.spi.store.catalog.persistence.PersistenceService;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.EntityIdsStoragePart;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.EntityIndexStoragePart;
import io.evitadb.spi.store.catalog.persistence.storageParts.schema.EntitySchemaStoragePart;
import io.evitadb.store.model.header.CollectionFileReference;
import io.evitadb.store.model.header.EntityCollectionFileHeader;
import io.evitadb.store.model.reference.LogFileRecordReference;
import io.evitadb.utils.IOUtils;
import io.evitadb.utils.IOUtils.ExceptionThrowingRunnable;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;

/**
 * Detects current-equal representative partitions from published manifests before any migration publishes them.
 * Numeric manifest ids preserve partitions that an equality-keyed index map would overwrite on load. Only manifests,
 * schemas and membership parts are read; separate reference rows cannot be reconstructed from these parts.
 */
@Deprecated(since = "2026.3", forRemoval = true)
final class LegacyRepresentativeKeyPreflight {
	/** Prevents instantiation of this migration-local reader. */
	private LegacyRepresentativeKeyPreflight() {
	}

	/**
	 * Refuses a catalog of storage protocol 4 or 5 before the chain of older migrations can publish anything, when two
	 * of its stored representative partitions have keys that are equal in this version. The catalog load calls it only
	 * for those protocols; a catalog of protocol 6 gets the same check inside {@link Migration_2026_3}. It only reads -
	 * manifests, schemas and membership parts - and every collection it opens is closed again, on refusal too.
	 *
	 * @param header       the published catalog header
	 * @param catalogParts the storage of the catalog file
	 * @param factory      opens the storage of a collection
	 * @throws ObsoleteStorageProtocolException when partitions collide, naming every colliding pair
	 */
	static void verifyBeforeMigrationChain(
		@Nonnull CatalogHeader<LogFileRecordReference, CollectionFileReference> header,
		@Nonnull CatalogOffsetIndexStoragePartPersistenceService catalogParts,
		@Nonnull Function<EntityCollectionFileHeader, DefaultEntityCollectionPersistenceService> factory
	) {
		final List<DefaultEntityCollectionPersistenceService> opened = new ArrayList<>();
		try {
			final Map<String, EntitySchema> schemas = new HashMap<>();
			for (final CollectionFileReference reference : header.getEntityTypeFileIndexes()) {
				final EntityCollectionFileHeader collectionHeader = EntityCollectionHeaderReconciler.reconcile(
					header.catalogName(), reference, Objects.requireNonNull(catalogParts.getStoragePart(
						header.version(), reference.entityTypePrimaryKey(), EntityCollectionFileHeader.class
					))
				);
				final DefaultEntityCollectionPersistenceService service =
					Objects.requireNonNull(factory.apply(collectionHeader));
				opened.add(service);
				schemas.put(collectionHeader.entityType(), Objects.requireNonNull(
					service.getStoragePartPersistenceService().getStoragePart(
						header.version(), 1, EntitySchemaStoragePart.class
					)
				).entitySchema());
			}
			final Function<String, Optional<? extends EntitySchemaContract>> lookup =
				name -> Optional.ofNullable(schemas.get(name));
			final List<Collision> collisions = new ArrayList<>();
			for (final DefaultEntityCollectionPersistenceService service : opened) {
				final EntityCollectionFileHeader collectionHeader = service.getEntityCollectionHeader();
				final Set<Integer> indexIds = new HashSet<>(collectionHeader.usedEntityIndexPrimaryKeys());
				if (collectionHeader.globalEntityIndexPrimaryKey() != null) {
					indexIds.add(collectionHeader.globalEntityIndexPrimaryKey());
				}
				collisions.addAll(findCollisions(
					header.version(), collectionHeader.entityType(),
					EntityCollection.bindReflectedReferences(schemas.get(collectionHeader.entityType()), lookup),
					indexIds, service.getStoragePartPersistenceService()
				));
			}
			refuseCollisions(header.catalogName(), header.storageProtocolVersion(), collisions);
		} finally {
			IOUtils.closeQuietly(opened.stream()
				.map(service -> (ExceptionThrowingRunnable) service::close)
				.toArray(ExceptionThrowingRunnable[]::new));
		}
	}

	/**
	 * Groups only live manifest ids by their complete current identity, retaining each original tuple positionally.
	 * Collection identity is supplied by the caller; scope, family, reference and target are part of the index key.
	 *
	 * @param version    the catalog version to read
	 * @param collection the entity type of the collection, named in every collision
	 * @param schema     the collection's schema with its reflected references bound, which names the representative
	 *                   attributes
	 * @param indexIds   the entity indexes the collection header lists
	 * @param parts      the storage of the collection
	 * @return every colliding pair with the owners of both partitions, empty when none collide
	 */
	@Nonnull
	static List<Collision> findCollisions(
		long version,
		@Nonnull String collection,
		@Nonnull EntitySchema schema,
		@Nonnull Set<Integer> indexIds,
		@Nonnull OffsetIndexStoragePartPersistenceService parts
	) {
		final Iterable<EntityIndexStoragePart> manifests = () -> indexIds.stream().sorted()
			.map(id -> parts.getStoragePart(version, id, EntityIndexStoragePart.class))
			.filter(Objects::nonNull).iterator();
		final List<Collision> collisions = new ArrayList<>();
		for (final ManifestCollision collision : findCollidingManifests(manifests)) {
			final RepresentativeReferenceKey key =
				(RepresentativeReferenceKey) collision.first().getEntityIndexKey().discriminator();
			final List<String> names = schema.getReference(key.referenceName()).isEmpty() ? List.of() :
				schema.getReferenceOrThrowException(key.referenceName())
					.getRepresentativeAttributeDefinition().getAttributeNames();
			collisions.add(new Collision(collection, names, collision.first(), collision.second(),
				membership(version, collision.first(), parts), membership(version, collision.second(), parts)));
		}
		return collisions;
	}

	/**
	 * Finds different manifest ids with an equal complete key while preserving both raw manifest objects. Only keys of
	 * representative partitions holding a `DateTimeRange` value are considered: that is the only representative type
	 * whose equality became coarser since the releases, so no other partition key can collide.
	 *
	 * @param manifests the manifests of one collection
	 * @return every pair of the first manifest with a key and a later one with the equal key
	 */
	@Nonnull
	static List<ManifestCollision> findCollidingManifests(@Nonnull Iterable<EntityIndexStoragePart> manifests) {
		final Map<EntityIndexKey, EntityIndexStoragePart> first = new HashMap<>();
		final List<ManifestCollision> collisions = new ArrayList<>();
		for (final EntityIndexStoragePart manifest : manifests) {
			if (!(manifest.getEntityIndexKey().discriminator() instanceof RepresentativeReferenceKey key) ||
				Arrays.stream(key.representativeAttributeValues()).noneMatch(DateTimeRange.class::isInstance)) {
				continue;
			}
			final EntityIndexStoragePart previous = first.putIfAbsent(manifest.getEntityIndexKey(), manifest);
			if (previous != null && previous.getPrimaryKey() != manifest.getPrimaryKey()) {
				collisions.add(new ManifestCollision(previous, manifest));
			}
		}
		return collisions;
	}

	/**
	 * Two distinct persisted ids with one current key, retaining their original positional tuples.
	 *
	 * @param first the first stored manifest
	 * @param second the other manifest with the same current key
	 */
	record ManifestCollision(@Nonnull EntityIndexStoragePart first, @Nonnull EntityIndexStoragePart second) {
	}

	/** Reads current membership first and falls back to the inline membership of pre-2026.2 manifests. */
	@Nullable
	private static Bitmap membership(
		long version,
		@Nonnull EntityIndexStoragePart manifest,
		@Nonnull OffsetIndexStoragePartPersistenceService parts
	) {
		final EntityIdsStoragePart membership = parts.getStoragePart(
			version, manifest.getPrimaryKey(), EntityIdsStoragePart.class
		);
		return membership == null ? manifest.getEntityIds() : membership.getEntityIds();
	}

	/**
	 * Refuses before publication, preserving the incoming protocol and naming both original tuples.
	 *
	 * @param catalog          the catalog name
	 * @param incomingProtocol the storage protocol the catalog stays at, named in the refusal
	 * @param collisions       the colliding pairs; nothing happens when there are none
	 * @throws ObsoleteStorageProtocolException when any pair collides
	 */
	static void refuseCollisions(@Nonnull String catalog, int incomingProtocol, @Nonnull List<Collision> collisions) {
		if (collisions.isEmpty()) {
			return;
		}
		final String message = "Catalog `" + catalog + "` cannot be upgraded to storage protocol version " +
			PersistenceService.STORAGE_PROTOCOL_VERSION + ": " + String.join("; ",
			collisions.stream().map(Collision::describe).toList()) +
			". Both partitions cannot be published under one key by a part-local rewrite. " +
			"Nothing has been published; the catalog remains as the release left it at storage protocol version " +
			incomingProtocol + ". Open it with the evitaDB release that wrote it and change the representative " +
			"values so they remain distinct in this version, or republish the catalog from its source data. " +
			"Then retry the upgrade.";
		throw new ObsoleteStorageProtocolException(message, message);
	}

	/**
	 * Original manifests and membership for two different ids with one complete current index key.
	 *
	 * @param collection the owning collection
	 * @param attributeNames ordered representative names from the resolved schema
	 * @param first the first stored manifest, including its original tuple
	 * @param second the second stored manifest, including its original tuple
	 * @param firstOwners membership of the first partition, when available
	 * @param secondOwners membership of the second partition, when available
	 */
	record Collision(
		@Nonnull String collection,
		@Nonnull List<String> attributeNames,
		@Nonnull EntityIndexStoragePart first,
		@Nonnull EntityIndexStoragePart second,
		@Nullable Bitmap firstOwners,
		@Nullable Bitmap secondOwners
	) {
		/**
		 * Describes each raw tuple and the owner memberships without inferring reference row identities.
		 *
		 * @return the reference, target, scope, both partitions and their tuples, for the refusal message
		 */
		@Nonnull
		String describe() {
			final EntityIndexKey indexKey = this.first.getEntityIndexKey();
			final RepresentativeReferenceKey firstKey = (RepresentativeReferenceKey) indexKey.discriminator();
			final RepresentativeReferenceKey secondKey =
				(RepresentativeReferenceKey) this.second.getEntityIndexKey().discriminator();
			final StringBuilder description = new StringBuilder("reference `")
				.append(firstKey.referenceName()).append("` of collection `").append(this.collection)
				.append("` to target ").append(firstKey.primaryKey()).append(" in scope ").append(indexKey.scope())
				.append(" has two stored ").append(indexKey.type()).append(" partitions (")
				.append(this.first.getPrimaryKey()).append(", ").append(this.second.getPrimaryKey())
				.append(") whose representative keys are equal in this version. ")
				.append(tuple(firstKey)).append(" and ").append(tuple(secondKey));
			if (indexKey.type() == EntityIndexType.REFERENCED_ENTITY) {
				description.append("; partition ").append(this.first.getPrimaryKey()).append(" contains owners ")
					.append(owners(this.firstOwners)).append("; partition ").append(this.second.getPrimaryKey())
					.append(" contains owners ").append(owners(this.secondOwners));
				if (this.firstOwners != null && this.secondOwners != null) {
					for (final int owner : this.firstOwners.getArray()) {
						if (this.secondOwners.contains(owner)) {
							description.append(". Both stored partitions contain owner ").append(owner)
								.append("; folding them would discard reference data");
						}
					}
				}
			}
			return description.toString();
		}

		/** Formats positional attribute labels without inventing a name when schema and tuple lengths disagree. */
		@Nonnull
		private String tuple(@Nonnull RepresentativeReferenceKey key) {
			final Serializable[] values = key.representativeAttributeValues();
			if (values.length != this.attributeNames.size()) {
				return "unresolved representative attribute layout " + Arrays.toString(values);
			}
			final List<String> attributes = new ArrayList<>(values.length);
			for (int i = 0; i < values.length; i++) {
				attributes.add("attribute `" + this.attributeNames.get(i) + "` = `" + values[i] + "`");
			}
			return String.join(", ", attributes);
		}

		/** Formats membership only where it identifies owners; an absent part remains explicitly unknown. */
		@Nonnull
		private static String owners(@Nullable Bitmap membership) {
			return membership == null ? "unknown (membership unavailable)" : Arrays.toString(membership.getArray());
		}
	}
}
