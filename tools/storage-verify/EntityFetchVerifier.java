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

package io.evitadb.tools.storage;

import io.evitadb.api.CatalogState;
import io.evitadb.api.EvitaSessionContract;
import io.evitadb.api.configuration.CacheOptions;
import io.evitadb.api.configuration.EvitaConfiguration;
import io.evitadb.api.configuration.ServerOptions;
import io.evitadb.api.configuration.StorageOptions;
import io.evitadb.api.exception.EntityHasNoPricesException;
import io.evitadb.api.query.Query;
import io.evitadb.api.query.require.EntityContentRequire;
import io.evitadb.api.query.require.EntityFetch;
import io.evitadb.api.requestResponse.EvitaResponse;
import io.evitadb.api.requestResponse.data.AssociatedDataContract.AssociatedDataValue;
import io.evitadb.api.requestResponse.data.AttributesContract.AttributeValue;
import io.evitadb.api.requestResponse.data.PriceContract;
import io.evitadb.api.requestResponse.data.ReferenceContract;
import io.evitadb.api.requestResponse.data.SealedEntity;
import io.evitadb.api.requestResponse.data.structure.EntityReference;
import io.evitadb.api.requestResponse.schema.SealedEntitySchema;
import io.evitadb.core.Evita;
import io.evitadb.dataType.Scope;
import io.evitadb.export.file.configuration.FileSystemExportOptions;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.TreeSet;
import java.util.stream.Collectors;

import static io.evitadb.api.query.QueryConstraints.*;

