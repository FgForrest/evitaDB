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

package io.evitadb.performance.fulltext.state;

import org.roaringbitmap.RoaringBitmap;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.List;
import java.util.stream.Stream;

import static io.evitadb.test.TestTags.QUERY;
import static io.evitadb.test.TestTags.TEST_HARNESS;
import static org.junit.jupiter.api.Assertions.*;

/** Guards census input selection and the order-sensitive result identity without opening a catalog. */
@DisplayName("Fulltext benchmark census queries and result identities")
@Tag(TEST_HARNESS)
@Tag(QUERY)
class FulltextQueryFileTest {

	@Nested
	@DisplayName("Query file selection")
	class Parsing {
		/** Keeps every kind, repeated text identities and exact Unicode/whitespace in file order. */
		@Test
		@DisplayName("Selects all kinds of a cohort and preserves Base64 UTF-8 text")
		void shouldSelectCohortAndPreserveText(@TempDir Path directory) throws IOException {
			final String text = "Příliš\tžluťoučký\n🦊 ";
			final Path file = writeQueries(directory,
				row("a", "by-token", "clean", text),
				row("b", "wide", "fragment", "other"),
				row("c", "by-token", "one-typo", text));
			final List<FulltextQueryFile.Entry> queries = FulltextQueryFile.read(file, "by-token", 64);
			assertEquals(List.of("a".repeat(64), "c".repeat(64)),
				queries.stream().map(FulltextQueryFile.Entry::identity).toList());
			assertEquals(List.of("clean", "one-typo"),
				queries.stream().map(FulltextQueryFile.Entry::kind).toList());
			assertEquals(List.of(text, text), queries.stream().map(FulltextQueryFile.Entry::text).toList());
			assertFalse(queries.toString().contains(text));
		}

		/** Caps selected rows rather than all rows, without sampling or sorting them. */
		@Test
		@DisplayName("Caps the selected pool in file order")
		void shouldCapSelectedPool(@TempDir Path directory) throws IOException {
			final Path file = writeQueries(directory, row("a", "wide", "clean", "skip"),
				row("b", "by-token", "clean", "first"), row("c", "by-token", "fragment", "second"));
			final List<FulltextQueryFile.Entry> queries = FulltextQueryFile.read(file, "by-token", 1);
			assertEquals(1, queries.size());
			assertEquals("b".repeat(64), queries.getFirst().identity());
		}

		/** Missing cohorts must fail before catalog opening, with a useful parameter name. */
		@Test
		@DisplayName("Refuses a missing cohort clearly")
		void shouldThrowExceptionWhenCohortMissing(@TempDir Path directory) throws IOException {
			final Path file = writeQueries(directory, row("a", "wide", "clean", "private phrase"));
			final IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
				() -> FulltextQueryFile.read(file, "by-token", 64));
			assertTrue(error.getMessage().contains("cohort"));
			assertTrue(error.getMessage().contains("by-token"));
			assertFalse(error.toString().contains("private phrase"));
		}

