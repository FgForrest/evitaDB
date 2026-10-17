/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2024-2026
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

package io.evitadb.api;

import io.evitadb.api.configuration.EvitaConfiguration;
import io.evitadb.api.configuration.ServerOptions;
import io.evitadb.api.configuration.StorageOptions;
import io.evitadb.api.index.EntityIndexType;
import io.evitadb.api.query.Query;
import io.evitadb.api.query.order.OrderDirection;
import io.evitadb.api.requestResponse.EvitaResponse;
import io.evitadb.api.requestResponse.data.AttributesContract.AttributeValue;
import io.evitadb.api.requestResponse.data.EntityReferenceContract;
import io.evitadb.api.requestResponse.data.ReferenceContract;
import io.evitadb.api.requestResponse.data.SealedEntity;
import io.evitadb.api.requestResponse.mutation.CatalogBoundMutation;
import io.evitadb.api.requestResponse.mutation.infrastructure.TransactionMutation;
import io.evitadb.api.requestResponse.schema.AttributeSchemaContract;
import io.evitadb.api.requestResponse.schema.ReferenceIndexType;
import io.evitadb.api.requestResponse.schema.ReferenceIndexedComponents;
import io.evitadb.api.requestResponse.schema.ReferenceSchemaContract;
import io.evitadb.api.requestResponse.schema.SealedEntitySchema;
import io.evitadb.api.requestResponse.system.SystemStatus;
import io.evitadb.api.statistics.BrowsedIndex;
import io.evitadb.api.statistics.CatalogStatisticsComponent;
import io.evitadb.api.statistics.CollectionIndexCardinality;
import io.evitadb.api.statistics.CollectionIndexCardinality.IndexCardinality;
import io.evitadb.api.statistics.IndexBrowseCriteria;
import io.evitadb.api.statistics.IndexBrowseOrdering;
import io.evitadb.api.statistics.IndexBrowseResult;
import io.evitadb.core.Evita;
import io.evitadb.dataType.Scope;
import io.evitadb.spi.store.catalog.persistence.PersistenceService;
import io.evitadb.store.catalog.OfflineCatalogImages;
import io.evitadb.store.catalog.model.CatalogBootstrap;
import io.evitadb.test.EvitaTestSupport;
import io.evitadb.utils.CollectionUtils;
import io.evitadb.utils.FileUtils;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.IOException;
import java.io.InputStream;
import java.io.Serializable;
import java.net.MalformedURLException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Objects;
import java.util.Set;
import java.util.SortedMap;
import java.util.SortedSet;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.UUID;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static io.evitadb.test.TestTags.SLOW;
import static io.evitadb.api.configuration.StorageOptions.DEFAULT_OUTPUT_BUFFER_SIZE;
import static io.evitadb.api.query.QueryConstraints.collection;
import static io.evitadb.api.query.QueryConstraints.entityFetch;
import static io.evitadb.api.query.QueryConstraints.entityFetchAll;
import static io.evitadb.api.query.QueryConstraints.entityPrimaryKeyInSet;
import static io.evitadb.api.query.QueryConstraints.filterBy;
import static io.evitadb.api.query.QueryConstraints.page;
import static io.evitadb.api.query.QueryConstraints.referenceContentAllWithAttributes;
import static io.evitadb.api.query.QueryConstraints.referenceHaving;
import static io.evitadb.api.query.QueryConstraints.require;
import static io.evitadb.api.query.QueryConstraints.scope;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static io.evitadb.test.TestTags.CONTRACT;
import static io.evitadb.test.TestTags.QUERY;

/**
 * This test verifies that data stored in older versions of evitaDB are still readable by the current version.
 *
 * Every dataset reaches the current storage protocol through the migration converting the single-owner partitions of
 * the filtering-only references into registry rows, so the test also verifies the conversion: the catalog header on
 * disk is of the current protocol, the converted references hold registry rows, the owners a `referenceHaving` query
 * answers for their targets are those the entity bodies name, and an engine restarted over the converted catalog
 * runs no migration and answers the same.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2024
 */
@Slf4j
@Tag(CONTRACT)
@Tag(QUERY)
public class EvitaBackwardCompatibilityTest implements EvitaTestSupport {
	private static final String DIRECTORY = "evita-backward-compatibility";
	/**
	 * How many targets of one filtering-only reference in one scope have their owners checked by a query.
	 */
	private static final int TARGETS_PER_REFERENCE = 50;
	/**
	 * How many entity bodies one query reads.
	 */
	private static final int BODY_PAGE_SIZE = 1_000;
	/**
	 * How many disagreements with the entity bodies a failure lists at most.
	 */
	private static final int MAX_FINDINGS = 40;
	/**
	 * The datasets written by a release older than 2025.7, whose referenced-type entity indexes keep target primary
	 * keys in their own primary-key bitmap - see {@link #checkFilteringOnlyPartitions} for what that does to the
	 * answers.
	 */
	private static final Set<String> DATASETS_WITH_TARGET_KEYED_TYPE_INDEXES = Set.of("2025.1", "2025.3", "2025.6");
	private Path mainDirectory;

	@BeforeEach
	void setUp() throws MalformedURLException {
		this.mainDirectory = Path.of(System.getProperty("java.io.tmpdir")).resolve(DIRECTORY);
		if (!this.mainDirectory.toFile().exists()) {
			this.mainDirectory.toFile().mkdirs();
		}
	}