/**
 * Opens an evitaDB storage directory with the real engine and fetches every entity of every collection, in every
 * scope, with its full content.
 *
 * Loading the catalogs deserializes the catalog headers, the schemas and every entity index, and replays the
 * write-ahead log behind the last published state; fetching the entities deserializes every entity body, attribute,
 * associated data, price and reference storage part. A failure in any of them is reported with the entity it hit.
 *
 * For every collection and scope the tool prints the number of entities fetched and a SHA-256 digest of a canonical
 * text of their content, with every collection in it sorted. The lines carry no timing, so the outputs of two builds
 * run against copies of the same storage can be compared with a plain `diff`.
 *
 * **Run it on a copy of the storage directory.** The engine starts in read-only mode, but opening a catalog may still
 * truncate a torn write-ahead log tail, and storage written by an older version is upgraded to the current storage
 * protocol on open.
 *
 * Usage: `EntityFetchVerifier <storage dir copy> <work dir> <export dir>`. The process exits with `0` when every
 * catalog loaded and every entity was fetched, `1` otherwise.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
public class EntityFetchVerifier {
	/**
	 * Entities fetched per query.
	 */
	private static final int PAGE_SIZE = 500;
	/**
	 * How long a catalog may stay in a transitional state after the engine started.
	 */
	private static final long ACTIVATION_TIMEOUT_MILLIS = 1_800_000L;

	/**
	 * Entry point - see the class documentation for the arguments.
	 *
	 * @param args storage directory copy, work directory, export directory
	 */
	public static void main(@Nonnull String[] args) {
		int exit;
		if (args.length != 3) {
			System.err.println("usage: EntityFetchVerifier <storage dir copy> <work dir> <export dir>");
			exit = 2;
		} else {
			try {
				exit = verify(Path.of(args[0]), Path.of(args[1]), Path.of(args[2])) ? 0 : 1;
			} catch (Throwable ex) {
				System.out.println("# SUMMARY entities: status=FAILED failure=" + describe(ex));
				ex.printStackTrace(System.out);
				exit = 1;
			}
		}
		System.out.flush();
		// the engine's scheduler threads are not daemons and would keep a finished verification alive
		Runtime.getRuntime().halt(exit);
	}

	/**
	 * Loads the storage and fetches everything in it.
	 *
	 * @return true when every catalog loaded and every entity was fetched
	 */
	private static boolean verify(@Nonnull Path storage, @Nonnull Path work, @Nonnull Path export) throws Exception {
		final long started = System.currentTimeMillis();
		final Evita evita = new Evita(
			EvitaConfiguration.builder()
				.server(ServerOptions.builder().readOnly(true).build())
				.cache(CacheOptions.builder().enabled(false).build())
				.storage(
					StorageOptions.builder()
						.storageDirectory(storage)
						.workDirectory(work)
						// reading compressed records needs a decompressor, whatever the storage was written with
						.compress(true)
						.build()
				)
				.export(FileSystemExportOptions.builder().directory(export).build())
				.build()
		);
		long entities = 0, entityFailures = 0, catalogFailures = 0;
		try {
			final TreeSet<String> catalogNames = new TreeSet<>(evita.getCatalogNames());
			System.out.println(
				"# engine started in " + (System.currentTimeMillis() - started) + " ms, catalogs " + catalogNames
			);
			for (String catalogName : catalogNames) {
				final CatalogState state = awaitSettledState(evita, catalogName);
				System.out.println("CATALOG|" + catalogName + "|state=" + state);
				if (state != CatalogState.ALIVE && state != CatalogState.WARMING_UP) {
					catalogFailures++;
					continue;
				}
				try {
					final long[] counts = evita.queryCatalog(catalogName, session -> {
						final long[] sums = new long[2];
						for (String entityType : new TreeSet<>(session.getAllEntityTypes())) {
							for (Scope scope : Scope.values()) {
								final long[] collection = verifyCollection(catalogName, entityType, scope, session);
								sums[0] += collection[0];
								sums[1] += collection[1];
							}
						}
						return sums;
					});
					entities += counts[0];
					entityFailures += counts[1];
				} catch (Throwable ex) {
					catalogFailures++;
					System.out.println("CATALOG|" + catalogName + "|status=FAILED|failure=" + describe(ex));
				}
			}
		} finally {
			evita.close();
		}
		final boolean ok = entityFailures == 0 && catalogFailures == 0;
		System.out.printf(
			"# SUMMARY entities: entities=%d entityFailures=%d catalogFailures=%d status=%s (%d s)%n",
			entities, entityFailures, catalogFailures, ok ? "OK" : "FAILED",
			(System.currentTimeMillis() - started) / 1000
		);
		return ok;
	}

	/**
	 * Waits until a catalog leaves the transitional states it passes through after the engine starts.
	 */
	@Nullable
	private static CatalogState awaitSettledState(@Nonnull Evita evita, @Nonnull String catalogName)
		throws InterruptedException {
		final long waitStart = System.currentTimeMillis();
		CatalogState state = evita.getCatalogState(catalogName).orElse(null);
		while (
			state != null && state.isTransitional() &&
				System.currentTimeMillis() - waitStart < ACTIVATION_TIMEOUT_MILLIS
		) {
			Thread.sleep(200);
			state = evita.getCatalogState(catalogName).orElse(null);
		}
		return state;
	}

	/**
	 * Fetches every entity of one collection in one scope, page by page, and prints the collection's line.
	 *
	 * @return the number of entities fetched and the number that failed
	 */
	@Nonnull
	private static long[] verifyCollection(
		@Nonnull String catalogName,
		@Nonnull String entityType,
		@Nonnull Scope scope,
		@Nonnull EvitaSessionContract session
	) {
		final MessageDigest digest = sha256();
		final List<String> failureSamples = new ArrayList<>(5);
		final EntityFetch fetchEverything = fetchEverything(session, entityType, scope);
		long fetched = 0, failures = 0, expected = 0;
		for (int page = 1; ; page++) {
			// the primary keys of the page come from the indexes alone - no entity storage part is touched yet
			final EvitaResponse<EntityReference> keys = session.query(
				Query.query(collection(entityType), filterBy(scope(scope)), require(page(page, PAGE_SIZE))),
				EntityReference.class
			);
			expected = keys.getTotalRecordCount();
			final List<EntityReference> references = keys.getRecordData();
			if (references.isEmpty()) {
				break;
			}
			final int[] primaryKeys = new int[references.size()];
			for (int i = 0; i < primaryKeys.length; i++) {
				primaryKeys[i] = references.get(i).getPrimaryKey();
			}
			try {
				final List<SealedEntity> page1 = session.query(
					Query.query(
						collection(entityType),
						filterBy(entityPrimaryKeyInSet(primaryKeys), scope(scope)),
						require(page(1, PAGE_SIZE), fetchEverything)
					),
					SealedEntity.class
				).getRecordData();
				if (page1.size() != primaryKeys.length) {
					failures += primaryKeys.length - page1.size();
					addSample(
						failureSamples, "page " + page + " returned " + page1.size() + " of " + primaryKeys.length
					);
				}
				for (SealedEntity entity : page1) {
					digest.update(canonical(entity).getBytes(StandardCharsets.UTF_8));
					fetched++;
				}
			} catch (Throwable pageFailure) {
				// the page failed as a whole - fetch its entities one by one to name the ones that cannot be read
				for (int primaryKey : primaryKeys) {
					try {
						for (SealedEntity entity : session.query(
							Query.query(
								collection(entityType),
								filterBy(entityPrimaryKeyInSet(primaryKey), scope(scope)),
								require(fetchEverything)
							),
							SealedEntity.class
						).getRecordData()) {
							digest.update(canonical(entity).getBytes(StandardCharsets.UTF_8));
							fetched++;
						}
					} catch (Throwable ex) {
						failures++;
						addSample(failureSamples, "pk " + primaryKey + ": " + describe(ex));
					}
				}
			}
			if (references.size() < PAGE_SIZE) {
				break;
			}
		}
		if (expected > 0 || fetched > 0 || failures > 0) {
			System.out.printf(
				"COLLECTION|%s|%s|%s|expected=%d|fetched=%d|failures=%d|digest=%s|samples=%s%n",
				catalogName, entityType, scope, expected, fetched, failures,
				HexFormat.of().formatHex(digest.digest()).substring(0, 16), failureSamples
			);
		}
		return new long[]{fetched, Math.max(failures, expected - fetched)};
	}

	/**
	 * Builds the requirement fetching every part of an entity the collection can have. Prices are asked for only
	 * where the engine accepts it, which it does not for a collection whose schema rules prices out.
	 */
	@Nonnull
	private static EntityFetch fetchEverything(
		@Nonnull EvitaSessionContract session,
		@Nonnull String entityType,
		@Nonnull Scope scope
	) {
		final SealedEntitySchema schema = session.getEntitySchemaOrThrowException(entityType);
		final List<EntityContentRequire> content = new ArrayList<>(6);
		content.add(attributeContentAll());
		content.add(associatedDataContentAll());
		content.add(referenceContentAllWithAttributes());
		content.add(dataInLocalesAll());
		try {
			session.query(
				Query.query(
					collection(entityType),
					filterBy(scope(scope)),
					require(page(1, 1), entityFetch(priceContentAll()))
				),
				SealedEntity.class
			);
			content.add(priceContentAll());
		} catch (EntityHasNoPricesException ex) {
			// the collection keeps no prices - there is nothing to fetch
		}
		if (schema.isWithHierarchy()) {
			content.add(hierarchyContent());
		}
		return entityFetch(content.toArray(EntityContentRequire[]::new));
	}

	/**
	 * Canonical text of everything a full fetch materializes, with every collection sorted so that the text does not
	 * depend on iteration order.
	 */
	@Nonnull
	private static String canonical(@Nonnull SealedEntity entity) {
		final StringBuilder sb = new StringBuilder(4096);
		sb.append(entity.getType()).append('#').append(entity.getPrimaryKey())
			.append(" v").append(entity.version()).append(' ').append(entity.getScope())
			.append(" parent=")
			.append(
				entity.getSchema().isWithHierarchy() ?
					entity.getParentEntity().map(Object::toString).orElse("-") : "n/a"
			)
			.append(" locales=").append(new TreeSet<>(entity.getLocales().stream().map(Object::toString).toList()))
			.append('\n');
		sb.append(" A ").append(
			entity.getAttributeValues().stream().map(EntityFetchVerifier::attribute).sorted()
				.collect(Collectors.joining("; "))
		).append('\n');
		sb.append(" D ").append(
			entity.getAssociatedDataValues().stream().map(EntityFetchVerifier::associatedData).sorted()
				.collect(Collectors.joining("; "))
		).append('\n');
		if (entity.pricesAvailable()) {
			sb.append(" P ").append(entity.getPriceInnerRecordHandling()).append(' ').append(
				entity.getPrices().stream().map(PriceContract::toString).sorted().collect(Collectors.joining("; "))
			).append('\n');
		}
		sb.append(" R ").append(
			entity.getReferences().stream().map(EntityFetchVerifier::reference).sorted()
				.collect(Collectors.joining("; "))
		).append('\n');
		return sb.toString();
	}

	@Nonnull
	private static String attribute(@Nonnull AttributeValue value) {
		return value.key() + "=" + valueText(value.value()) + "@v" + value.version();
	}

	@Nonnull
	private static String associatedData(@Nonnull AssociatedDataValue value) {
		return value.key() + "=" + valueText(value.value()) + "@v" + value.version();
	}

	@Nonnull
	private static String reference(@Nonnull ReferenceContract reference) {
		return reference.getReferenceKey() + " v" + reference.version() +
			" group=" + reference.getGroup().map(Object::toString).orElse("-") + " attributes={" +
			reference.getAttributeValues().stream().map(EntityFetchVerifier::attribute).sorted()
				.collect(Collectors.joining(", ")) + "}";
	}

	/**
	 * Text of a stored value, with arrays spelled out element by element.
	 */
	@Nonnull
	private static String valueText(@Nullable Object value) {
		if (value == null) {
			return "null";
		} else if (value instanceof Object[] objects) {
			return Arrays.deepToString(objects);
		} else if (value instanceof int[] ints) {
			return Arrays.toString(ints);
		} else if (value instanceof long[] longs) {
			return Arrays.toString(longs);
		} else if (value instanceof byte[] bytes) {
			return Arrays.toString(bytes);
		} else if (value instanceof short[] shorts) {
			return Arrays.toString(shorts);
		} else if (value instanceof char[] chars) {
			return Arrays.toString(chars);
		} else if (value instanceof boolean[] booleans) {
			return Arrays.toString(booleans);
		} else {
			return value.toString();
		}
	}

	private static void addSample(@Nonnull List<String> samples, @Nonnull String sample) {
		if (samples.size() < 5) {
			samples.add(sample);
		}
	}

	@Nonnull
	private static MessageDigest sha256() {
		try {
			return MessageDigest.getInstance("SHA-256");
		} catch (java.security.NoSuchAlgorithmException ex) {
			throw new IllegalStateException("SHA-256 is mandatory in every JDK", ex);
		}
	}

	/**
	 * Formats an exception with its causes on one line.
	 */
	@Nonnull
	private static String describe(@Nonnull Throwable ex) {
		final StringBuilder sb = new StringBuilder(256)
			.append(ex.getClass().getSimpleName()).append(": ").append(ex.getMessage());
		Throwable cause = ex.getCause();
		for (int depth = 0; cause != null && depth < 5; depth++) {
			sb.append(" <- ").append(cause.getClass().getSimpleName()).append(": ").append(cause.getMessage());
			cause = cause.getCause();
		}
		return sb.toString().replace('\n', ' ');
	}
}
