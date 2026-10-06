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

import io.evitadb.api.CatalogState;
import io.evitadb.api.EvitaSessionContract;
import io.evitadb.api.exception.UniqueValueViolationException;
import io.evitadb.api.query.FilterConstraint;
import io.evitadb.api.requestResponse.data.EntityReferenceContract;
import io.evitadb.core.Evita;
import io.evitadb.test.EvitaTestSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.function.Consumer;
import java.util.stream.Stream;

import static io.evitadb.api.query.Query.query;
import static io.evitadb.api.query.QueryConstraints.and;
import static io.evitadb.api.query.QueryConstraints.attributeEquals;
import static io.evitadb.api.query.QueryConstraints.collection;
import static io.evitadb.api.query.QueryConstraints.entityLocaleEquals;
import static io.evitadb.api.query.QueryConstraints.filterBy;
import static io.evitadb.test.TestTags.ATTRIBUTE;
import static io.evitadb.test.TestTags.INDEXING;
import static io.evitadb.test.TestTags.STORAGE;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Opens catalogs written by the released 2026.2 engine (storage protocol 6) and pins what `Migration_2026_3` does to
 * their standalone unique indexes: values stored raw - a precomposed (NFC) text, a decimal with more digits than the
 * indexed decimal places - are re-keyed into the filter index's key space and found by every equivalent spelling,
 * in the inline and the paged shape, in the catalog file and in a collection file; and a catalog in which two owners'
 * values become one value is refused, untouched, with every pair named.
 *
 * ## The fixtures
 *
 * Both were written by the v2026.2.18 engine under `testData/unique_normalization_fixtures`. The catalog declares
 * `code` (`String`, `uniqueGlobally`) and `price` (`BigDecimal`, `uniqueGlobally`, two indexed decimal places);
 * `product` adds `url` (`String`, localized, `unique`).
 *
 * `unique_raw_keys`:
 *
 * | entity | `code` | `price` | `url` |
 * |---|---|---|---|
 * | `product:1` | NFC `čaj` | `1.235` | NFC `/čaj` (en) |
 * | `product:2` | `tea` | `2.5` | `/tea` (en) |
 * | `product:3..302` | NFC `ž-i` | - | NFC `/řeka-i` (de) |
 * | `category:1` | NFC `kategorie-ř` | - | - |
 *
 * The 300 extra values make the catalog's `code` tree and the collection's `url` tree paged.
 *
 * `unique_collisions`: `product:1` holds NFC `čaj` / `1.235` / NFC `/čaj` (en), `product:2` holds NFD `/čaj` (en),
 * `category:1` holds NFD `čaj` / `1.236`. The release kept each pair apart; this version compares them as one value.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@DisplayName("Release-written standalone unique indexes on upgrade")
@Tag(STORAGE)
@Tag(INDEXING)
@Tag(ATTRIBUTE)
class UniqueIndexReleaseUpgradeTest implements EvitaTestSupport {
	private static final String FIXTURES = "testData/unique_normalization_fixtures";
	private static final String RAW_KEYS = "unique_raw_keys";
	private static final String COLLISIONS = "unique_collisions";
	private static final String PRODUCT = "product";
	private static final String CATEGORY = "category";

	private final List<TestPaths> allocatedPaths = new ArrayList<>(2);

	/**
	 * Returns the NFC spelling of `text`.
	 */
	@Nonnull
	private static String nfc(@Nonnull String text) {
		return Normalizer.normalize(text, Normalizer.Form.NFC);
	}

	/**
	 * Returns the NFD spelling of `text`.
	 */
	@Nonnull
	private static String nfd(@Nonnull String text) {
		return Normalizer.normalize(text, Normalizer.Form.NFD);
	}

	@AfterEach
	void tearDown() {
		this.allocatedPaths.forEach(this::cleanupTestPaths);
	}

