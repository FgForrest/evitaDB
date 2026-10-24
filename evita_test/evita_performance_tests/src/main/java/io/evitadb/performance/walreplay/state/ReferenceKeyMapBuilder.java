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

package io.evitadb.performance.walreplay.state;

import io.evitadb.api.CatalogState;
import io.evitadb.api.EvitaSessionContract;
import io.evitadb.api.configuration.EvitaConfiguration;
import io.evitadb.api.configuration.StorageOptions;
import io.evitadb.api.requestResponse.data.ReferenceContract;
import io.evitadb.api.requestResponse.data.SealedEntity;
import io.evitadb.api.requestResponse.data.mutation.EntityMutation;
import io.evitadb.api.requestResponse.mutation.CatalogBoundMutation;
import io.evitadb.api.requestResponse.schema.EntitySchemaContract;
import io.evitadb.api.requestResponse.schema.ReferenceSchemaContract;
import io.evitadb.api.requestResponse.schema.ReflectedReferenceSchemaContract;
import io.evitadb.dataType.Scope;
import io.evitadb.core.Evita;
import io.evitadb.core.catalog.Catalog;
import io.evitadb.core.collection.EntityCollection;
import io.evitadb.exception.GenericEvitaInternalError;
import io.evitadb.performance.walreplay.state.WalReplayState.WalSource;
import io.evitadb.spi.store.catalog.persistence.storageParts.entity.ReferencesStoragePart;
import io.evitadb.utils.CollectionUtils;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.io.FileUtils;

import javax.annotation.Nonnull;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.stream.Stream;

import static io.evitadb.api.query.QueryConstraints.referenceContentAll;

