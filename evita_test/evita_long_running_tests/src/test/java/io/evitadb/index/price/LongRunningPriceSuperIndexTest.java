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

package io.evitadb.index.price;

import io.evitadb.api.requestResponse.data.PriceInnerRecordHandling;
import io.evitadb.dataType.DateTimeRange;
import io.evitadb.index.price.model.PriceIndexKey;
import io.evitadb.index.price.model.priceRecord.PriceRecordContract;
import io.evitadb.index.price.model.priceRecord.PriceRecordInnerRecordSpecific;
import io.evitadb.index.range.RangeIndex;
import io.evitadb.test.duration.TimeArgumentProvider;
import io.evitadb.test.duration.TimeArgumentProvider.GenerationalTestInput;
import io.evitadb.test.duration.TimeBoundedTestSupport;
import io.evitadb.utils.ArrayUtils;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ArgumentsSource;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Currency;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

import static io.evitadb.test.TestTags.INDEXING;
import static io.evitadb.test.TestTags.PRICE;
import static io.evitadb.test.TestTags.SLOW;
import static io.evitadb.utils.AssertionUtils.assertStateAfterCommit;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Long-running generational randomized proof test for {@link PriceSuperIndex}.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
@DisplayName("PriceSuperIndex generational proof")
@Tag(INDEXING)
@Tag(PRICE)
class LongRunningPriceSuperIndexTest implements TimeBoundedTestSupport {
	private static final Currency CURRENCY_CZK = Currency.getInstance("CZK");
	private static final String PRICE_LIST = "basic";
	private final AtomicInteger priceIdSequence = new AtomicInteger(0);

	@ParameterizedTest(name = "PriceSuperIndex should survive generational randomized test applying modifications on it")
	@Tag(SLOW)
	@ArgumentsSource(TimeArgumentProvider.class)
	void generationalProofTest(GenerationalTestInput input) {
		final int maxPrices = 50;
		final PriceIndexKey key = new PriceIndexKey(PRICE_LIST, CURRENCY_CZK, PriceInnerRecordHandling.NONE);

		runFor(
			input,
			1_000,
			new TestState(
				new StringBuilder(),
				new PriceRecordWithValidity[0]
			),
			(random, testState) -> {
				final PriceRecordWithValidity[] initialRecords = testState.initialState();
				final PriceRecordContract[] priceRecords = buildPriceRecordsFrom(initialRecords);
				final RangeIndex validityIndex = buildValidityIndexFrom(initialRecords);
				final PriceListAndCurrencyPriceSuperIndex priceSuperIndex = new PriceListAndCurrencyPriceSuperIndex(key, validityIndex, priceRecords);

				final AtomicReference<PriceRecordWithValidity[]> nextArrayToCompare = new AtomicReference<>(testState.initialState());

				final StringBuilder codeBuffer = testState.code();
				codeBuffer.append("final PriceListAndCurrencyPriceSuperIndex priceIndex = new PriceListAndCurrencyPriceSuperIndex(new PriceIndexKey(PRICE_LIST, CURRENCY_CZK, PriceInnerRecordHandling.NONE));\n")
					.append(Arrays.stream(initialRecords)
						.map(it ->
							"priceIndex.addPrice(" +
								it.entityPrimaryKey() + "," +
								it.priceId() + "," +
								it.innerRecordId() + "," +
								"DateTimeRange.between(OffsetDateTime.now().minusMinutes(" + it.differenceInMinutes() + "), OffsetDateTime.now().plusMinutes(" + it.differenceInMinutes() + "))," +
								it.priceWithoutTax() + "," +
								it.priceWithTax() +
								");"
						)
						.collect(Collectors.joining("\n")));
				codeBuffer.append("\nOps:\n");

				assertStateAfterCommit(
					priceSuperIndex,
					original -> {
						final int operationsInTransaction = random.nextInt(10);
						final Set<Integer> addedInThisRound = new HashSet<>();
						final List<PriceRecordWithValidity> addedRecordInThisRound = new ArrayList<>();
						final Set<Integer> removedInThisRound = new HashSet<>();
						for (int i = 0; i < operationsInTransaction; i++) {
							final int length = priceSuperIndex.getPriceRecords().length;
							if (length < maxPrices && random.nextBoolean() || length < 10) {
								// insert new item
								int newPriceId;
								do {
									newPriceId = random.nextInt(maxPrices * 2);
								} while (addedInThisRound.contains(newPriceId) || ArrayUtils.binarySearch(nextArrayToCompare.get(), newPriceId, (priceRecordWithValidity, pid) -> Integer.compare(priceRecordWithValidity.priceId(), pid)) >= 0);

								final int newEntityId = random.nextInt(maxPrices / 4);
								final int newInnerRecordId = random.nextInt(maxPrices / 2);
								final int randomPriceWithoutTax = random.nextInt(1000);
								final int randomPriceWithTax = (int) (randomPriceWithoutTax * 1.21);
								final int differenceInMinutes = random.nextInt(10_000);
								final OffsetDateTime from = OffsetDateTime.now().minusMinutes(differenceInMinutes);
								final DateTimeRange validity = DateTimeRange.between(from, from.plusMinutes(differenceInMinutes));

								final int internalPriceId = LongRunningPriceSuperIndexTest.this.priceIdSequence.incrementAndGet();
								final PriceRecordWithValidity priceRecord = new PriceRecordWithValidity(
									internalPriceId, newPriceId, newEntityId, newInnerRecordId,
									randomPriceWithTax, randomPriceWithoutTax, differenceInMinutes, validity
								);

								codeBuffer.append("priceIndex.addPrice(")
									.append(newEntityId).append(",")
									.append(newPriceId).append(",")
									.append(newInnerRecordId).append(",")
									.append("DateTimeRange.between(OffsetDateTime.now().minusMinutes(").append(differenceInMinutes).append("), OffsetDateTime.now().plusMinutes(").append(differenceInMinutes).append(")),")
									.append(randomPriceWithoutTax).append(",")
									.append(randomPriceWithTax)
									.append(");\n");

								try {
									priceSuperIndex.addPrice(
										new PriceRecordInnerRecordSpecific(internalPriceId, newPriceId, newEntityId, newInnerRecordId, randomPriceWithTax, randomPriceWithoutTax),
										validity
									);
									nextArrayToCompare.set(ArrayUtils.insertRecordIntoOrderedArray(priceRecord, nextArrayToCompare.get(), Comparator.comparingInt(PriceRecordWithValidity::priceId)));
									addedInThisRound.add(newPriceId);
									addedRecordInThisRound.add(priceRecord);
									removedInThisRound.remove(newPriceId);
								} catch (Exception ex) {
									fail(ex.getMessage() + "\n" + codeBuffer, ex);
								}

							} else {
								// remove existing item
								PriceRecordWithValidity recordToRemove;
								do {
									if (addedInThisRound.isEmpty() || random.nextInt(5) == 0) {
										recordToRemove = nextArrayToCompare.get()[random.nextInt(nextArrayToCompare.get().length)];
									} else {
										recordToRemove = addedRecordInThisRound.get(random.nextInt(addedRecordInThisRound.size()));
									}
								} while (removedInThisRound.contains(recordToRemove.priceId()));

								codeBuffer.append("priceIndex.removePrice(")
									.append(recordToRemove.entityPrimaryKey()).append(",")
									.append(recordToRemove.priceId()).append(",")
									.append("DateTimeRange.between(OffsetDateTime.now().minusMinutes(").append(recordToRemove.differenceInMinutes()).append("), OffsetDateTime.now().plusMinutes(").append(recordToRemove.differenceInMinutes()).append("))")
									.append(");\n");

								try {
									priceSuperIndex.removePrice(
										recordToRemove.entityPrimaryKey(),
										recordToRemove.internalPriceId(),
										recordToRemove.validity()
									);
									nextArrayToCompare.set(ArrayUtils.removeRecordFromOrderedArray(recordToRemove, nextArrayToCompare.get()));
									if (addedInThisRound.remove(recordToRemove.priceId())) {
										addedRecordInThisRound.remove(recordToRemove);
									} else {
										removedInThisRound.add(recordToRemove.priceId());
									}
								} catch (Exception ex) {
									fail(ex.getMessage() + "\n" + codeBuffer, ex);
								}
							}
						}
					},
					(original, committed) -> {
						final PriceRecordContract[] expectedPriceRecords = buildPriceRecordsFrom(nextArrayToCompare.get());
						assertArrayEquals(
							expectedPriceRecords, committed.getPriceRecords(),
							"\nExpected: " + Arrays.toString(expectedPriceRecords) + "\n" +
								"Actual:   " + Arrays.toString(committed.getPriceRecords()) + "\n\n" +
								codeBuffer
						);
					}
				);

				return new TestState(
					new StringBuilder(),
					nextArrayToCompare.get()
				);
			}
		);
	}