	@Test
	@DisplayName("Should re-key release-written unique values and find them by every equivalent spelling")
	void shouldRekeyReleaseWrittenUniqueValues() {
		final TestPaths paths = copy(RAW_KEYS);
		try (Evita evita = boot(paths)) {
			assertEquals(CatalogState.ALIVE, evita.getCatalogState(RAW_KEYS).orElseThrow());
			assertEverySpellingFinds(evita);
			assertEquivalentSpellingsRefused(evita);
		}
		// the re-keyed catalog loads again as it is, and still answers the same way
		try (Evita evita = boot(paths)) {
			assertEquals(CatalogState.ALIVE, evita.getCatalogState(RAW_KEYS).orElseThrow());
			assertEverySpellingFinds(evita);
		}
	}

	@Test
	@DisplayName("Should refuse the upgrade, unchanged, when two owners hold one value, naming every pair")
	void shouldRefuseUpgradeOverCollidingOwners() {
		final TestPaths paths = copy(COLLISIONS);
		final Map<String, String> before = catalogFileDigests(paths, COLLISIONS);
		try (Evita evita = new Evita(newTestEvitaConfigurationBuilder(paths).build())) {
			final Exception refusal = assertThrows(Exception.class, evita::waitUntilFullyInitialized);
			assertEquals(CatalogState.CORRUPTED, evita.getCatalogState(COLLISIONS).orElseThrow());
			final String message = causeChainMessages(refusal);
			assertAll(
				() -> assertTrue(message.contains("cannot be upgraded to storage protocol version 7"), message),
				() -> assertTrue(message.contains("3 unique value(s)"), message),
				() -> assertTrue(message.contains("catalog attribute `code`"), message),
				() -> assertTrue(message.contains("catalog attribute `price`"), message),
				() -> assertTrue(message.contains("attribute `url` of entity `product`"), message),
				() -> assertTrue(message.contains("`product` 1"), message),
				() -> assertTrue(message.contains("`category` 1"), message),
				() -> assertTrue(message.contains("`product` 2"), message)
			);
		}
		assertEquals(before, catalogFileDigests(paths, COLLISIONS), "a refused upgrade must write nothing");
	}

	/**
	 * Starts the engine over `paths` and waits until every catalog has finished loading.
	 */
	@Nonnull
	private Evita boot(@Nonnull TestPaths paths) {
		final Evita evita = new Evita(newTestEvitaConfigurationBuilder(paths).build());
		evita.waitUntilFullyInitialized();
		return evita;
	}

	/**
	 * Asserts that every value of `unique_raw_keys` is found by its NFC and its NFD spelling (a decimal by any value
	 * equal at two decimal places), within its collection and catalog-wide.
	 */
	private static void assertEverySpellingFinds(@Nonnull Evita evita) {
		final List<Executable> lookups = new ArrayList<>(16);
		for (String spelling : new String[]{nfc("čaj"), nfd("čaj")}) {
			lookups.add(() -> assertEquals(List.of("product:1"), find(evita, null, null, attributeEquals("code", spelling))));
		}
		for (BigDecimal price : new BigDecimal[]{new BigDecimal("1.235"), new BigDecimal("1.24"), new BigDecimal("1.2449")}) {
			lookups.add(() -> assertEquals(List.of("product:1"), find(evita, null, null, attributeEquals("price", price))));
		}
		lookups.add(() -> assertEquals(
			List.of("product:2"), find(evita, null, null, attributeEquals("price", new BigDecimal("2.50"))))
		);
		lookups.add(() -> assertEquals(
			List.of("product:1"), find(evita, PRODUCT, Locale.ENGLISH, attributeEquals("url", nfd("/čaj"))))
		);
		lookups.add(() -> assertEquals(
			List.of("category:1"), find(evita, CATEGORY, null, attributeEquals("code", nfd("kategorie-ř"))))
		);
		for (int i : new int[]{3, 150, 302}) {
			lookups.add(() -> assertEquals(
				List.of("product:" + i), find(evita, null, null, attributeEquals("code", nfd("ž-" + i))), "code " + i
			));
			lookups.add(() -> assertEquals(
				List.of("product:" + i),
				find(evita, PRODUCT, Locale.GERMAN, attributeEquals("url", nfd("/řeka-" + i))), "url " + i
			));
		}
		assertAll(lookups);
	}

