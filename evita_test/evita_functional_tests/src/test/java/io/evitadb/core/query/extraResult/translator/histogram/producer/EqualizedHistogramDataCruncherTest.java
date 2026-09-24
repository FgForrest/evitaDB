/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2023-2026
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

package io.evitadb.core.query.extraResult.translator.histogram.producer;

import io.evitadb.api.exception.InvalidHistogramBucketCountException;
import io.evitadb.core.query.extraResult.translator.histogram.cache.CacheableHistogramContract.CacheableBucket;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;

import static io.evitadb.test.TestTags.ENGINE;
import static io.evitadb.test.TestTags.HISTOGRAM;
import static io.evitadb.test.TestTags.QUERY;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * This test verifies the {@link EqualizedHistogramDataCruncher} contract.
 *
 * The suite is deliberately **invariant-based rather than pinned to expected numbers**. The bar heights come from a
 * kernel density estimate whose bandwidth is a continuous function of the whole catalogue, so a golden-value test
 * would break on any harmless re-derivation while saying nothing about whether the histogram is still *correct*. What
 * has to hold instead are the properties a filter slider depends on:
 *
 * - the returned bucket count never exceeds the requested one, and every bucket is non-empty with a distinct,
 *   selectable threshold,
 * - a value held by many records is charged for every quantile target it absorbs, so it cannot starve the buckets
 *   that follow it (the defect this class was rewritten for),
 * - the height reacts to the *distribution* and not to incidental structure: it is invariant to replicating the
 *   catalogue, insensitive to how the same mass is spread over neighbouring values, and continuous when a record
 *   crosses a quartile boundary.
 *
 * Two production catalogues captured from a live storefront are part of the corpus and stay in it permanently -
 * charm-priced retail data is precisely the shape that broke the previous implementation.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2025
 */
@Slf4j
@DisplayName("EqualizedHistogramDataCruncher tests")
@Tag(ENGINE)
@Tag(QUERY)
@Tag(HISTOGRAM)
class EqualizedHistogramDataCruncherTest {
	/**
	 * Production price catalogue of the `zrcadla-a-galerky` category of a live storefront, run-length encoded as
	 * `priceInThousandths:productCount`. 3 237 products over only 135 distinct prices, the busiest holding 241 of
	 * them - the charm-pricing shape (999, 799, 899, 699, 599, 499) that the previous bucketing collapsed on.
	 */
	private static final String PRODUCTION_PRICES =
		"9000:3,19000:5,29000:5,31000:1,39000:1,49000:9,56000:1,60000:4,69000:1,70000:1,75000:22,87000:2,90000:7," +
			"99000:5,105000:1,110000:1,119000:1,129000:6,135000:1,139000:4,143000:1,145000:3,149000:2,179000:1," +
			"189000:14,199000:1,203000:3,217000:1,229000:1,230000:8,239000:1,245000:3,249000:6,254000:1,259000:5," +
			"267000:8,269000:4,286000:23,289000:12,299000:30,309000:2,314000:2,319000:4,324000:1,329000:3,338000:3," +
			"339000:2,349000:21,359000:7,364000:1,369000:16,374000:5,389000:2,393000:18,396000:6,399000:41,419000:6," +
			"422000:1,424000:20,429000:4,439000:2,441000:8,449000:62,452000:34,458000:1,469000:2,473000:21,479000:3," +
			"495000:1,499000:82,507000:38,509000:2,529000:9,530000:16,535000:146,539000:3,549000:26,552000:1," +
			"579000:1,592000:52,595000:1,598000:14,599000:95,618000:1,628000:58,630000:9,649000:28,659000:1," +
			"674000:67,679000:4,695000:3,699000:110,709000:1,728000:113,749000:81,752000:4,769000:5,799000:176," +
			"806000:61,811000:3,849000:28,856000:115,872000:3,899000:172,910000:81,934000:86,940000:5,949000:6," +
			"999000:241,1007000:44,1070000:70,1090000:62,1145000:19,1190000:84,1210000:9,1282000:34,1290000:83," +
			"1390000:38,1422000:1,1490000:44,1561000:37,1590000:28,1605000:25,1690000:8,1790000:26,1890000:9," +
			"1990000:28,2090000:1,2117000:3,2190000:6,2290000:4,2390000:1,2490000:76,2809000:14,2990000:27";
	/**
	 * Production attribute histogram captured from the same storefront, run-length encoded the same way. Only 24
	 * distinct values, and a single one of them holds 338 of the 666 records - 50.75%, five records away from the
	 * hard majority switch this implementation deliberately does not use.
	 */
	private static final String PRODUCTION_ATTRIBUTE =
		"200000:1,205000:1,400000:3,406000:7,420000:6,430000:6,433000:5,435000:37,440000:2,457000:1,458000:1," +
			"460000:52,465000:3,475000:4,480000:44,490000:1,495000:2,500000:338,505000:5,510000:123,515000:11," +
			"520000:6,560000:1,575000:6";
	/**
	 * Number of randomly generated catalogues each property-style test runs over.
	 */
	private static final int RANDOM_CATALOGUE_COUNT = 5_000;
	/**
	 * Fixed seed so a failure is always reproducible.
	 */
	private static final long RANDOM_SEED = 20_260_907L;

	@Nested
	@DisplayName("Degenerate and invalid inputs")
	class DegenerateInputs {