	@Tag(SLOW)
	// 2024.5 (and older) is intentionally absent: the type-less 2024.5 filter-index storage format is no longer
	// supported (its backward-compatible serializer was removed). Such catalogs must be upgraded through an
	// intermediate 2025.x release first; attempting a direct load now fails fast with StoredVersionNotSupportedException.
	//
	// 2026.2 is the one that exercises the storage-protocol 6 -> 8 upgrade: a catalog written by that release is
	// already at protocol 6 (Migration_2026_2 took it there), so loading it here is the only coverage that actually
	// runs Migration_2026_3 — which re-keys every persisted AttributeCardinalityIndex onto the canonical form its
	// value tree keys on, whose storage part changed serialVersionUID in the same change, and converts the
	// single-owner reference partitions into registry rows. The older fixtures reach protocol 8 too, but by chaining
	// through every earlier step; this one starts adjacent to it.
	@ParameterizedTest
	@ValueSource(
		strings = {"2025.1", "2025.3", "2025.6", "2026.1", "2026.2"}
	)
	void verifyBackwardCompatibilityTo(String version) throws IOException {
		final Path targetDirectory = this.mainDirectory.resolve(version);
		targetDirectory.toFile().mkdirs();

		final String fileName = "evita-demo-dataset_" + version + ".zip";
		final Path targetZipFile = targetDirectory.resolve(fileName);

		// download the zip only if it doesn't exist or is empty (cache for repeated runs)
		if (!targetZipFile.toFile().exists() || targetZipFile.toFile().length() == 0) {
			log.info("Downloading " + fileName);
			try (final InputStream is = new URL("https://evitadb.io/download/test/" + fileName).openStream()) {
				Files.copy(is, targetZipFile, StandardCopyOption.REPLACE_EXISTING);
			}

			assertTrue(targetZipFile.toFile().exists(), "File " + fileName + " does not exist!");
			assertTrue(targetZipFile.toFile().length() > 0, "File " + fileName + " is empty!");
		} else {
			log.info("Using cached " + fileName);
		}

		// clean everything except the zip (evitaDB modifies the storage directory on startup)
		try (final Stream<Path> entries = Files.list(targetDirectory)) {
			entries
				.filter(entry -> !entry.equals(targetZipFile))
				.forEach(entry -> {
					if (entry.toFile().isDirectory()) {
						FileUtils.deleteDirectory(entry);
					} else {
						FileUtils.deleteFileIfExists(entry);
					}
				});
		}

		// unzip the file fresh
		log.info("Unzipping " + fileName);
		try (
			final InputStream is = Files.newInputStream(targetZipFile);
			final ZipInputStream zis = new ZipInputStream(is)
		) {
			ZipEntry zipEntry = zis.getNextEntry();
			while (zipEntry != null) {
				final Path newPath = targetDirectory.resolve(zipEntry.getName());
				if (zipEntry.isDirectory()) {
					Files.createDirectories(newPath);
				} else {
					// make sure parent directory exists
					Files.createDirectories(newPath.getParent());
					Files.copy(zis, newPath, StandardCopyOption.REPLACE_EXISTING);
				}
				zipEntry = zis.getNextEntry();
			}
		}

		log.info("Starting Evita with backward compatibility to " + version);
		final TestPaths testPaths = createTestPaths("EvitaBackwardCompatibilityTest");
		final EvitaConfiguration configuration = newTestEvitaConfigurationBuilder(testPaths)
			.server(
				ServerOptions.builder()
					.closeSessionsAfterSecondsOfInactivity(-1)
					.build()
			)
			.storage(
				StorageOptions.builder()
					.storageDirectory(targetDirectory)
					.workDirectory(testPaths.work())
					.outputBufferSize(DEFAULT_OUTPUT_BUFFER_SIZE << 1)
					.build()
			)
			.build();
		try {
			final String catalogName;
			final PartitionCheck converted;
			try (final Evita evita = new Evita(configuration)) {

				// Old-format catalogs are upgraded asynchronously by the engine: the boot-time load throws
				// CatalogRequiresUpgradeException, the failure handler issues an UpgradeCatalogFormatMutation
				// through EngineTransactionManager, and on its completion the load is retried under
				// LoadMode.RETRY_AFTER_UPGRADE. The first-attempt future stays exceptionally completed —
				// waitUntilFullyInitialized() would propagate the upgrade-required exception — so we poll
				// for the upgraded catalog to settle into ALIVE state instead. System CDC is not a viable
				// signal here because the retry's `replaceCatalogReference` is a pure in-memory engine-state
				// update — no system CDC event marks the final BEING_ACTIVATED → ALIVE transition.
				waitForAtLeastOneAliveCatalog(evita, version);

				final SystemStatus status = evita.management().getSystemStatus();
				assertEquals(0, status.catalogsCorrupted());
				assertEquals(1, status.catalogsActive());
				catalogName = evita.getCatalogNames()
					.stream()
					.filter(it -> evita.getCatalogState(it).map(state -> state == CatalogState.ALIVE).orElse(false))
					.findFirst()
					.orElseThrow();

				// check the catalog has its own id
				final UUID catalogId = evita.queryCatalog(
					catalogName,
					session -> {
						for (String entityType : session.getAllEntityTypes()) {
							log.info("Entity type: {}", entityType);
							if (session.getEntityCollectionSize(entityType) > 0) {
								final List<SealedEntity> sealedEntities = session.queryListOfSealedEntities(
									Query.query(
										collection(entityType),
										require(
											page(1, 20),
											entityFetchAll()
										)
									)
								);
								for (SealedEntity sealedEntity : sealedEntities) {
									assertNotNull(sealedEntity);
								}
							}
						}

						return session.getCatalogId();
					}
				);
				assertNotNull(catalogId);

				// read entire WAL contents from oldest to newest record
				readWalContents(evita, catalogName);

				// the single-owner partitions the migration converted answer as the entity bodies say they must
				converted = checkFilteringOnlyPartitions(evita, catalogName, version);
			}

			// the migration published its result: the catalog on disk is of the current storage protocol
			assertCurrentStorageProtocolOnDisk(configuration, catalogName, version);
			if (converted.filteringOnlyReferences().isEmpty()) {
				// nothing to convert - the check found no registry row under any reference, which it asserts per
				// reference type index
				log.info("{}: the catalog declares no filtering-only reference", version);
			} else {
				assertTrue(
					converted.registryRowCount() > 0,
					version + ": the conversion turned no single-owner partition into a registry row: " + converted
				);
				assertTrue(
					converted.checkedRowTargets() > 0,
					version + ": no checked target is kept as a registry row: " + converted
				);
				assertEquals(
					converted.partitionObjectCount() > 0, converted.checkedObjectTargets() > 0,
					version + ": a shared index object must be checked exactly when the catalog holds one: " +
						converted
				);
				if (DATASETS_WITH_TARGET_KEYED_TYPE_INDEXES.contains(version)) {
					// the characterized loss of owners must show up - once it does not, the type index bitmap no
					// longer holds target keys and the dataset belongs among the strictly checked ones
					assertTrue(
						converted.diminishedAnswers() > 0,
						version + ": no checked target answered fewer owners than its bodies name, although the " +
							"catalog was written before 2025.7 - its referenced-type entity indexes no longer keep " +
							"target primary keys, so check its answers strictly: " + converted
					);
				}
			}

			// a restart on the same folder loads the catalog as it is - its header is of the current protocol, which
			// no migration starts from - and must answer exactly as the engine that converted it
			log.info("Restarting Evita over the converted catalog of " + version);
			try (final Evita evita = new Evita(configuration)) {
				waitForAtLeastOneAliveCatalog(evita, version);
				assertEquals(0, evita.management().getSystemStatus().catalogsCorrupted());
				assertEquals(
					converted, checkFilteringOnlyPartitions(evita, catalogName, version),
					version + ": the restarted engine must answer as the engine that converted the catalog"
				);
			}
			log.info(
				"{}: storage protocol {} on disk; filtering-only references {}; registry rows {} {}; shared index " +
					"objects {} {}; targets checked {} ({} registry rows, {} shared index objects, {} answered fewer " +
					"owners than the bodies name); restart without a migration answered the same",
				version, PersistenceService.STORAGE_PROTOCOL_VERSION, converted.filteringOnlyReferences(),
				converted.registryRowCount(), converted.registryRows(),
				converted.partitionObjectCount(), converted.partitionObjects(),
				converted.owners().size(), converted.checkedRowTargets(), converted.checkedObjectTargets(),
				converted.diminishedAnswers()
			);
		} finally {
			cleanupTestPaths(testPaths);
		}
	}

