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

package io.evitadb.index.fulltext;

import io.evitadb.exception.GenericEvitaInternalError;
import io.evitadb.index.fulltext.FulltextScoringPolicy.Place;
import net.openhft.hashing.LongHashFunction;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;

import static io.evitadb.test.TestTags.ENGINE;
import static io.evitadb.test.TestTags.FULLTEXT;
import static io.evitadb.test.TestTags.QUERY;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Verifies how query overrides derive an effective policy from schema defaults: own places replace their weight,
 * composed places stay in the namespace of their composing reference, and both reach the structural hash.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@Tag(ENGINE)
@Tag(QUERY)
@Tag(FULLTEXT)
@DisplayName("Fulltext scoring policy overrides")
class FulltextScoringPolicyTest {
	private static final FulltextFieldKey NAME = FulltextFieldKey.attribute("name");
	private static final FulltextFieldKey DESCRIPTION = FulltextFieldKey.associatedData("description");

	/** Creates schema defaults weighing the name 1 and the description 2 with the engine tie breaker. */
	@Nonnull
	private static FulltextScoringPolicy defaults() {
		return new FulltextScoringPolicy(Map.of(NAME, 1f, DESCRIPTION, 2f));
	}

	/** Own and composed overrides land in separate namespaces and never touch the policy they derive from. */
	@Nested
	@DisplayName("Effective weights")
	class EffectiveWeights {

		/** An own override replaces the schema weight while the shared defaults keep theirs. */
		@Test
		@DisplayName("should replace an own weight without modifying the schema defaults")
		void shouldReplaceOwnWeightWithoutModifyingDefaults() {
			final FulltextScoringPolicy defaults = defaults();

			final FulltextScoringPolicy effective = defaults.withOverrides(Map.of(new Place(null, NAME), 3f));

			assertEquals(3f, effective.weight(NAME));
			assertEquals(2f, effective.weight(DESCRIPTION));
			assertEquals(1f, defaults.weight(NAME));
		}

		/** A composed override answers only for its composing reference and key; other lookups get the default. */
		@Test
		@DisplayName("should keep a composed override out of the own places and apply it to its reference only")
		void shouldApplyComposedOverrideToItsReferenceOnly() {
			final FulltextScoringPolicy effective = defaults().withOverrides(Map.of(new Place("brand", NAME), 0f));

			assertEquals(1f, effective.weight(NAME));
			assertEquals(0f, effective.composedWeight("brand", NAME, 0.5f));
			assertEquals(0.5f, effective.composedWeight("category", NAME, 0.5f));
			assertEquals(0.5f, effective.composedWeight("brand", DESCRIPTION, 0.5f));
			assertEquals(0.5f, defaults().composedWeight("brand", NAME, 0.5f));
		}

		/** The derived policy combines places with the coefficient of the policy it was derived from. */
		@Test
		@DisplayName("should retain the tie breaker of the policy it derives from")
		void shouldRetainTieBreaker() {
			final Map<Place, Float> overrides = Map.of(new Place(null, NAME), 3f);
			final FulltextScoringPolicy withoutBonus = new FulltextScoringPolicy(Map.of(NAME, 1f), 0f);

			final FulltextScoringPolicy engineDefault = defaults().withOverrides(overrides);
			final FulltextScoringPolicy noTieBreaker = withoutBonus.withOverrides(overrides);

			assertEquals(100, noTieBreaker.combine(100, 200));
			assertTrue(engineDefault.combine(100, 200) > 100);
		}

		/** Own and composed overrides pass the same weight validation as schema weights. */
		@Test
		@DisplayName("should refuse negative and non-finite override weights like schema weights")
		void shouldRefuseInvalidOverrideWeights() {
			for (final float weight : new float[]{-1f, Float.NaN, Float.POSITIVE_INFINITY}) {
				for (final Place place : new Place[]{new Place(null, NAME), new Place("brand", NAME)}) {
					final GenericEvitaInternalError error = assertThrows(GenericEvitaInternalError.class,
						() -> defaults().withOverrides(Map.of(place, weight)), "weight=" + weight + ", " + place);

					assertTrue(error.getMessage().contains("must be finite and nonnegative"), error.getMessage());
				}
			}
		}
	}

	/** The hash identifies the effective weights, so every override that changes a score must change it. */
	@Nested
	@DisplayName("Structural hash")
	class StructuralHash {

		/** Each override that changes a score yields a hash of its own. */
		@Test
		@DisplayName("should distinguish own and composed overrides, composing references and values")
		void shouldDistinguishEveryOverride() {
			final LongHashFunction hashFunction = LongHashFunction.xx3();
			final FulltextScoringPolicy defaults = defaults();

			final long[] hashes = {
				defaults.computeHash(hashFunction),
				defaults.withOverrides(Map.of(new Place(null, NAME), 3f)).computeHash(hashFunction),
				defaults.withOverrides(Map.of(new Place("brand", NAME), 3f)).computeHash(hashFunction),
				defaults.withOverrides(Map.of(new Place("category", NAME), 3f)).computeHash(hashFunction),
				defaults.withOverrides(Map.of(new Place("brand", NAME), 4f)).computeHash(hashFunction)
			};

			assertEquals(hashes.length, Arrays.stream(hashes).distinct().count(), Arrays.toString(hashes));
		}

		/** Equal overrides inserted in opposite order hash identically. */
		@Test
		@DisplayName("should hash overrides independently of their insertion order")
		void shouldHashIndependentlyOfInsertionOrder() {
			final LongHashFunction hashFunction = LongHashFunction.xx3();
			final Place[] places = {new Place("brand", NAME), new Place("brand", DESCRIPTION),
				new Place("category", NAME), new Place(null, NAME)};
			final Map<Place, Float> forward = new LinkedHashMap<>();
			final Map<Place, Float> backward = new LinkedHashMap<>();
			for (int i = 0; i < places.length; i++) {
				forward.put(places[i], 1.5f + i);
				backward.put(places[places.length - 1 - i], 1.5f + places.length - 1 - i);
			}

			final long forwardHash = defaults().withOverrides(forward).computeHash(hashFunction);
			final long backwardHash = defaults().withOverrides(backward).computeHash(hashFunction);

			assertEquals(forward, backward);
			assertEquals(forwardHash, backwardHash);
		}
	}
}