		@Test
		@DisplayName("Should throw exception when fewer than two buckets are requested")
		void shouldThrowExceptionWhenFewerThanTwoBucketsRequested() {
			for (int i = 1; i > -2; i--) {
				final int bucketCount = i;
				assertThrows(
					InvalidHistogramBucketCountException.class,
					() -> cruncher(bucketCount, new int[][]{{100, 1}, {200, 1}})
				);
			}
		}

		@Test
		@DisplayName("Should throw exception when source data is empty")
		void shouldThrowExceptionWhenSourceDataIsEmpty() {
			assertThrows(
				IllegalArgumentException.class,
				() -> cruncher(5, new int[0][])
			);
		}

		@Test
		@DisplayName("Should collapse a single distinct value into one bucket occupying the whole scale")
		void shouldCollapseSingleDistinctValueIntoOneFullHeightBucket() {
			// D = 1 is a routine production result for attribute histograms once a facet narrows the selection
			final CacheableBucket[] histogram = cruncher(20, new int[][]{{100, 7}}).getHistogram();

			assertEquals(1, histogram.length, "One distinct value cannot be split into more than one bucket");
			assertEquals(new BigDecimal(100), histogram[0].threshold());
			assertEquals(7, histogram[0].occurrences());
			assertEquals(
				new BigDecimal("100"), histogram[0].relativeFrequency(),
				"The only bucket is by definition the peak of the distribution"
			);
		}

		@Test
		@DisplayName("Should yield exactly two buckets when only two distinct values exist")
		void shouldYieldTwoBucketsWhenOnlyTwoDistinctValuesExist() {
			final CacheableBucket[] histogram = cruncher(20, new int[][]{{100, 3}, {500, 9}}).getHistogram();

			assertEquals(2, histogram.length, "There is no third value to open a third bucket at");
			assertEquals(new BigDecimal(100), histogram[0].threshold());
			assertEquals(3, histogram[0].occurrences());
			assertEquals(new BigDecimal(500), histogram[1].threshold());
			assertEquals(9, histogram[1].occurrences());
		}

		@Test
		@DisplayName("Should stay linear in the data when a huge bucket count is requested")
		void shouldStayLinearInTheDataWhenHugeBucketCountRequested() {
			// The caller picks bucketCount and nothing bounds it, so walking the quantile function one rank at a
			// time would let a single request cost O(bucketCount) regardless of how little data there is. The
			// rank walk consumes whole runs in one step instead. The timeout is the assertion that matters:
			// asserting only the output would pass just as happily against a walk that took a hundred million
			// iterations to produce it.
			final CacheableBucket[] histogram = assertTimeoutPreemptively(
				Duration.ofSeconds(5),
				() -> cruncher(
					Integer.MAX_VALUE, new int[][]{{10, 3}, {20, 999_000}, {30, 7}}
				).getHistogram(),
				"The quantile walk scaled with the requested bucket count instead of with the data"
			);

			assertEquals(3, histogram.length, "Three distinct values cannot produce more than three buckets");
			assertEquals(3, histogram[0].occurrences());
			assertEquals(999_000, histogram[1].occurrences());
			assertEquals(7, histogram[2].occurrences());
		}

		@Test
		@DisplayName("Should preserve requested decimal places in thresholds")
		void shouldPreserveRequestedDecimalPlacesInThresholds() {
			final EqualizedHistogramDataCruncher<int[]> cruncher = new EqualizedHistogramDataCruncher<>(
				"test histogram", 5, 2,
				new int[][]{{1_050, 4}, {2_575, 6}, {9_999, 2}},
				pair -> pair[0], pair -> pair[1],
				value -> new BigDecimal(value).scaleByPowerOfTen(-2)
			);
			final CacheableBucket[] histogram = cruncher.getHistogram();

			assertEquals(3, histogram.length);
			assertEquals(new BigDecimal("10.50"), histogram[0].threshold());
			assertEquals(new BigDecimal("25.75"), histogram[1].threshold());
			assertEquals(new BigDecimal("99.99"), histogram[2].threshold());
			assertEquals(new BigDecimal("99.99"), cruncher.getMaxValue());
		}
	}

	@Nested
	@DisplayName("Bucketing invariants")
	class BucketingInvariants {

		@Test
		@DisplayName("Should never exceed the requested bucket count nor emit an empty or repeated bucket")
		void shouldNeverExceedBudgetNorEmitDegenerateBucket() {
			forEachRandomCatalogue((values, weights, bucketCount, histogram) -> {
				assertTrue(
					histogram.length <= Math.min(bucketCount, values.length),
					"Returned " + histogram.length + " buckets for a budget of " + bucketCount +
						" over " + values.length + " distinct values"
				);
				assertTrue(histogram.length > 0, "A non-empty catalogue must produce at least one bucket");
				for (int i = 0; i < histogram.length; i++) {
					assertTrue(
						histogram[i].occurrences() > 0,
						"Bucket " + i + " is empty - the equalized algorithm must never emit one"
					);
					if (i > 0) {
						assertTrue(
							histogram[i - 1].threshold().compareTo(histogram[i].threshold()) < 0,
							"Thresholds must strictly increase so that every slider position selects a different set"
						);
					}
				}
			});
		}