	/**
	 * Checks every filtering-only reference of the catalog - indexed `FOR_FILTERING` with the referenced entity
	 * component in a scope, the references whose single-owner partitions the storage migration converts into registry
	 * rows - against the entity bodies, which know nothing of how a partition is represented. The bodies group the
	 * rows of their owners into partitions as the indexes do: by the target and, for a reference allowing duplicates,
	 * by the values of its representative attributes.
	 *
	 * - For up to {@link #TARGETS_PER_REFERENCE} targets of the reference in the scope - the lowest primary keys the
	 *   bodies reference, the last of them swapped for the lowest target of a kind the others lack, so that a target
	 *   with a single-owner partition and one with a shared partition are both checked wherever the reference has
	 *   one - the owners `referenceHaving(reference, entityPrimaryKeyInSet(target))` answers equal the owners whose
	 *   bodies reference the target. A target of a managed entity type that exists in no scope is not asked about: the
	 *   query drops a reference to a missing managed entity by design, while its partition stays. On a dataset written
	 *   before 2025.7 ({@link #DATASETS_WITH_TARGET_KEYED_TYPE_INDEXES}) the answer is characterized instead: it must
	 *   be a subset of those owners, and the targets answering fewer owners are counted.
	 * - The reference type index holds one registry row per partition with a single owner, as its
	 *   {@link IndexCardinality#virtualPartitionCountIfKnown()} reports.
	 * - The collection holds one index object per partition with several owners, covering exactly them, as an index
	 *   browse lists them.
	 * - No reference type index of a reference that is not filtering-only in its scope holds a registry row.
	 *
	 * Every disagreement is collected, and the check fails listing them.
	 *
	 * @param evita       the engine holding the catalog
	 * @param catalogName the catalog
	 * @param version     the dataset version, used in failure messages
	 * @return what the check found, for the restarted engine to be compared with and for the report
	 */
	@Nonnull
	private static PartitionCheck checkFilteringOnlyPartitions(
		@Nonnull Evita evita,
		@Nonnull String catalogName,
		@Nonnull String version
	) {
		final List<String> filteringOnlyReferences = new ArrayList<>(16);
		final SortedMap<String, Integer> registryRows = new TreeMap<>();
		final SortedMap<String, Integer> partitionObjects = new TreeMap<>();
		final SortedMap<String, List<Integer>> owners = new TreeMap<>();
		final List<String> findings = new ArrayList<>(8);
		int checkedRowTargets = 0;
		int checkedObjectTargets = 0;
		int diminishedAnswers = 0;
		final boolean targetKeyedTypeIndexes = DATASETS_WITH_TARGET_KEYED_TYPE_INDEXES.contains(version);
		try (final EvitaSessionContract session = evita.createReadOnlySession(catalogName)) {
			for (final String entityType : new TreeSet<>(session.getAllEntityTypes())) {
				final SealedEntitySchema schema = session.getEntitySchemaOrThrowException(entityType);
				final Map<String, Integer> rowsByTypeIndex = registryRowsOf(evita, catalogName, entityType);
				for (final Scope scope : Scope.values()) {
					final List<ReferenceSchemaContract> references = filteringOnlyReferencesOf(schema, scope);
					if (references.isEmpty()) {
						continue;
					}
					final Map<String, BodyOwners> ownersFromBodies = readOwnersFromBodies(
						session, entityType, scope, references
					);
					for (final ReferenceSchemaContract referenceSchema : references) {
						final String referenceName = referenceSchema.getName();
						final String reference = entityType + "." + referenceName + " in " + scope;
						final BodyOwners bodyOwners = ownersFromBodies.get(referenceName);
						final SortedMap<Integer, List<Integer>> objects = partitionObjectsOf(
							evita, catalogName, entityType, referenceName, scope
						);
						filteringOnlyReferences.add(reference);

						// the owners the query answers for a target are the owners whose bodies reference it - except
						// for a target of a managed entity type that exists in no scope: the query drops a reference
						// to a missing managed entity (`ReferencedEntityIndexPrimaryKeyTranslatingFormula`), while its
						// partition stays and is counted below, so such a target is not asked about
						final Set<Integer> existingTargets = referenceSchema.isReferencedEntityTypeManaged() ?
							existingEntities(
								session, referenceSchema.getReferencedEntityType(),
								bodyOwners.ownersByTarget().keySet()
							) :
							null;
						final List<Integer> targets = selectTargets(bodyOwners, existingTargets);
						final List<String> wrongAnswers = new ArrayList<>(4);
						for (final int target : targets) {
							final List<Integer> expected = List.copyOf(bodyOwners.ownersByTarget().get(target));
							final List<Integer> answered = ownersByQuery(
								session, entityType, referenceName, scope, target, expected.size()
							);
							if (targetKeyedTypeIndexes) {
								// catalogs written by 2025.6 or earlier keep TARGET primary keys in the
								// REFERENCED_ENTITY_TYPE index's own primary-key bitmap; since 2025.7 (commit
								// ee0def7167, #906) the bitmap holds partition primary keys, and no migration rewrites
								// it - so the superset post-processor of `EntityPrimaryKeyInSetTranslator` ANDs the
								// translated partition keys with target keys, which can only remove owners from the
								// answer
								if (!new HashSet<>(expected).containsAll(answered)) {
									wrongAnswers.add(
										"target " + target + ": the query answered the owners " + answered + ", not " +
											"a subset of the owners the bodies name " + expected + " - " +
											describeTarget(session, referenceSchema, target, bodyOwners, objects)
									);
								} else if (answered.size() < expected.size()) {
									diminishedAnswers++;
								}
							} else if (!expected.equals(answered)) {
								wrongAnswers.add(
									"target " + target + ": the query answered the owners " + answered + ", the " +
										"bodies name " + expected + " - " +
										describeTarget(session, referenceSchema, target, bodyOwners, objects)
								);
							}
							owners.put(reference + " -> " + target, answered);
							if (bodyOwners.hasSingleOwnerPartition(target)) {
								checkedRowTargets++;
							}
							if (bodyOwners.hasSharedPartition(target)) {
								checkedObjectTargets++;
							}
						}
						if (!wrongAnswers.isEmpty()) {
							findings.add(
								reference + ": " + wrongAnswers.size() + " of " + targets.size() + " checked " +
									"target(s) answered with other owners than the bodies name, for example\n\t" +
									String.join("\n\t", wrongAnswers.subList(0, Math.min(wrongAnswers.size(), 3)))
							);
						}

						// a partition with one owner is a registry row, a partition with several an index object
						// covering exactly them
						final Integer rows = rowsByTypeIndex.remove(referenceName + " in " + scope);
						final int expectedRows = bodyOwners.singleOwnerPartitionCount();
						if (expectedRows != (rows == null ? 0 : rows)) {
							findings.add(
								reference + ": the reference type index holds " + rows + " registry row(s), the " +
									"bodies name " + expectedRows + " partition(s) with a single owner"
							);
						}
						final SortedMap<Integer, List<Integer>> expectedObjects = bodyOwners.sharedPartitions();
						if (!expectedObjects.equals(objects)) {
							findings.add(
								reference + ": the index objects differ from the partitions with several owners " +
									"the bodies name - " + describeDifference(expectedObjects, objects)
							);
						}
						registryRows.put(reference, expectedRows);
						int objectCount = 0;
						for (final List<Integer> targetObjects : expectedObjects.values()) {
							objectCount += targetObjects.size();
						}
						partitionObjects.put(reference, objectCount);
					}
				}
				// neither the conversion nor the write path keeps a row for a partition that is not filtering-only
				for (final Entry<String, Integer> typeIndex : rowsByTypeIndex.entrySet()) {
					if (typeIndex.getValue() != 0) {
						findings.add(
							entityType + "." + typeIndex.getKey() + ": not filtering-only, yet the reference type " +
								"index holds " + typeIndex.getValue() + " registry row(s)"
						);
					}
				}
			}
		}
		if (!findings.isEmpty()) {
			fail(
				version + ": the filtering-only references disagree with the entity bodies in " + findings.size() +
					" place(s)" + (findings.size() > MAX_FINDINGS ? ", the first " + MAX_FINDINGS + " of them" : "") +
					":\n" + String.join("\n", findings.subList(0, Math.min(findings.size(), MAX_FINDINGS)))
			);
		}
		return new PartitionCheck(
			filteringOnlyReferences, registryRows, partitionObjects, owners, checkedRowTargets, checkedObjectTargets,
			diminishedAnswers
		);
	}