		/** Invalid row errors must not expose row values, even through an exception cause. */
		@ParameterizedTest(name = "{0}")
		@MethodSource("malformedRows")
		@DisplayName("Rejects a malformed row privately")
		void shouldThrowExceptionWhenRowInvalid(String cell, String invalid, @TempDir Path directory)
			throws IOException {
			final Path file = writeQueries(directory, invalid);
			final IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
				() -> FulltextQueryFile.read(file, "wide", 64));
			assertFalse(error.toString().contains("private"));
			assertNull(error.getCause());
		}

		/** One malformed variant of a valid row per validated cell, each value marked private. */
		static Stream<Arguments> malformedRows() {
			final String valid = row("a", "wide", "clean", "private phrase");
			return Stream.of(
				Arguments.of("identity", valid.replace("a".repeat(64), "private identity")),
				Arguments.of("non-numeric weight", valid.replace("\t1\t", "\tprivate weight\t")),
				Arguments.of("zero weight", valid.replace("\t1\t", "\t0\t")),
				Arguments.of("Base64", valid.substring(0, valid.lastIndexOf('\t')) + "\tprivate base64!")
			);
		}

		/** A repeated census identity would blend two occurrences into one result identity. */
		@Test
		@DisplayName("Rejects a duplicate row")
		void shouldThrowExceptionWhenRowDuplicated(@TempDir Path directory) throws IOException {
			final String valid = row("a", "wide", "clean", "private phrase");
			final Path file = writeQueries(directory, valid, valid);
			assertThrows(IllegalArgumentException.class, () -> FulltextQueryFile.read(file, "wide", 64));
		}

		/** An empty pool is refused before the file is read. */
		@Test
		@DisplayName("Rejects a pool size below one")
		void shouldThrowExceptionWhenPoolSizeNotPositive(@TempDir Path directory) throws IOException {
			final Path file = writeQueries(directory, row("a", "wide", "clean", "private phrase"));
			final IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
				() -> FulltextQueryFile.read(file, "wide", 0));
			assertTrue(error.getMessage().contains("poolSize"));
			assertNull(error.getCause());
		}

		/** Only the exact census header identifies a readable file shape. */
		@Test
		@DisplayName("Rejects an unknown header")
		void shouldThrowExceptionWhenHeaderInvalid(@TempDir Path directory) throws IOException {
			final Path file = directory.resolve("queries.tsv");
			Files.writeString(file, "bad header\n" + row("a", "wide", "clean", "private phrase") + "\n");
			final IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
				() -> FulltextQueryFile.read(file, "wide", 64));
			assertTrue(error.getMessage().contains("header"));
			assertNull(error.getCause());
		}
	}

	@Nested
	@DisplayName("Result identity")
	class Identity {
		/** Same counts and ordered page keys must compare equally across forks. */
		@Test
		@DisplayName("Hashes identical results identically including an empty page")
		void shouldHashIdenticalResultsIdentically() {
			assertEquals(FulltextQueryResultIdentity.from(100, new int[]{7, 1, 20}),
				FulltextQueryResultIdentity.from(100, new int[]{7, 1, 20}));
			assertEquals(FulltextQueryResultIdentity.from(0, new int[0]),
				FulltextQueryResultIdentity.from(0, new int[0]));
			assertTrue(FulltextQueryResultIdentity.from(0, new int[0]).pageHash().matches("[0-9a-f]{64}"));
		}

		/** Ranking changes, membership changes and total-count changes remain distinguishable. */
		@Test
		@DisplayName("Distinguishes order, page membership and total count")
		void shouldDistinguishChangedResults() {
			final FulltextQueryResultIdentity reference = FulltextQueryResultIdentity.from(100, new int[]{7, 1, 20});
			assertNotEquals(reference.pageHash(),
				FulltextQueryResultIdentity.from(100, new int[]{1, 7, 20}).pageHash());
			assertNotEquals(reference.pageHash(),
				FulltextQueryResultIdentity.from(100, new int[]{7, 1, 21}).pageHash());
			assertNotEquals(reference, FulltextQueryResultIdentity.from(101, new int[]{7, 1, 20}));
		}
	}

	@Nested
	@DisplayName("Fixture configuration and exact core")
	class Fixture {
		/** The complete core must survive even when expansion adds documents before it in plain order. */
		@Test
		@DisplayName("Accepts a strict superset and refuses a missing core document")
		void shouldVerifyExactCoreSubset() {
			FulltextCatalogFixture.verifyExactCore(RoaringBitmap.bitmapOf(10, 20), RoaringBitmap.bitmapOf(1, 10, 20));
			assertThrows(RuntimeException.class,
				() -> FulltextCatalogFixture.verifyExactCore(RoaringBitmap.bitmapOf(10, 20),
					RoaringBitmap.bitmapOf(1, 10, 30)));
		}

		/** Numeric diagnostics distinguish incomplete materialization from inconsistent totals without raw text. */
		@Test
		@DisplayName("Includes all non-sensitive counts when expanded results disagree")
		void shouldReportExpandedResultCounts() {
			for (final int[] counts : new int[][]{{100, 100, 99, 100, 1}, {100, 99, 99, 100, 1},
				{100, 100, 100, 99, 1}, {100, 100, 100, 100, 0}}) {
				final RuntimeException error = assertThrows(RuntimeException.class,
					() -> FulltextCatalogFixture.verifyExpandedResultCounts(
						counts[0], counts[1], counts[2], counts[3], counts[4]));
				assertEquals("Expanded plain/ranked results disagree or are incomplete: probeTotal=" + counts[0]
					+ ", plainTotal=" + counts[1] + ", plainPageSize=" + counts[2]
					+ ", rankedTotal=" + counts[3] + ", rankedPageSize=" + counts[4] + "!", error.getMessage());
				assertNull(error.getCause());
			}
			FulltextCatalogFixture.verifyExpandedResultCounts(100, 100, 100, 100, 1);
		}

		/** The two-typo threshold is applied through configuration and participates in fixture reuse identity. */
		@Test
		@DisplayName("Configures the two-typo threshold and distinguishes fixture modes")
		void shouldConfigureTwoTypoThreshold(@TempDir Path directory) {
			for (final int threshold : new int[]{8, 9, 10}) {
				assertEquals(threshold,
					FulltextCatalogFixture.createConfiguration(directory, directory, true, threshold)
						.fulltext().minLengthTwoTypos());
			}
			final FulltextCatalogFixture.FixtureKey key =
				new FulltextCatalogFixture.FixtureKey("", "", "", "cs", 8, true);
			assertNotEquals(key, new FulltextCatalogFixture.FixtureKey("", "", "", "cs", 9, true));
			assertNotEquals(key, new FulltextCatalogFixture.FixtureKey("", "", "", "cs", 8, false));
		}
	}

	/** Writes the exact census header, using only invented sample phrases. */
	private static Path writeQueries(Path directory, String... rows) throws IOException {
		final Path file = directory.resolve("queries.tsv");
		Files.writeString(file, "queryHash\tcohort\tkind\tweight\ttextBase64\tcleanBase64\n" +
			String.join("\n", rows) + "\n", StandardCharsets.UTF_8);
		return file;
	}

	/** Encodes an invented query occurrence; distinct occurrences may have the same text. */
	private static String row(String identityCharacter, String cohort, String kind, String text) {
		final String encoded = Base64.getEncoder().encodeToString(text.getBytes(StandardCharsets.UTF_8));
		return identityCharacter.repeat(64) + "\t" + cohort + "\t" + kind + "\t1\t" + encoded + "\t" + encoded;
	}
}