		@Test
		@DisplayName("Should account for the whole catalogue in bucket occurrences")
		void shouldAccountForWholeCatalogueInBucketOccurrences() {
			forEachRandomCatalogue((values, weights, bucketCount, histogram) -> {
				long total = 0;
				for (int weight : weights) {
					total += weight;
				}
				long counted = 0;
				for (CacheableBucket bucket : histogram) {
					counted += bucket.occurrences();
				}
				assertEquals(total, counted, "Buckets must partition the catalogue without loss or duplication");
			});
		}

		@Test
		@DisplayName("Should charge every bucket for the quantile targets its values absorbed")
		void shouldChargeEveryBucketForTheTargetsItAbsorbed() {
			// This is the invariant the previous implementation broke. A distinct value's weight is atomic - a
			// bucket that opens on a value holding 30% of the catalogue is going to hold at least 30%, and that is
			// inherent. What is not inherent is failing to *charge* that value for the quantile targets it
			// swallowed, which is what let one bar absorb a third of the catalogue while thirteen of the twenty
			// that followed it starved.
			forEachRandomCatalogue((values, weights, bucketCount, histogram) -> {
				long total = 0;
				for (int weight : weights) {
					total += weight;
				}
				final double targetMass = (double) total / bucketCount;

				for (int i = 0; i < histogram.length; i++) {
					final int from = indexOfThreshold(values, histogram[i].threshold());
					final int to = i + 1 < histogram.length
						? indexOfThreshold(values, histogram[i + 1].threshold()) - 1
						: values.length - 1;
					int heaviest = 0;
					for (int j = from; j <= to; j++) {
						heaviest = Math.max(heaviest, weights[j]);
					}
					assertTrue(
						histogram[i].occurrences() - heaviest <= targetMass,
						"Bucket " + i + " holds " + histogram[i].occurrences() + " records, of which its heaviest " +
							"single value contributes " + heaviest + " - the remainder exceeds one target of " +
							targetMass
					);
				}
			});
		}

		@Test
		@DisplayName("Should split evenly weighted values into exactly equal buckets")
		void shouldSplitEvenlyWeightedValuesIntoEqualBuckets() {
			// Evenly weighted data is the one shape where every quantile rank falls exactly on a value
			// boundary, which makes it the only shape that can tell "the value that CONTAINS the rank" apart
			// from "the value that ENDS at it". Picking the latter puts every cut one distinct value early and
			// piles the remainder onto the last bucket. Irregular retail data never exhibits it - both
			// production corpora below bucket identically under either convention - so this case has to be
			// constructed deliberately or the error is invisible.
			final int[][] sixEqualValues = new int[6][];
			for (int i = 0; i < sixEqualValues.length; i++) {
				sixEqualValues[i] = new int[]{(i + 1) * 10, 7};
			}
			final CacheableBucket[] thirds = cruncher(3, sixEqualValues).getHistogram();

			assertEquals(3, thirds.length, "Six equal masses split three ways must produce three buckets");
			for (int i = 0; i < thirds.length; i++) {
				assertEquals(
					14, thirds[i].occurrences(),
					"Bucket " + i + " of an evenly weighted catalogue must hold exactly a third of it"
				);
			}
		}

		@Test
		@DisplayName("Should fill every bucket when the plateau count matches the bucket count")
		void shouldFillEveryBucketWhenPlateauCountMatchesBucketCount() {
			// Twenty equal plateaus asked to fill twenty buckets is the degenerate form of the case above:
			// one plateau per bucket is both achievable and obviously correct, so anything less is a defect
			// rather than the unavoidable consequence of an indivisible value.
			final int[][] twentyPlateaus = new int[20][];
			for (int i = 0; i < twentyPlateaus.length; i++) {
				twentyPlateaus[i] = new int[]{(i + 1) * 100 - 1, 100};
			}
			final CacheableBucket[] histogram = cruncher(20, twentyPlateaus).getHistogram();

			assertEquals(20, histogram.length, "Twenty equal plateaus must fill twenty buckets");
			for (int i = 0; i < histogram.length; i++) {
				assertEquals(
					100, histogram[i].occurrences(),
					"Bucket " + i + " must hold exactly one plateau, not two"
				);
			}
		}

		@Test
		@DisplayName("Should aggregate duplicate consecutive thresholds in the source data")
		void shouldAggregateDuplicateConsecutiveThresholds() {
			// This is the shape the price histogram always has: one source item per entity carrying weight 1,
			// so a price shared by several products arrives as repeated consecutive entries rather than as a
			// pre-aggregated pair. Feeding only distinct pairs leaves the aggregation pass unexercised.
			final int[][] perRecord = {
				{100, 1}, {100, 1}, {100, 1},
				{200, 1},
				{300, 1}, {300, 1}, {300, 1}, {300, 1}, {300, 1}
			};
			final CacheableBucket[] histogram = cruncher(3, perRecord).getHistogram();

			assertEquals(3, histogram.length, "Three distinct prices must open three buckets");
			assertEquals(new BigDecimal(100), histogram[0].threshold());
			assertEquals(3, histogram[0].occurrences(), "The three records priced 100 must be folded together");
			assertEquals(new BigDecimal(200), histogram[1].threshold());
			assertEquals(1, histogram[1].occurrences());
			assertEquals(new BigDecimal(300), histogram[2].threshold());
			assertEquals(5, histogram[2].occurrences(), "The five records priced 300 must be folded together");
		}