	/**
	 * Returns the references of the collection that are filtering-only in the scope - indexed `FOR_FILTERING` with
	 * the referenced entity component, the references whose single-owner partitions are kept as registry rows - in the
	 * order of their names.
	 *
	 * @param schema the schema of the collection
	 * @param scope  the scope
	 * @return the filtering-only references
	 */
	@Nonnull
	private static List<ReferenceSchemaContract> filteringOnlyReferencesOf(
		@Nonnull SealedEntitySchema schema,
		@Nonnull Scope scope
	) {
		final List<ReferenceSchemaContract> references = new ArrayList<>(schema.getReferences().size());
		for (final ReferenceSchemaContract reference : schema.getReferences().values()) {
			if (
				reference.getReferenceIndexType(scope) == ReferenceIndexType.FOR_FILTERING &&
					reference.getIndexedComponents(scope).contains(ReferenceIndexedComponents.REFERENCED_ENTITY)
			) {
				references.add(reference);
			}
		}
		references.sort(Comparator.comparing(ReferenceSchemaContract::getName));
		return references;
	}

	/**
	 * Reads the body of every entity of the collection in the scope, page by page, and collects the owners of every
	 * partition of the given references - the partitions the indexes must hold, derived without any index. A partition
	 * is a target together with, for a reference allowing duplicates, the values of its representative attributes in
	 * the order of their names, a missing one taking the attribute's default - the key the indexes partition by.
	 *
	 * @param session    the session to read the bodies in
	 * @param entityType the collection
	 * @param scope      the scope
	 * @param references the references
	 * @return the owners of every reference, by its name
	 */
	@Nonnull
	private static Map<String, BodyOwners> readOwnersFromBodies(
		@Nonnull EvitaSessionContract session,
		@Nonnull String entityType,
		@Nonnull Scope scope,
		@Nonnull List<ReferenceSchemaContract> references
	) {
		final Map<String, Map<PartitionKey, SortedSet<Integer>>> ownersByPartition =
			CollectionUtils.createHashMap(references.size());
		final Map<String, List<String>> representativeAttributes = CollectionUtils.createHashMap(references.size());
		for (final ReferenceSchemaContract reference : references) {
			ownersByPartition.put(reference.getName(), new HashMap<>());
			final List<String> names = new ArrayList<>(2);
			if (reference.getCardinality().allowsDuplicates()) {
				for (final AttributeSchemaContract attribute : reference.getAttributes().values()) {
					if (attribute.isRepresentative()) {
						names.add(attribute.getName());
					}
				}
				names.sort(null);
			}
			representativeAttributes.put(reference.getName(), names);
		}
		int pageNumber = 1;
		int read = 0;
		int total;
		do {
			final EvitaResponse<SealedEntity> response = session.querySealedEntity(
				Query.query(
					collection(entityType),
					filterBy(scope(scope)),
					require(page(pageNumber++, BODY_PAGE_SIZE), entityFetch(referenceContentAllWithAttributes()))
				)
			);
			total = response.getTotalRecordCount();
			for (final SealedEntity body : response.getRecordData()) {
				read++;
				for (final ReferenceSchemaContract referenceSchema : references) {
					final List<String> names = representativeAttributes.get(referenceSchema.getName());
					final Map<PartitionKey, SortedSet<Integer>> partitions =
						ownersByPartition.get(referenceSchema.getName());
					for (final ReferenceContract reference : body.getReferences(referenceSchema.getName())) {
						final List<Serializable> values = new ArrayList<>(names.size());
						for (final String name : names) {
							values.add(
								reference.getAttributeValue(name)
									.filter(AttributeValue::exists)
									.map(AttributeValue::value)
									.orElseGet(() -> referenceSchema.getAttribute(name).orElseThrow().getDefaultValue())
							);
						}
						partitions
							.computeIfAbsent(
								new PartitionKey(reference.getReferencedPrimaryKey(), values), key -> new TreeSet<>()
							)
							.add(body.getPrimaryKeyOrThrowException());
					}
				}
			}
			if (response.getRecordData().isEmpty()) {
				break;
			}
		} while (read < total);
		assertEquals(total, read, "The bodies of `" + entityType + "` in " + scope + " were read incompletely");

		final Map<String, BodyOwners> bodyOwners = CollectionUtils.createHashMap(references.size());
		for (final Entry<String, Map<PartitionKey, SortedSet<Integer>>> reference : ownersByPartition.entrySet()) {
			final SortedMap<Integer, SortedSet<Integer>> ownersByTarget = new TreeMap<>();
			final SortedMap<Integer, List<Integer>> partitionSizes = new TreeMap<>();
			for (final Entry<PartitionKey, SortedSet<Integer>> partition : reference.getValue().entrySet()) {
				final int target = partition.getKey().target();
				ownersByTarget.computeIfAbsent(target, key -> new TreeSet<>()).addAll(partition.getValue());
				partitionSizes.computeIfAbsent(target, key -> new ArrayList<>(1)).add(partition.getValue().size());
			}
			for (final List<Integer> sizes : partitionSizes.values()) {
				sizes.sort(null);
			}
			bodyOwners.put(reference.getKey(), new BodyOwners(ownersByTarget, partitionSizes));
		}
		return bodyOwners;
	}