	/**
	 * Asserts that an equivalent spelling of a value of `unique_raw_keys` is refused for another owner, in the inline
	 * and the paged shape of both standalone indexes.
	 */
	private static void assertEquivalentSpellingsRefused(@Nonnull Evita evita) {
		assertAll(
			() -> assertRefused(evita, session -> session.createNewEntity(CATEGORY, 2)
				.setAttribute("code", nfd("čaj")).upsertVia(session)),
			() -> assertRefused(evita, session -> session.createNewEntity(CATEGORY, 2)
				.setAttribute("price", new BigDecimal("1.24")).upsertVia(session)),
			() -> assertRefused(evita, session -> session.createNewEntity(CATEGORY, 2)
				.setAttribute("code", nfd("ž-200")).upsertVia(session)),
			() -> assertRefused(evita, session -> session.createNewEntity(PRODUCT, 1000)
				.setAttribute("url", Locale.ENGLISH, nfd("/řeka-5")).upsertVia(session))
		);
	}

	/**
	 * Asserts that `write` is refused with a {@link UniqueValueViolationException}.
	 */
	private static void assertRefused(@Nonnull Evita evita, @Nonnull Consumer<EvitaSessionContract> write) {
		assertThrows(UniqueValueViolationException.class, () -> evita.updateCatalog(RAW_KEYS, write::accept));
	}

	/**
	 * Runs `filter` (in `locale` when given) within `collection`, or across the catalog when `collection` is `null`.
	 */
	@Nonnull
	private static List<String> find(
		@Nonnull Evita evita,
		@Nullable String collection,
		@Nullable Locale locale,
		@Nonnull FilterConstraint filter
	) {
		final FilterConstraint constraint = locale == null ? filter : and(filter, entityLocaleEquals(locale));
		return evita.queryCatalog(
			RAW_KEYS,
			session -> {
				return session.queryListOfEntityReferences(
						collection == null
							? query(filterBy(constraint))
							: query(collection(collection), filterBy(constraint))
					)
					.stream()
					.map(UniqueIndexReleaseUpgradeTest::describe)
					.sorted()
					.toList();
			}
		);
	}

	/**
	 * Renders a reference as `type:pk`.
	 */
	@Nonnull
	private static String describe(@Nonnull EntityReferenceContract reference) {
		return reference.getType() + ":" + reference.getPrimaryKey();
	}

	/**
	 * Concatenates the messages of `throwable` and all its causes.
	 */
	@Nonnull
	private static String causeChainMessages(@Nonnull Throwable throwable) {
		final StringBuilder sb = new StringBuilder();
		Throwable current = throwable;
		for (int depth = 0; current != null && depth < 16; depth++) {
			sb.append(current.getMessage()).append('\n');
			current = current.getCause() == current ? null : current.getCause();
		}
		return sb.toString();
	}

	/**
	 * Copies the fixture `fixture` into fresh test paths.
	 */
	@Nonnull
	private TestPaths copy(@Nonnull String fixture) {
		final TestPaths paths = createTestPaths("UniqueIndexReleaseUpgrade_" + fixture);
		this.allocatedPaths.add(paths);
		try {
			final Path source = Path.of(
				Objects.requireNonNull(
					UniqueIndexReleaseUpgradeTest.class.getClassLoader().getResource(FIXTURES + "/" + fixture),
					"Fixture `" + fixture + "` is not on the class path."
				).toURI()
			);
			try (Stream<Path> files = Files.walk(source)) {
				for (Path file : files.toList()) {
					final Path target = paths.storage().resolve(source.relativize(file).toString());
					if (Files.isDirectory(file)) {
						Files.createDirectories(target);
					} else {
						Files.copy(file, target);
					}
				}
			}
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		} catch (URISyntaxException e) {
			throw new IllegalStateException(e);
		}
		return paths;
	}

	/**
	 * Returns the SHA-256 digest of every file of the catalog folder, by file name.
	 */
	@Nonnull
	private static Map<String, String> catalogFileDigests(@Nonnull TestPaths paths, @Nonnull String catalog) {
		final Map<String, String> digests = new TreeMap<>();
		try (Stream<Path> files = Files.list(paths.storage().resolve(catalog))) {
			for (Path file : files.toList()) {
				digests.put(
					file.getFileName().toString(),
					HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file)))
				);
			}
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		} catch (NoSuchAlgorithmException e) {
			throw new IllegalStateException(e);
		}
		return digests;
	}

}