		@Test
		@DisplayName("Should isolate a heavy value into a bucket of its own")
		void shouldIsolateHeavyValueIntoItsOwnBucket() {
			// forty evenly priced values holding ten products each, plus one price holding two hundred - a third
			// of the catalogue on a single value, which is exactly what charm pricing produces. This pins the
			// isolation rule only: the heavy value opens a bucket and the next distinct price opens the one
			// after it, so its mass cannot spill forwards. It is deliberately NOT the regression guard for
			// target-crossing accounting - a bucketing that mis-charges absorbed ranks can still isolate this
			// particular value, and shouldChargeEveryBucketForTheTargetsItAbsorbed is what actually detects
			// that across the random corpus.
			final int[][] catalogue = new int[40][];
			for (int i = 0; i < catalogue.length; i++) {
				catalogue[i] = new int[]{(i + 1) * 37, 10};
			}
			catalogue[12][1] = 200;

			final CacheableBucket[] histogram = cruncher(10, catalogue).getHistogram();

			int isolating = -1;
			for (int i = 0; i < histogram.length; i++) {
				if (histogram[i].threshold().compareTo(new BigDecimal(13 * 37)) == 0) {
					isolating = i;
				}
			}
			assertTrue(isolating >= 0, "The heavy value must open a bucket of its own");
			assertEquals(
				200, histogram[isolating].occurrences(),
				"The heavy value's bucket must hold its mass and nothing else - it was charged for every quantile " +
					"target it absorbed, so the following bucket reopens at the very next price"
			);
		}
	}

	@Nested
	@DisplayName("Height stability")
	class HeightStability {

		@Test
		@DisplayName("Should keep relative frequency inside the documented (0, 100] scale")
		void shouldKeepRelativeFrequencyWithinDocumentedScale() {
			forEachRandomCatalogue((values, weights, bucketCount, histogram) -> {
				for (int i = 0; i < histogram.length; i++) {
					final BigDecimal frequency = histogram[i].relativeFrequency();
					assertTrue(
						frequency.compareTo(BigDecimal.ZERO) > 0,
						"Bucket " + i + " holds records but renders at " + frequency
					);
					assertTrue(
						frequency.compareTo(new BigDecimal("100")) <= 0,
						"Bucket " + i + " renders at " + frequency + ", above the curve maximum"
					);
				}
			});
		}

		@Test
		@DisplayName("Should keep bar heights stable when a neighbouring value is repriced by one unit")
		void shouldKeepHeightStableWhenNeighbouringValueIsRepriced() {
			// The previous implementation read the height off the single gap between two adjacent prices, so
			// nudging one price by one unit could change a bar by orders of magnitude. Every distinct price in the
			// production catalogue is nudged in turn; the worst observed drift is around 0.01%.
			final int[][] original = parse(PRODUCTION_PRICES);
			final CacheableBucket[] reference = cruncher(20, original).getHistogram();

			double worstDrift = 0.0;
			for (int i = 0; i < original.length; i++) {
				if (i + 1 < original.length && original[i][0] + 1 >= original[i + 1][0]) {
					// nudging here would collide with the next price and genuinely change the catalogue
					continue;
				}
				final int[][] nudged = parse(PRODUCTION_PRICES);
				nudged[i][0] += 1;
				worstDrift = Math.max(
					worstDrift, maximumRelativeDrift(reference, cruncher(20, nudged).getHistogram())
				);
			}

			assertTrue(
				worstDrift < 0.001,
				"Moving one price by one unit shifted a bar by " + (worstDrift * 100) + "% - the height must " +
					"describe the distribution, not the gap between two neighbours"
			);
		}

		@Test
		@DisplayName("Should keep bar heights identical when the whole catalogue is replicated")
		void shouldKeepHeightInvariantWhenCatalogueIsReplicated() {
			// The estimate smooths a catalogue that is known in full - it does not infer a latent population from a
			// sample. Cloning every product leaves the shape identical, so it must leave the curve identical; this
			// is why the bandwidth's count term is the number of distinct values and not the number of records.
			final int[][] single = parse(PRODUCTION_PRICES);
			final int[][] tripled = parse(PRODUCTION_PRICES);
			for (int[] pair : tripled) {
				pair[1] *= 3;
			}

			final CacheableBucket[] one = cruncher(20, single).getHistogram();
			final CacheableBucket[] three = cruncher(20, tripled).getHistogram();

			assertEquals(one.length, three.length, "Replication must not change the bucketing");
			for (int i = 0; i < one.length; i++) {
				assertEquals(
					one[i].threshold(), three[i].threshold(),
					"Replication moved the threshold of bucket " + i
				);
				assertEquals(
					one[i].relativeFrequency(), three[i].relativeFrequency(),
					"Replication changed the height of bucket " + i
				);
			}
		}