	/**
	 * Selects the targets whose owners are checked by a query: the {@link #TARGETS_PER_REFERENCE} lowest target primary
	 * keys among the eligible ones. When none of them has a single-owner partition, or none a shared one, the last of
	 * them is swapped for the lowest eligible target that has, if the reference has one - so that a registry row and a
	 * shared index object are both checked wherever the reference holds one.
	 *
	 * @param bodyOwners      the owners of the reference, as the bodies name them
	 * @param eligibleTargets the targets that may be selected, or `null` when every target may
	 * @return the selected targets
	 */
	@Nonnull
	private static List<Integer> selectTargets(
		@Nonnull BodyOwners bodyOwners,
		@Nullable Set<Integer> eligibleTargets
	) {
		final List<Integer> eligible = new ArrayList<>(bodyOwners.ownersByTarget().size());
		for (final int target : bodyOwners.ownersByTarget().keySet()) {
			if (eligibleTargets == null || eligibleTargets.contains(target)) {
				eligible.add(target);
			}
		}
		final List<Integer> targets = new ArrayList<>(
			eligible.subList(0, Math.min(eligible.size(), TARGETS_PER_REFERENCE))
		);
		boolean singleOwner = false;
		boolean shared = false;
		for (final int target : targets) {
			singleOwner |= bodyOwners.hasSingleOwnerPartition(target);
			shared |= bodyOwners.hasSharedPartition(target);
		}
		// a selection below the limit holds every eligible target, so only a full one can lack a kind the reference
		// has - and every target has a partition, so a non-empty selection lacks one kind at most
		if (!singleOwner || !shared) {
			for (final int target : eligible) {
				if (
					(!singleOwner && bodyOwners.hasSingleOwnerPartition(target)) ||
						(!shared && bodyOwners.hasSharedPartition(target))
				) {
					targets.set(targets.size() - 1, target);
					break;
				}
			}
		}
		return targets;
	}

	/**
	 * Returns which of the given primary keys belong to an entity of the type in any scope.
	 *
	 * @param session     the session to query
	 * @param entityType  the entity type
	 * @param primaryKeys the primary keys to look up
	 * @return the primary keys of the entities that exist
	 */
	@Nonnull
	private static Set<Integer> existingEntities(
		@Nonnull EvitaSessionContract session,
		@Nonnull String entityType,
		@Nonnull Set<Integer> primaryKeys
	) {
		if (primaryKeys.isEmpty() || !session.getAllEntityTypes().contains(entityType)) {
			return Set.of();
		}
		final int[] requested = new int[primaryKeys.size()];
		int index = 0;
		for (final int primaryKey : primaryKeys) {
			requested[index++] = primaryKey;
		}
		final EvitaResponse<EntityReferenceContract> response = session.queryEntityReference(
			Query.query(
				collection(entityType),
				filterBy(entityPrimaryKeyInSet(requested), scope(Scope.values())),
				require(page(1, requested.length))
			)
		);
		final Set<Integer> existing = CollectionUtils.createHashSet(response.getRecordData().size());
		for (final EntityReferenceContract entity : response.getRecordData()) {
			existing.add(entity.getPrimaryKey());
		}
		return existing;
	}