	private static PriceRecordContract[] buildPriceRecordsFrom(PriceRecordWithValidity[] priceRecords) {
		final PriceRecordContract[] result = new PriceRecordContract[priceRecords.length];
		for (int i = 0; i < priceRecords.length; i++) {
			final PriceRecordWithValidity priceRecord = priceRecords[i];
			result[i] = new PriceRecordInnerRecordSpecific(
				priceRecord.internalPriceId(),
				priceRecord.priceId(),
				priceRecord.entityPrimaryKey(),
				priceRecord.innerRecordId(),
				priceRecord.priceWithTax(),
				priceRecord.priceWithoutTax()
			);
		}
		Arrays.sort(result, Comparator.comparingInt(PriceRecordContract::internalPriceId));
		return result;
	}

	private static RangeIndex buildValidityIndexFrom(PriceRecordWithValidity[] priceRecords) {
		final RangeIndex result = new RangeIndex();
		for (PriceRecordWithValidity priceRecord : priceRecords) {
			result.addRecord(
				priceRecord.validity().getFrom(),
				priceRecord.validity().getTo(),
				priceRecord.priceId()
			);
		}
		return result;
	}

	private record PriceRecordWithValidity(
		int internalPriceId,
		int priceId,
		int entityPrimaryKey,
		int innerRecordId,
		int priceWithTax,
		int priceWithoutTax,
		int differenceInMinutes,
		DateTimeRange validity
	) implements Comparable<PriceRecordWithValidity> {
		@Override
		public int compareTo(PriceRecordWithValidity o) {
			return Integer.compare(this.priceId, o.priceId);
		}

	}

	private record TestState(
		StringBuilder code,
		PriceRecordWithValidity[] initialState
	) {}

}