		@Test
		@DisplayName("Should keep bar heights stable when the same mass is spread over neighbouring values")
		void shouldKeepHeightStableWhenMassIsFragmentedOverNeighbours() {
			// Whether a shop prices 240 mirrors at exactly 999 or scatters them over 997..1001 is a pricing
			// accident, not a change in the distribution - a bandwidth read off the value grid would react
			// violently to it.
			final int[][] concentrated = parse(PRODUCTION_PRICES);
			final List<int[]> fragmented = new ArrayList<>();
			for (int[] pair : concentrated) {
				if (pair[1] >= 60) {
					// scatter a heavy plateau over five adjacent values one unit apart
					final int share = pair[1] / 5;
					for (int k = 0; k < 5; k++) {
						fragmented.add(new int[]{pair[0] + k, k == 4 ? pair[1] - 4 * share : share});
					}
				} else {
					fragmented.add(new int[]{pair[0], pair[1]});
				}
			}

			// The discriminating measure is WHERE the profile peaks. A density read off each bucket's own width
			// relocates the peak entirely when a plateau is split - the mass at one position collapses and a new
			// maximum appears several buckets away - whereas a density pooled over the whole axis barely moves
			// it. Counting bars above some height threshold cannot see that: both profiles can have the same
			// number of tall bars in completely different places.
			final double peakShift = relativePeakShift(
				concentrated,
				cruncher(20, concentrated).getHistogram(),
				cruncher(20, fragmented.toArray(new int[0][])).getHistogram()
			);
			assertTrue(
				peakShift < 0.05,
				"Fragmenting heavy plateaus over adjacent values moved the peak of the profile by " +
					(peakShift * 100) + "% of the value range - the bandwidth must not be a function of the " +
					"value grid"
			);
		}

		@Test
		@DisplayName("Should keep bar heights continuous when a record crosses the quartile boundary")
		void shouldKeepHeightContinuousWhenRecordCrossesQuartileBoundary() {
			// A point-valued quantile is a step function of the weights: on values 0, 1, 2, G a single record
			// moving across the upper quartile takes a nearest-rank IQR from 2 to G, and the bandwidth with it -
			// measured at 293 885x for G = 10^6. Band-averaging the quantile removes the cliff, so walking one
			// record of a hundred across the boundary must stay smooth. The worst observed step is 1.26x.
			final int outlier = 1_000_000;
			double worstStep = 1.0;
			CacheableBucket[] previous = null;
			for (int moved = 0; moved <= 10; moved++) {
				final int[][] catalogue = {
					{0, 25 + moved}, {1, 25}, {2, 25}, {outlier, 25 - moved}
				};
				final CacheableBucket[] histogram = cruncher(4, catalogue).getHistogram();
				if (previous != null && previous.length == histogram.length) {
					worstStep = Math.max(worstStep, 1.0 + maximumRelativeDrift(previous, histogram));
				}
				previous = histogram;
			}
			assertTrue(
				worstStep < 1.5,
				"Moving one record of a hundred across the quartile boundary changed a bar by " + worstStep +
					"x - the spread estimate is discontinuous"
			);
		}

		@Test
		@DisplayName("Should keep every bar visible while one value grows to dominate the catalogue")
		void shouldKeepEveryBucketVisibleWhenOneValueDominates() {
			// Sweeps a single value from a third to nine tenths of the catalogue, straight through the point where
			// a hard `w > N / 2` majority switch would have stepped the bandwidth discontinuously. The faintest bar
			// observed anywhere in the sweep renders at 1.96.
			for (int dominantWeight = 200; dominantWeight <= 3_600; dominantWeight += 100) {
				final int[][] catalogue = {
					{100, 40}, {200, 60}, {300, 80}, {400, dominantWeight},
					{500, 70}, {600, 90}, {700, 50}, {900, 30}
				};
				final CacheableBucket[] histogram = cruncher(8, catalogue).getHistogram();
				for (int i = 0; i < histogram.length; i++) {
					assertTrue(
						histogram[i].relativeFrequency().compareTo(new BigDecimal("0.50")) > 0,
						"With the dominant value at weight " + dominantWeight + " bucket " + i + " rendered at " +
							histogram[i].relativeFrequency() + " - the bandwidth collapsed"
					);
				}
			}
		}

		@Test
		@DisplayName("Should match a direct quadratic reference for the kernel evaluation")
		void shouldMatchDirectQuadraticReferenceForKernelEvaluation() {
			// The kernel masses are produced by one O(D + B) sweep that maintains running sums across a moving
			// window. This recomputes the same quantity the naive O(B * D) way, from the published output only.
			for (String corpus : new String[]{PRODUCTION_PRICES, PRODUCTION_ATTRIBUTE}) {
				final int[][] catalogue = parse(corpus);
				for (int bucketCount : new int[]{2, 5, 20, 50}) {
					assertMatchesDirectReference(catalogue, bucketCount);
				}
			}
			final Random random = new Random(RANDOM_SEED);
			for (int i = 0; i < 200; i++) {
				assertMatchesDirectReference(randomCatalogue(random), 2 + random.nextInt(30));
			}
		}
	}

	@Nested
	@DisplayName("Production corpus")
	class ProductionCorpus {