	/**
	 * Returns the owners of the reference in the scope that `referenceHaving(reference, entityPrimaryKeyInSet(target))`
	 * answers, in ascending order. The page is one longer than the owners expected, so an answer with more owners shows
	 * up as a different list rather than a truncated one.
	 *
	 * @param session        the session to query
	 * @param entityType     the collection
	 * @param referenceName  the reference
	 * @param scope          the scope
	 * @param target         the target primary key
	 * @param expectedOwners how many owners the bodies say reference the target
	 * @return the primary keys of the owners the query answered
	 */
	@Nonnull
	private static List<Integer> ownersByQuery(
		@Nonnull EvitaSessionContract session,
		@Nonnull String entityType,
		@Nonnull String referenceName,
		@Nonnull Scope scope,
		int target,
		int expectedOwners
	) {
		final EvitaResponse<EntityReferenceContract> response = session.queryEntityReference(
			Query.query(
				collection(entityType),
				filterBy(scope(scope), referenceHaving(referenceName, entityPrimaryKeyInSet(target))),
				require(page(1, expectedOwners + 1))
			)
		);
		final List<Integer> owners = new ArrayList<>(response.getRecordData().size());
		for (final EntityReferenceContract owner : response.getRecordData()) {
			owners.add(owner.getPrimaryKey());
		}
		owners.sort(null);
		return owners;
	}

	/**
	 * Describes a target whose owners the query answered wrong: its partitions as the bodies name them, its index
	 * objects, and - for a reference to a managed entity type - the scope the target entity is in.
	 *
	 * @param session         the session to query
	 * @param referenceSchema the reference
	 * @param target          the target primary key
	 * @param bodyOwners      the owners of the reference, as the bodies name them
	 * @param objects         the index objects of the reference in the scope
	 * @return the description
	 */
	@Nonnull
	private static String describeTarget(
		@Nonnull EvitaSessionContract session,
		@Nonnull ReferenceSchemaContract referenceSchema,
		int target,
		@Nonnull BodyOwners bodyOwners,
		@Nonnull SortedMap<Integer, List<Integer>> objects
	) {
		final String partitions = "partitions by their owner count " +
			bodyOwners.partitionSizesByTarget().get(target) + ", index objects by their owner count " +
			objects.getOrDefault(target, List.of());
		if (!referenceSchema.isReferencedEntityTypeManaged()) {
			return partitions;
		}
		final String targetScope = session.queryOneSealedEntity(
				Query.query(
					collection(referenceSchema.getReferencedEntityType()),
					filterBy(entityPrimaryKeyInSet(target), scope(Scope.values())),
					require(entityFetch())
				)
			)
			.map(entity -> "in " + entity.getScope())
			.orElse("missing");
		return partitions + ", `" + referenceSchema.getReferencedEntityType() + "` " + target + " is " + targetScope;
	}

	/**
	 * Describes how the index objects of a reference differ from the partitions with several owners the bodies name,
	 * target by target.
	 *
	 * @param expected the partitions with several owners, by their owner count by the target
	 * @param actual   the index objects, by their owner count by the target
	 * @return the description of up to ten differing targets
	 */
	@Nonnull
	private static String describeDifference(
		@Nonnull SortedMap<Integer, List<Integer>> expected,
		@Nonnull SortedMap<Integer, List<Integer>> actual
	) {
		final SortedSet<Integer> targets = new TreeSet<>(expected.keySet());
		targets.addAll(actual.keySet());
		final List<String> differences = new ArrayList<>(10);
		int differing = 0;
		for (final int target : targets) {
			final List<Integer> expectedSizes = expected.getOrDefault(target, List.of());
			final List<Integer> actualSizes = actual.getOrDefault(target, List.of());
			if (!expectedSizes.equals(actualSizes)) {
				differing++;
				if (differences.size() < 10) {
					differences.add(
						"target " + target + ": bodies " + expectedSizes + ", index objects " + actualSizes
					);
				}
			}
		}
		return differing + " target(s) differ by their owner counts: " + String.join("; ", differences);
	}

	/**
	 * Returns the number of single-owner partitions every reference type index of the collection holds as registry
	 * rows, as the index cardinality statistics report it.
	 *
	 * @param evita       the engine holding the catalog
	 * @param catalogName the catalog
	 * @param entityType  the collection
	 * @return the registry rows by `reference in SCOPE`, one entry per reference type index the collection holds
	 */
	@Nonnull
	private static Map<String, Integer> registryRowsOf(
		@Nonnull Evita evita,
		@Nonnull String catalogName,
		@Nonnull String entityType
	) {
		final CollectionIndexCardinality cardinality = evita.management()
			.getEntityCollectionStatistics(
				catalogName, entityType, EnumSet.of(CatalogStatisticsComponent.INDEX_CARDINALITY)
			)
			.indexCardinalityIfPresent()
			.orElseThrow();
		final Map<String, Integer> rows = CollectionUtils.createHashMap(cardinality.indexes().length);
		for (final IndexCardinality index : cardinality.indexes()) {
			if (index.indexType() == EntityIndexType.REFERENCED_ENTITY_TYPE) {
				rows.put(
					index.discriminator() + " in " + index.scope(), index.virtualPartitionCountIfKnown().orElseThrow()
				);
			}
		}
		return rows;
	}