/**
 * Writes the reference-key map {@link ReferenceKeyTranslator} needs to replay a source WAL over a catalog rebuilt from
 * the source snapshot - see {@link WalReplayState#REFERENCE_KEY_MAP_PROPERTY} for why the ids differ. Run it once per
 * rebuilt snapshot, before its replays, with the system properties the replay gets:
 *
 * - `{@value WalReplayState#WAL_SOURCE_DIR_PROPERTY}`, `{@value WalReplayState#WAL_CATALOG_NAME_PROPERTY}` and
 *   `{@value WalReplayState#START_VERSION_PROPERTY}` - the slice whose touched entities the map covers;
 * - `{@value WalReplayState#PRISTINE_DATA_DIR_PROPERTY}` and `{@value WalReplayState#CATALOG_NAME_PROPERTY}` - the
 *   rebuilt snapshot;
 * - `{@value WalReplayState#REFERENCE_KEY_MAP_PROPERTY}` - the file to write;
 * - `{@value WalReplayState#WORK_DIR_PROPERTY}` - both snapshots are opened from copies made here, because opening a
 *   snapshot may migrate its storage format and a pristine snapshot must stay as it was;
 *
 * plus `{@value #SOURCE_SNAPSHOT_DIR_PROPERTY}`, the snapshot the rebuild was made from.
 *
 * The map covers only the entities the slice touches - all entities of a production catalog would be millions of
 * rows. For each of them it pairs the references present in both snapshots per reference name and referenced entity
 * in ascending id order, which the rebuild preserves, and records both per-entity id counters. An entity the rebuild
 * does not hold is counted and skipped: the replay fails on it with or without a translated id.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@Slf4j
public class ReferenceKeyMapBuilder {

	/**
	 * System property naming the directory with the snapshot the rebuilt catalog was made from - it must contain a
	 * subfolder named after `{@value WalReplayState#WAL_CATALOG_NAME_PROPERTY}`, because that snapshot belongs to the
	 * catalog that wrote the WAL.
	 */
	public static final String SOURCE_SNAPSHOT_DIR_PROPERTY = "evita.replay.sourceSnapshotDir";

	/**
	 * The map must cover archived entities too - a rebuild copies them, and the slice may touch them.
	 */
	private static final Scope[] ALL_SCOPES = Scope.values();

	/**
	 * Builds the map from the system properties described in the class documentation and exits.
	 *
	 * @param args ignored
	 */
	public static void main(String[] args) {
		int status = 0;
		try {
			build();
		} catch (Exception ex) {
			log.error("Building the reference key map failed.", ex);
			status = 1;
		}
		// an engine that failed half-way may leave non-daemon threads behind, which would keep the JVM alive
		System.exit(status);
	}

	/**
	 * Reads the slice, collects the references of every entity it touches from copies of both snapshots and writes
	 * the map.
	 *
	 * @throws IOException when a snapshot cannot be copied, a WAL cannot be read or the map cannot be written
	 */
	static void build() throws IOException {
		final String catalogName = requiredProperty(WalReplayState.CATALOG_NAME_PROPERTY);
		final String walCatalogNameProperty = System.getProperty(WalReplayState.WAL_CATALOG_NAME_PROPERTY);
		final String walCatalogName = walCatalogNameProperty == null || walCatalogNameProperty.isBlank() ?
			catalogName : walCatalogNameProperty.trim();
		final long firstVersion = Long.parseLong(requiredProperty(WalReplayState.START_VERSION_PROPERTY)) + 1;
		final Path sourceDir = Path.of(requiredProperty(SOURCE_SNAPSHOT_DIR_PROPERTY));
		final Path targetDir = Path.of(requiredProperty(WalReplayState.PRISTINE_DATA_DIR_PROPERTY));
		final Path mapFile = Path.of(requiredProperty(WalReplayState.REFERENCE_KEY_MAP_PROPERTY));
		final Path workDir = Path.of(requiredProperty(WalReplayState.WORK_DIR_PROPERTY)).resolve("reference-key-map");
		final List<Path> walSourceDirs = new ArrayList<>(4);
		for (final String dir : requiredProperty(WalReplayState.WAL_SOURCE_DIR_PROPERTY).split(",")) {
			if (!dir.isBlank()) {
				walSourceDirs.add(Path.of(dir.trim()));
			}
		}

		final Map<String, Set<Integer>> touched = readTouchedEntities(walSourceDirs, walCatalogName, firstVersion);
		log.info(
			"The slice from version {} on touches {} entities of {} type(s).",
			firstVersion, touched.values().stream().mapToInt(Set::size).sum(), touched.size()
		);
		FileUtils.deleteDirectory(workDir.toFile());
		try {
			final Snapshot source = collect(
				copy(sourceDir, walCatalogName, workDir.resolve("source")), walCatalogName, touched
			);
			final Snapshot target = collect(
				copy(targetDir, catalogName, workDir.resolve("target")), catalogName, touched
			);
			write(mapFile, source, target);
		} finally {
			FileUtils.deleteDirectory(workDir.toFile());
		}
	}

	/**
	 * Returns the value of a required system property.
	 *
	 * @param propertyName the property name
	 * @return the value
	 */
	@Nonnull
	private static String requiredProperty(@Nonnull String propertyName) {
		final String value = System.getProperty(propertyName);
		if (value == null || value.isBlank()) {
			throw new GenericEvitaInternalError(
				"Required system property `" + propertyName + "` is not set - see " +
					ReferenceKeyMapBuilder.class.getSimpleName() + " JavaDoc.",
				"Required system property `" + propertyName + "` is not set."
			);
		}
		return value;
	}

	/**
	 * Collects the primary keys of the entities the slice touches, per entity type. Overlapping sources are merged.
	 *
	 * @param walSourceDirs  the WAL source directories
	 * @param walCatalogName the catalog that wrote the WAL
	 * @param firstVersion   the first version of the slice
	 * @return touched primary keys keyed by entity type
	 * @throws IOException when a WAL cannot be read
	 */
	@Nonnull
	private static Map<String, Set<Integer>> readTouchedEntities(
		@Nonnull List<Path> walSourceDirs,
		@Nonnull String walCatalogName,
		long firstVersion
	) throws IOException {
		final Map<String, Set<Integer>> touched = new TreeMap<>();
		for (final Path walSourceDir : walSourceDirs) {
			final WalSource walSource = WalReplayState.openWalSource(walSourceDir, walCatalogName);
			try (final Stream<CatalogBoundMutation> stream = walSource.wal().getCommittedMutationStream(firstVersion)) {
				final Iterator<CatalogBoundMutation> iterator = stream.iterator();
				while (iterator.hasNext()) {
					if (iterator.next() instanceof EntityMutation entityMutation &&
						entityMutation.getEntityPrimaryKey() != null) {
						final Set<Integer> ofType = touched.computeIfAbsent(
							entityMutation.getEntityType(), type -> CollectionUtils.createHashSet(1024)
						);
						ofType.add(entityMutation.getEntityPrimaryKey());
					}
				}
			} finally {
				walSource.close();
			}
		}
		if (touched.isEmpty()) {
			throw new GenericEvitaInternalError(
				"No WAL source holds an entity mutation from version " + firstVersion + " on - check `" +
					WalReplayState.START_VERSION_PROPERTY + "` and `" + WalReplayState.WAL_SOURCE_DIR_PROPERTY + "`.",
				"No WAL source holds an entity mutation of the slice."
			);
		}
		return touched;
	}

	/**
	 * Copies a snapshot's catalog folder into a scratch directory.
	 *
	 * @param snapshotDir the directory holding the catalog folder
	 * @param catalogName the catalog folder name
	 * @param copyDir     the scratch directory
	 * @return the scratch directory, now holding the copy
	 * @throws IOException when the copy fails
	 */
	@Nonnull
	private static Path copy(@Nonnull Path snapshotDir, @Nonnull String catalogName, @Nonnull Path copyDir)
		throws IOException {
		log.info("Copying `{}` from `{}` to `{}`...", catalogName, snapshotDir, copyDir);
		FileUtils.copyDirectory(snapshotDir.resolve(catalogName).toFile(), copyDir.resolve(catalogName).toFile());
		return copyDir;
	}

	/**
	 * Boots a snapshot and collects, for every touched entity it holds, its non-reflected references and the last id
	 * its reference counter handed out.
	 *
	 * @param storageDir  the storage directory holding the catalog
	 * @param catalogName the catalog
	 * @param touched     touched primary keys keyed by entity type
	 * @return the collected references
	 */
	@Nonnull
	private static Snapshot collect(
		@Nonnull Path storageDir,
		@Nonnull String catalogName,
		@Nonnull Map<String, Set<Integer>> touched
	) {
		final long start = System.nanoTime();
		final Map<EntityId, EntityReferences> entities = new TreeMap<>();
		final Set<String> reflectedReferences = new TreeSet<>();
		final EvitaConfiguration configuration = EvitaConfiguration.builder()
			.storage(
				StorageOptions.builder()
					.storageDirectory(storageDir)
					// a server may have stored compressed records, which only an instance with compression on can read
					.compress(true)
					.build()
			)
			.build();
		try (final Evita evita = new Evita(configuration)) {
			awaitCatalogAlive(evita, catalogName);
			final Catalog catalog = (Catalog) evita.getCatalogInstance(catalogName).orElseThrow();
			try (final EvitaSessionContract session = evita.createReadOnlySession(catalogName)) {
				final long catalogVersion = session.getCatalogVersion();
				for (final Map.Entry<String, Set<Integer>> touchedOfType : touched.entrySet()) {
					final String entityType = touchedOfType.getKey();
					final EntitySchemaContract schema = session.getEntitySchema(entityType).orElse(null);
					if (schema == null) {
						continue;
					}
					final Set<String> reflectedNames = CollectionUtils.createHashSet(4);
					for (final ReferenceSchemaContract reference : schema.getReferences().values()) {
						if (reference instanceof ReflectedReferenceSchemaContract) {
							reflectedNames.add(reference.getName());
							reflectedReferences.add(entityType + '\t' + reference.getName());
						}
					}
					final EntityCollection collection = (EntityCollection) catalog
						.getCollectionForEntity(entityType).orElseThrow();
					for (final Integer primaryKey : touchedOfType.getValue()) {
						final SealedEntity entity = session
							.getEntity(entityType, primaryKey, ALL_SCOPES, referenceContentAll())
							.orElse(null);
						if (entity == null) {
							continue;
						}
						final List<Ref> references = new ArrayList<>(entity.getReferences().size());
						for (final ReferenceContract reference : entity.getReferences()) {
							if (!reflectedNames.contains(reference.getReferenceName())) {
								references.add(new Ref(
									reference.getReferenceName(), reference.getReferencedPrimaryKey(),
									reference.getReferenceKey().internalPrimaryKey()
								));
							}
						}
						final ReferencesStoragePart storagePart = collection.getDataStoreReader()
							.fetch(catalogVersion, primaryKey, ReferencesStoragePart.class);
						entities.put(
							new EntityId(entityType, primaryKey),
							new EntityReferences(
								storagePart == null ? 0 : storagePart.getLastUsedPrimaryKey(), references
							)
						);
					}
				}
			}
		}
		log.info(
			"Collected {} touched entities from `{}` in {} s.",
			entities.size(), catalogName, Duration.ofNanos(System.nanoTime() - start).toSeconds()
		);
		return new Snapshot(entities, reflectedReferences);
	}

	/**
	 * Waits until the freshly booted catalog leaves its transitional state.
	 *
	 * @param evita       the engine
	 * @param catalogName the catalog
	 */
	private static void awaitCatalogAlive(@Nonnull Evita evita, @Nonnull String catalogName) {
		final long deadlineNanos = System.nanoTime() + Duration.ofMinutes(30).toNanos();
		while (evita.getCatalogState(catalogName).map(CatalogState::isTransitional).orElse(false)) {
			if (System.nanoTime() > deadlineNanos) {
				throw new GenericEvitaInternalError(
					"Timed out waiting for catalog `" + catalogName + "` to leave its transitional state.",
					"Timed out waiting for the catalog to leave its transitional state."
				);
			}
			try {
				Thread.sleep(200);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				throw new GenericEvitaInternalError(
					"Interrupted while waiting for catalog `" + catalogName + "`.",
					"Interrupted while waiting for the catalog.",
					e
				);
			}
		}
	}

	/**
	 * Writes the map in the format {@link ReferenceKeyTranslator#load(Path)} reads.
	 *
	 * @param mapFile the file to write
	 * @param source  references of the snapshot that wrote the WAL
	 * @param target  references of the rebuilt snapshot
	 * @throws IOException when the file cannot be written
	 */
	private static void write(@Nonnull Path mapFile, @Nonnull Snapshot source, @Nonnull Snapshot target)
		throws IOException {
		long missingInTarget = 0;
		long countersDiffer = 0;
		long pairs = 0;
		long idsDiffer = 0;
		long groupsDiffer = 0;
		try (final PrintWriter out = new PrintWriter(Files.newBufferedWriter(mapFile, StandardCharsets.UTF_8))) {
			for (final String reflected : source.reflectedReferences()) {
				out.println("X\t" + reflected);
			}
			for (final Map.Entry<EntityId, EntityReferences> entry : source.entities().entrySet()) {
				final EntityId id = entry.getKey();
				final EntityReferences sourceReferences = entry.getValue();
				final EntityReferences targetReferences = target.entities().get(id);
				if (targetReferences == null) {
					missingInTarget++;
					continue;
				}
				out.println(
					"E\t" + id.entityType() + '\t' + id.primaryKey() + '\t' +
						sourceReferences.lastUsedPrimaryKey() + '\t' + targetReferences.lastUsedPrimaryKey()
				);
				if (sourceReferences.lastUsedPrimaryKey() != targetReferences.lastUsedPrimaryKey()) {
					countersDiffer++;
				}
				final Map<String, List<Ref>> targetGroups = group(targetReferences.references());
				for (final Map.Entry<String, List<Ref>> sourceGroup : group(sourceReferences.references()).entrySet()) {
					final List<Ref> sourceRefs = sourceGroup.getValue();
					final List<Ref> targetRefs = targetGroups.getOrDefault(sourceGroup.getKey(), List.of());
					if (sourceRefs.size() != targetRefs.size()) {
						groupsDiffer++;
					}
					for (int i = 0; i < Math.min(sourceRefs.size(), targetRefs.size()); i++) {
						final Ref sourceRef = sourceRefs.get(i);
						final Ref targetRef = targetRefs.get(i);
						out.println(
							"R\t" + id.entityType() + '\t' + id.primaryKey() + '\t' + sourceRef.referenceName() + '\t' +
								sourceRef.referencedPrimaryKey() + '\t' + sourceRef.internalPrimaryKey() + '\t' +
								targetRef.internalPrimaryKey()
						);
						pairs++;
						if (sourceRef.internalPrimaryKey() != targetRef.internalPrimaryKey()) {
							idsDiffer++;
						}
					}
				}
			}
		}
		log.info(
			"Reference key map `{}` written: {} entities ({} missing in the rebuild, {} with different counters), " +
				"{} reference pairs ({} with a different id), {} reference groups of different size, reflected {}.",
			mapFile, source.entities().size() - missingInTarget, missingInTarget, countersDiffer, pairs, idsDiffer,
			groupsDiffer, source.reflectedReferences()
		);
	}

	/**
	 * Groups references by reference name and referenced entity, each group ordered by internal id.
	 *
	 * @param references the references of one entity
	 * @return the groups keyed by name and referenced primary key
	 */
	@Nonnull
	private static Map<String, List<Ref>> group(@Nonnull List<Ref> references) {
		final Map<String, List<Ref>> groups = CollectionUtils.createHashMap(references.size());
		for (final Ref reference : references) {
			groups.computeIfAbsent(
				reference.referenceName() + '\t' + reference.referencedPrimaryKey(), key -> new ArrayList<>(2)
			).add(reference);
		}
		for (final List<Ref> group : groups.values()) {
			group.sort(Comparator.comparingInt(Ref::internalPrimaryKey));
		}
		return groups;
	}

	/**
	 * Identifies an entity across both snapshots; ordered so the map file is written deterministically.
	 *
	 * @param entityType the entity type
	 * @param primaryKey the primary key
	 */
	private record EntityId(@Nonnull String entityType, int primaryKey) implements Comparable<EntityId> {

		@Override
		public int compareTo(@Nonnull EntityId other) {
			final int byType = this.entityType.compareTo(other.entityType);
			return byType == 0 ? Integer.compare(this.primaryKey, other.primaryKey) : byType;
		}

	}

	/**
	 * One non-reflected reference of an entity.
	 *
	 * @param referenceName        the reference name
	 * @param referencedPrimaryKey the referenced entity's primary key
	 * @param internalPrimaryKey   the internal id the snapshot assigned to the reference
	 */
	private record Ref(@Nonnull String referenceName, int referencedPrimaryKey, int internalPrimaryKey) {
	}

	/**
	 * References of one entity in one snapshot.
	 *
	 * @param lastUsedPrimaryKey the last internal id the entity's reference counter handed out
	 * @param references         the entity's non-reflected references
	 */
	private record EntityReferences(int lastUsedPrimaryKey, @Nonnull List<Ref> references) {
	}

	/**
	 * Everything collected from one snapshot.
	 *
	 * @param entities            references per touched entity the snapshot holds
	 * @param reflectedReferences reflected references as `type TAB name`
	 */
	private record Snapshot(
		@Nonnull Map<EntityId, EntityReferences> entities,
		@Nonnull Set<String> reflectedReferences
	) {
	}

}