		@Test
		@DisplayName("Should render the production price catalogue as a readable profile")
		void shouldRenderProductionPriceCatalogueAsReadableProfile() {
			final CacheableBucket[] histogram = cruncher(20, parse(PRODUCTION_PRICES)).getHistogram();

			assertEquals(20, histogram.length, "3 237 products over 135 distinct prices fill the whole budget");

			// the defect this replaces rendered the bucket holding 81 products at 35.15 and the one holding 338 at
			// 6.03 - a quarter of the records drawing almost six times taller. Heights must now track the mass.
			final int tallest = indexOfMaximumRelativeFrequency(histogram);
			final int shortest = indexOfMinimumRelativeFrequency(histogram);
			assertTrue(
				histogram[tallest].occurrences() > histogram[shortest].occurrences(),
				"The tallest bar (" + histogram[tallest].occurrences() + " products) must not hold fewer " +
					"records than the shortest one (" + histogram[shortest].occurrences() + ")"
			);

			// the dynamic range must stay renderable without a compressing transform on the client
			final double ratio = histogram[tallest].relativeFrequency().doubleValue()
				/ histogram[shortest].relativeFrequency().doubleValue();
			assertTrue(
				ratio < 25.0,
				"Tallest-to-shortest bar ratio is " + ratio + " - storefronts would have to compress it again"
			);
		}

		@Test
		@DisplayName("Should render the production attribute histogram when one value holds half the records")
		void shouldRenderProductionAttributeHistogramWhenOneValueHoldsHalfTheRecords() {
			// 338 of 666 records sit on a single value - 50.75%, right beside the point where a hard majority
			// switch on the spread estimate would have stepped discontinuously.
			final CacheableBucket[] histogram = cruncher(20, parse(PRODUCTION_ATTRIBUTE)).getHistogram();

			assertTrue(
				histogram.length < 20,
				"Only 24 distinct values with half the mass on one of them cannot fill twenty buckets"
			);
			int majorityBucket = -1;
			for (int i = 0; i < histogram.length; i++) {
				if (histogram[i].threshold().compareTo(new BigDecimal(500_000)) == 0) {
					majorityBucket = i;
				}
			}
			assertTrue(majorityBucket >= 0, "The majority value must open a bucket of its own");
			assertEquals(
				338, histogram[majorityBucket].occurrences(),
				"The majority value's mass must be closed into its own bucket, not spilled into the next"
			);
		}
	}

	/*
		HELPER METHODS AND TYPES
	 */

	/**
	 * Assertion applied to a randomly generated catalogue and the histogram computed from it.
	 */
	@FunctionalInterface
	private interface CatalogueAssertion {

		/**
		 * Verifies one property of a computed histogram.
		 *
		 * @param values      distinct values of the catalogue in ascending order
		 * @param weights     weight of each distinct value
		 * @param bucketCount bucket count the histogram was requested with
		 * @param histogram   the computed histogram
		 */
		void verify(
			@Nonnull int[] values, @Nonnull int[] weights, int bucketCount, @Nonnull CacheableBucket[] histogram
		);
	}

	/**
	 * Runs `assertion` over {@link #RANDOM_CATALOGUE_COUNT} pseudo-random catalogues drawn from a spread of shapes:
	 * charm-priced, uniform, tightly clustered, dominated by one value, very sparse, and one with a far outlier.
	 *
	 * @param assertion property to verify on every generated catalogue
	 */
	private static void forEachRandomCatalogue(@Nonnull CatalogueAssertion assertion) {
		final Random random = new Random(RANDOM_SEED);
		for (int i = 0; i < RANDOM_CATALOGUE_COUNT; i++) {
			final int[][] catalogue = randomCatalogue(random);
			final int bucketCount = 2 + random.nextInt(50);
			final int[] values = new int[catalogue.length];
			final int[] weights = new int[catalogue.length];
			for (int j = 0; j < catalogue.length; j++) {
				values[j] = catalogue[j][0];
				weights[j] = catalogue[j][1];
			}
			assertion.verify(values, weights, bucketCount, cruncher(bucketCount, catalogue).getHistogram());
		}
	}

	/**
	 * Draws one pseudo-random catalogue as an ascending array of `{value, weight}` pairs. Values are grown by random
	 * positive steps, so they are distinct and sorted by construction.
	 *
	 * @param random source of randomness
	 * @return catalogue with at least two distinct values
	 */
	@Nonnull
	private static int[][] randomCatalogue(@Nonnull Random random) {
		final int shape = random.nextInt(6);
		final int distinctCount = switch (shape) {
			case 4 -> 2 + random.nextInt(5);
			default -> 2 + random.nextInt(60);
		};
		// largest step between two neighbouring values - small for a tightly clustered catalogue, large for a
		// sparse one
		final int maximumStep = switch (shape) {
			case 1 -> 4_000;
			case 2 -> 7;
			case 4 -> 300_000;
			default -> 200;
		};

		final int[] values = new int[distinctCount];
		int value = random.nextInt(1_000);
		for (int i = 0; i < distinctCount; i++) {
			value += 1 + random.nextInt(maximumStep);
			values[i] = value;
		}

		final int[][] catalogue = new int[distinctCount][];
		for (int i = 0; i < distinctCount; i++) {
			final int weight = switch (shape) {
				case 0 -> new int[]{1, 1, 2, 3, 5, 40, 120, 240}[random.nextInt(8)];
				case 2 -> 1 + random.nextInt(50);
				case 3 -> 1;
				default -> 1 + random.nextInt(5);
			};
			catalogue[i] = new int[]{values[i], weight};
		}
		if (shape == 3) {
			// one value carries several times the weight of everything else put together
			catalogue[random.nextInt(distinctCount)][1] = distinctCount * (3 + random.nextInt(48));
		}
		if (shape == 5) {
			// push the last value far away from the rest of the catalogue
			catalogue[distinctCount - 1][0] = 1_000_000 + random.nextInt(1_000_000);
		}
		return catalogue;
	}