	/**
	 * Returns the index objects of the partitions of the reference in the scope, as an index browse lists them.
	 *
	 * @param evita         the engine holding the catalog
	 * @param catalogName   the catalog
	 * @param entityType    the collection
	 * @param referenceName the reference
	 * @param scope         the scope
	 * @return the number of owners each index object covers, in ascending order, by its target primary key
	 */
	@Nonnull
	private static SortedMap<Integer, List<Integer>> partitionObjectsOf(
		@Nonnull Evita evita,
		@Nonnull String catalogName,
		@Nonnull String entityType,
		@Nonnull String referenceName,
		@Nonnull Scope scope
	) {
		final SortedMap<Integer, List<Integer>> objects = new TreeMap<>();
		int pageNumber = 1;
		int read = 0;
		int total;
		do {
			final IndexBrowseResult result = evita.management().browseIndexes(
				catalogName,
				entityType,
				new IndexBrowseCriteria(
					pageNumber++, IndexBrowseCriteria.MAX_PAGE_SIZE, IndexBrowseOrdering.MAP_ORDER, OrderDirection.ASC,
					EnumSet.of(EntityIndexType.REFERENCED_ENTITY), EnumSet.of(scope), Set.of(referenceName)
				)
			);
			total = result.totalRecordCount();
			for (final BrowsedIndex index : result.indexes()) {
				read++;
				objects
					.computeIfAbsent(Objects.requireNonNull(index.discriminatorPrimaryKey()), key -> new ArrayList<>(1))
					.add(index.entityCountIfKnown().orElseThrow());
			}
			if (result.indexes().length == 0) {
				break;
			}
		} while (read < total);
		assertEquals(
			total, read,
			"The index objects of `" + entityType + "." + referenceName + "` in " + scope + " were read incompletely"
		);
		for (final List<Integer> sizes : objects.values()) {
			sizes.sort(null);
		}
		return objects;
	}

	/**
	 * Asserts with the engine stopped that the catalog on disk is of the current storage protocol - its last
	 * bootstrap record and the catalog header that record publishes. The header is read through the persistence
	 * service the engine loads the catalog with, whose open would migrate a header of an older protocol and publish
	 * the result; that the open published nothing proves the header read is the one the engine left behind.
	 *
	 * @param configuration the configuration of the engine
	 * @param catalogName   the catalog
	 * @param version       the dataset version, used in failure messages
	 */
	private static void assertCurrentStorageProtocolOnDisk(
		@Nonnull EvitaConfiguration configuration,
		@Nonnull String catalogName,
		@Nonnull String version
	) {
		final CatalogBootstrap published = OfflineCatalogImages.lastBootstrap(configuration, catalogName);
		assertEquals(
			PersistenceService.STORAGE_PROTOCOL_VERSION, published.storageProtocolVersion(),
			version + ": the last bootstrap record must be of the current storage protocol"
		);
		final int headerProtocol = OfflineCatalogImages.withOfflineCatalog(
			configuration, catalogName,
			service -> service.getCatalogHeader(service.getLastCatalogVersion()).storageProtocolVersion()
		);
		assertEquals(
			published, OfflineCatalogImages.lastBootstrap(configuration, catalogName),
			version + ": the offline open must publish nothing - it ran a migration"
		);
		assertEquals(
			PersistenceService.STORAGE_PROTOCOL_VERSION, headerProtocol,
			version + ": the catalog header on disk must be of the current storage protocol"
		);
	}

	/**
	 * Polls until at least one catalog reaches {@link CatalogState#ALIVE} or any catalog becomes
	 * {@link CatalogState#CORRUPTED}. Required because the boot-time auto-upgrade flow runs
	 * asynchronously after the engine constructor returns: the first-attempt load future for an
	 * old-format catalog completes exceptionally with `CatalogRequiresUpgradeException`, the
	 * engine schedules an `UpgradeCatalogFormatMutation` and a retry on the service executor,
	 * and only after that retry installs a real `Catalog` does the catalog state move to ALIVE.
	 *
	 * Polling (rather than a system-CDC subscription) is required because the retry's
	 * `replaceCatalogReference` is a pure in-memory engine-state update — no system CDC event
	 * marks the final BEING_ACTIVATED → ALIVE transition, so a CDC subscriber would block on
	 * the upgrade-mutation event and never wake up for the install that follows.
	 *
	 * @param evita   the engine instance whose catalog states are polled
	 * @param version dataset version label, used in failure messages
	 */
	private static void waitForAtLeastOneAliveCatalog(@Nonnull Evita evita, @Nonnull String version) {
		final long deadline = System.nanoTime() + Duration.ofMinutes(10).toNanos();
		while (true) {
			if (evita.management().getSystemStatus().catalogsCorrupted() > 0) {
				fail("Catalog became CORRUPTED during boot-time auto-upgrade for dataset " + version);
			}
			final boolean anyAlive = evita.getCatalogNames()
				.stream()
				.anyMatch(it -> evita.getCatalogState(it)
					.map(state -> state == CatalogState.ALIVE)
					.orElse(false));
			if (anyAlive) {
				return;
			}
			if (System.nanoTime() > deadline) {
				fail("No catalog reached ALIVE state within 10 minutes for dataset " + version);
			}
			try {
				Thread.sleep(500);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				throw new IllegalStateException(
					"Interrupted while waiting for catalog to come alive", e
				);
			}
		}
	}