	/**
	 * Recomputes the histogram's relative frequencies the naive way - deriving the bucket spans from the published
	 * thresholds, re-deriving the bandwidth from its published formula, and evaluating the triangular kernel at
	 * every distinct value with a direct double loop - and asserts the cruncher agrees.
	 *
	 * @param catalogue   ascending `{value, weight}` pairs
	 * @param bucketCount requested bucket count
	 */
	private static void assertMatchesDirectReference(@Nonnull int[][] catalogue, int bucketCount) {
		final int distinctCount = catalogue.length;
		if (distinctCount < 2) {
			return;
		}
		final CacheableBucket[] histogram = cruncher(bucketCount, catalogue).getHistogram();
		final int[] values = new int[distinctCount];
		final int[] weights = new int[distinctCount];
		long total = 0;
		for (int i = 0; i < distinctCount; i++) {
			values[i] = catalogue[i][0];
			weights[i] = catalogue[i][1];
			total += weights[i];
		}

		final double bandwidth = referenceBandwidth(values, weights, total);
		final double[] density = new double[distinctCount];
		double peak = 0.0;
		for (int q = 0; q < distinctCount; q++) {
			double mass = 0.0;
			for (int j = 0; j < distinctCount; j++) {
				final double distance = Math.abs((double) values[q] - values[j]);
				if (distance < bandwidth) {
					mass += weights[j] * (1.0 - distance / bandwidth);
				}
			}
			density[q] = mass;
			peak = Math.max(peak, mass);
		}

		for (int i = 0; i < histogram.length; i++) {
			final int from = indexOfThreshold(values, histogram[i].threshold());
			final int to = i + 1 < histogram.length
				? indexOfThreshold(values, histogram[i + 1].threshold()) - 1
				: distinctCount - 1;
			long bucketWeight = 0;
			for (int j = from; j <= to; j++) {
				bucketWeight += weights[j];
			}
			int representative = from;
			long cumulated = 0;
			for (int j = from; j <= to; j++) {
				cumulated += weights[j];
				if (cumulated * 2 >= bucketWeight) {
					representative = j;
					break;
				}
			}
			final double expected = 100.0 * density[representative] / peak;
			final double actual = histogram[i].relativeFrequency().doubleValue();
			// the sliding window accumulates its sums in a different order than the direct loop, so the two agree
			// to within floating-point noise rather than bit for bit
			assertTrue(
				Math.abs(expected - actual) <= 0.02,
				"Bucket " + i + " of " + bucketCount + " renders at " + actual + " but the direct evaluation " +
					"gives " + expected
			);
		}
	}

	/**
	 * Re-derivation of `h = √6 · 0.9 · min(σ_w, IQR_c / 1.34) · D^(−1/5)` written out longhand, used only to
	 * cross-check the production implementation.
	 *
	 * @param values  distinct values in ascending order
	 * @param weights weight of each distinct value
	 * @param total   total weight
	 * @return support radius of the triangular kernel
	 */
	private static double referenceBandwidth(@Nonnull int[] values, @Nonnull int[] weights, long total) {
		final int distinctCount = values.length;
		double weightedSum = 0.0;
		for (int i = 0; i < distinctCount; i++) {
			weightedSum += (double) weights[i] * values[i];
		}
		final double mean = weightedSum / total;
		double squares = 0.0;
		for (int i = 0; i < distinctCount; i++) {
			squares += weights[i] * (values[i] - mean) * (values[i] - mean);
		}
		final double standardDeviation = Math.sqrt(squares / total);

		final double[] capped = new double[distinctCount];
		double cappedTotal = 0.0;
		for (int i = 0; i < distinctCount; i++) {
			capped[i] = Math.max(Math.min(weights[i], (total - (double) weights[i]) / 2.0), 1e-12);
			cappedTotal += capped[i];
		}
		final double interQuartileRange =
			(referenceBandQuantile(values, capped, cappedTotal, 0.75)
				- referenceBandQuantile(values, capped, cappedTotal, 0.25)) / 1.34;

		final double spread;
		if (standardDeviation > 0.0 && interQuartileRange > 0.0) {
			spread = Math.min(standardDeviation, interQuartileRange);
		} else if (standardDeviation > 0.0) {
			spread = standardDeviation;
		} else {
			spread = interQuartileRange;
		}
		return Math.sqrt(6.0) * 0.9 * spread * Math.pow(distinctCount, -0.2);
	}

	/**
	 * Longhand band-averaged weighted quantile, used only to cross-check the production implementation.
	 *
	 * @param values      distinct values in ascending order
	 * @param capped      per-value weights the rank axis is built from
	 * @param cappedTotal sum of `capped`
	 * @param quantile    requested rank
	 * @return band-averaged quantile value
	 */
	private static double referenceBandQuantile(
		@Nonnull int[] values, @Nonnull double[] capped, double cappedTotal, double quantile
	) {
		final double low = quantile - 0.05;
		final double high = quantile + 0.05;
		double accumulated = 0.0;
		double cumulated = 0.0;
		for (int i = 0; i < values.length; i++) {
			final double from = cumulated / cappedTotal;
			cumulated += capped[i];
			final double to = cumulated / cappedTotal;
			final double overlap = Math.min(high, to) - Math.max(low, from);
			if (overlap > 0.0) {
				accumulated += (double) values[i] * overlap;
			}
		}
		return accumulated / (high - low);
	}

	/**
	 * Returns the largest relative change of any bar height between two histograms of equal length, expressed as a
	 * fraction of the smaller value.
	 *
	 * @param before histogram before the perturbation
	 * @param after  histogram after the perturbation
	 * @return largest relative drift, or zero when the two histograms cannot be compared bar by bar
	 */
	private static double maximumRelativeDrift(@Nonnull CacheableBucket[] before, @Nonnull CacheableBucket[] after) {
		assertEquals(
			before.length, after.length,
			"Histograms of different lengths cannot be compared bar by bar - returning \"no drift\" here would " +
				"turn a structural change into a silent pass"
		);
		double worst = 0.0;
		for (int i = 0; i < before.length; i++) {
			final double a = before[i].relativeFrequency().doubleValue();
			final double b = after[i].relativeFrequency().doubleValue();
			worst = Math.max(worst, Math.abs(a - b) / Math.min(a, b));
		}
		return worst;
	}

	/**
	 * Returns how far the tallest bar moved between two histograms of the same catalogue, as a fraction of the
	 * catalogue's own value range. Peak *position* is what distinguishes a density pooled over the whole axis
	 * from one derived per bucket: the former barely moves when a plateau is redistributed over neighbouring
	 * values, the latter relocates the maximum entirely.
	 *
	 * @param catalogue the source catalogue, used for the value range the shift is expressed in
	 * @param first     histogram before the perturbation
	 * @param second    histogram after the perturbation
	 * @return absolute peak shift divided by the value range
	 */
	private static double relativePeakShift(
		@Nonnull int[][] catalogue, @Nonnull CacheableBucket[] first, @Nonnull CacheableBucket[] second
	) {
		final double range = (double) catalogue[catalogue.length - 1][0] - catalogue[0][0];
		assertTrue(range > 0.0, "A catalogue with no value range cannot express a peak shift");
		return Math.abs(peakThreshold(first) - peakThreshold(second)) / range;
	}

	/**
	 * Returns the threshold of the bucket rendering tallest.
	 *
	 * @param histogram histogram to inspect
	 * @return threshold of the tallest bucket
	 */
	private static double peakThreshold(@Nonnull CacheableBucket[] histogram) {
		return histogram[indexOfMaximumRelativeFrequency(histogram)].threshold().doubleValue();
	}

	/**
	 * Returns the index of the bucket with the largest relative frequency.
	 *
	 * @param histogram histogram to inspect
	 * @return index of the tallest bucket
	 */
	private static int indexOfMaximumRelativeFrequency(@Nonnull CacheableBucket[] histogram) {
		int best = 0;
		for (int i = 1; i < histogram.length; i++) {
			if (histogram[i].relativeFrequency().compareTo(histogram[best].relativeFrequency()) > 0) {
				best = i;
			}
		}
		return best;
	}

	/**
	 * Returns the index of the bucket with the smallest relative frequency.
	 *
	 * @param histogram histogram to inspect
	 * @return index of the shortest bucket
	 */
	private static int indexOfMinimumRelativeFrequency(@Nonnull CacheableBucket[] histogram) {
		int worst = 0;
		for (int i = 1; i < histogram.length; i++) {
			if (histogram[i].relativeFrequency().compareTo(histogram[worst].relativeFrequency()) < 0) {
				worst = i;
			}
		}
		return worst;
	}

	/**
	 * Locates the distinct value a published threshold was produced from.
	 *
	 * @param values    distinct values in ascending order
	 * @param threshold published bucket threshold
	 * @return index of the matching distinct value
	 */
	private static int indexOfThreshold(@Nonnull int[] values, @Nonnull BigDecimal threshold) {
		final int index = Arrays.binarySearch(values, threshold.intValueExact());
		assertTrue(index >= 0, "Threshold " + threshold + " is not one of the catalogue's own values");
		return index;
	}

	/**
	 * Parses a run-length encoded catalogue of the form `value:weight,value:weight,...`.
	 *
	 * @param runLengthEncoded encoded catalogue
	 * @return ascending array of `{value, weight}` pairs
	 */
	@Nonnull
	private static int[][] parse(@Nonnull String runLengthEncoded) {
		final String[] pairs = runLengthEncoded.split(",");
		final int[][] result = new int[pairs.length][];
		for (int i = 0; i < pairs.length; i++) {
			final int colon = pairs[i].indexOf(':');
			result[i] = new int[]{
				Integer.parseInt(pairs[i].substring(0, colon)),
				Integer.parseInt(pairs[i].substring(colon + 1))
			};
		}
		return result;
	}

	/**
	 * Creates a cruncher over `{value, weight}` pairs with integral thresholds.
	 *
	 * @param bucketCount requested bucket count
	 * @param catalogue   ascending `{value, weight}` pairs
	 * @return configured cruncher
	 */
	@Nonnull
	private static EqualizedHistogramDataCruncher<int[]> cruncher(int bucketCount, @Nonnull int[][] catalogue) {
		return new EqualizedHistogramDataCruncher<>(
			"test histogram", bucketCount, 0, catalogue,
			pair -> pair[0], pair -> pair[1],
			BigDecimal::valueOf
		);
	}

}