	/**
	 * Reads the WAL (Write-Ahead Log) contents of the specified catalog and validates
	 * the consistency between the last transaction version in the WAL and the current catalog version.
	 *
	 * @param evita The instance of {@link Evita} used to access the catalog and its mutation logs.
	 * @param catalogName The name of the catalog whose WAL contents are to be read and validated.
	 */
	private static void readWalContents(@Nonnull Evita evita, @Nonnull String catalogName) {
		final CatalogContract catalog = evita.getCatalogInstance(catalogName)
			.orElseThrow(() -> new IllegalStateException("Catalog '" + catalogName + "' not found!"));
		final long currentCatalogVersion = catalog.getVersion();
		log.info("Current catalog version: {}", currentCatalogVersion);

		long walRecordCount = 0;
		long lastTransactionVersion = -1;
		try (final Stream<CatalogBoundMutation> mutationStream = catalog.getCommittedMutationStream(1)) {
			final Iterator<CatalogBoundMutation> iterator = mutationStream.iterator();
			while (iterator.hasNext()) {
				final CatalogBoundMutation mutation = iterator.next();
				walRecordCount++;
				if (mutation instanceof TransactionMutation transactionMutation) {
					lastTransactionVersion = transactionMutation.getVersion();
					// skip over the individual mutations within this transaction
					for (int i = 0; i < transactionMutation.getMutationCount(); i++) {
						iterator.next();
						walRecordCount++;
					}
				}
			}
		}

		log.info(
			"WAL read complete: {} total records, last transaction version: {}",
			walRecordCount, lastTransactionVersion
		);
		if (walRecordCount > 0) {
			assertEquals(
				currentCatalogVersion, lastTransactionVersion,
				"Last WAL transaction version must equal current catalog version!"
			);
		} else {
			log.info("No WAL records found (dataset predates WAL storage) — skipping version assertion");
		}
	}

	/**
	 * What {@link #checkFilteringOnlyPartitions} found in one catalog - compared between the engine that converted the
	 * catalog and the engine restarted over it, and reported.
	 *
	 * @param filteringOnlyReferences every filtering-only reference of the catalog, as `entityType.reference in SCOPE`
	 * @param registryRows            the registry rows of every filtering-only reference, by the reference
	 * @param partitionObjects        the shared index objects of every filtering-only reference, by the reference
	 * @param owners                  the owners the query answered for every checked target, by the reference and the
	 *                                target
	 * @param checkedRowTargets       how many checked targets are referenced by a single owner - kept as a registry
	 *                                row
	 * @param checkedObjectTargets    how many checked targets are referenced by several owners - kept as a shared
	 *                                index object
	 * @param diminishedAnswers       how many checked targets the query answered with fewer owners than the bodies
	 *                                name - always `0` on a strictly checked dataset, which fails on any such answer
	 */
	private record PartitionCheck(
		@Nonnull List<String> filteringOnlyReferences,
		@Nonnull SortedMap<String, Integer> registryRows,
		@Nonnull SortedMap<String, Integer> partitionObjects,
		@Nonnull SortedMap<String, List<Integer>> owners,
		int checkedRowTargets,
		int checkedObjectTargets,
		int diminishedAnswers
	) {

		/**
		 * Returns the registry rows of all filtering-only references together - the single-owner partitions the
		 * catalog keeps as rows.
		 *
		 * @return the sum of the registry rows
		 */
		int registryRowCount() {
			int sum = 0;
			for (final int rows : this.registryRows.values()) {
				sum += rows;
			}
			return sum;
		}

		/**
		 * Returns the shared index objects of all filtering-only references together.
		 *
		 * @return the sum of the index objects
		 */
		int partitionObjectCount() {
			int sum = 0;
			for (final int objects : this.partitionObjects.values()) {
				sum += objects;
			}
			return sum;
		}

		@Nonnull
		@Override
		public String toString() {
			return "PartitionCheck{filteringOnlyReferences=" + this.filteringOnlyReferences +
				", registryRows=" + this.registryRows +
				", partitionObjects=" + this.partitionObjects +
				", checkedTargets=" + this.owners.size() +
				", checkedRowTargets=" + this.checkedRowTargets +
				", checkedObjectTargets=" + this.checkedObjectTargets +
				", diminishedAnswers=" + this.diminishedAnswers + '}';
		}
	}

	/**
	 * The owners of one reference in one scope, as the entity bodies name them.
	 *
	 * @param ownersByTarget         the owners of every referenced target, both in ascending order
	 * @param partitionSizesByTarget the owner count of every partition of a target, in ascending order, by the target -
	 *                               a target has several partitions only under a reference allowing duplicates, which
	 *                               partitions its targets by the values of its representative attributes
	 */
	private record BodyOwners(
		@Nonnull SortedMap<Integer, SortedSet<Integer>> ownersByTarget,
		@Nonnull SortedMap<Integer, List<Integer>> partitionSizesByTarget
	) {

		/**
		 * Returns TRUE when the target has a partition with a single owner - one kept as a registry row.
		 *
		 * @param target the target primary key
		 * @return TRUE for a target with a single-owner partition
		 */
		boolean hasSingleOwnerPartition(int target) {
			return this.partitionSizesByTarget.get(target).getFirst() == 1;
		}

		/**
		 * Returns TRUE when the target has a partition with several owners - one kept as a shared index object.
		 *
		 * @param target the target primary key
		 * @return TRUE for a target with a shared partition
		 */
		boolean hasSharedPartition(int target) {
			return this.partitionSizesByTarget.get(target).getLast() > 1;
		}

		/**
		 * Returns the number of partitions with a single owner - the registry rows the reference must hold.
		 *
		 * @return the number of single-owner partitions
		 */
		int singleOwnerPartitionCount() {
			int count = 0;
			for (final List<Integer> sizes : this.partitionSizesByTarget.values()) {
				for (final int size : sizes) {
					if (size == 1) {
						count++;
					}
				}
			}
			return count;
		}

		/**
		 * Returns the partitions with several owners - the index objects the reference must hold.
		 *
		 * @return the owner count of every partition with several owners, in ascending order, by its target
		 */
		@Nonnull
		SortedMap<Integer, List<Integer>> sharedPartitions() {
			final SortedMap<Integer, List<Integer>> shared = new TreeMap<>();
			for (final Entry<Integer, List<Integer>> target : this.partitionSizesByTarget.entrySet()) {
				for (final int size : target.getValue()) {
					if (size > 1) {
						shared.computeIfAbsent(target.getKey(), key -> new ArrayList<>(1)).add(size);
					}
				}
			}
			return shared;
		}
	}

	/**
	 * The key of a partition derived from a body: the target and, for a reference allowing duplicates, the values of
	 * its representative attributes in the order of their names.
	 *
	 * @param target               the target primary key
	 * @param representativeValues the values of the representative attributes, empty for a reference without
	 *                             duplicates
	 */
	private record PartitionKey(int target, @Nonnull List<Serializable> representativeValues) {
	}

}
