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
import io.evitadb.api.requestResponse.data.structure.Price.PriceKey;
import io.evitadb.core.catalog.Catalog;
import io.evitadb.dataType.Scope;
import io.evitadb.index.EntityIndexKey;
import io.evitadb.index.EntityIndexType;
import io.evitadb.index.GlobalEntityIndex;
import io.evitadb.index.price.model.PriceIndexKey;
import io.evitadb.test.duration.TimeArgumentProvider;
import io.evitadb.test.duration.TimeArgumentProvider.GenerationalTestInput;
import io.evitadb.test.duration.TimeBoundedTestSupport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ArgumentsSource;
import org.mockito.ArgumentMatchers;
import org.mockito.Mockito;

import javax.annotation.Nonnull;
import java.util.ArrayList;
import java.util.Currency;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static io.evitadb.test.TestTags.INDEXING;
import static io.evitadb.test.TestTags.PRICE;
import static io.evitadb.test.TestTags.SLOW;
import static io.evitadb.utils.AssertionUtils.assertStateAfterCommit;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Long-running generational randomized proof test for {@link PriceRefIndex}.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
@DisplayName("PriceRefIndex generational proof")
@Tag(INDEXING)
@Tag(PRICE)
class LongRunningPriceRefIndexTest implements TimeBoundedTestSupport {

	private static final String ENTITY_TYPE = "product";
	private static final Scope SCOPE = Scope.LIVE;
	private static final Currency CURRENCY_CZK = Currency.getInstance("CZK");
	private static final Currency CURRENCY_EUR = Currency.getInstance("EUR");
	private static final String PRICE_LIST_BASIC = "basic";
	private static final String PRICE_LIST_VIP = "vip";
	private static final PriceIndexKey KEY_BASIC_CZK = new PriceIndexKey(
		PRICE_LIST_BASIC, CURRENCY_CZK, PriceInnerRecordHandling.NONE
	);
	private static final PriceIndexKey KEY_VIP_CZK = new PriceIndexKey(
		PRICE_LIST_VIP, CURRENCY_CZK, PriceInnerRecordHandling.NONE
	);
	private static final PriceIndexKey KEY_BASIC_EUR = new PriceIndexKey(
		PRICE_LIST_BASIC, CURRENCY_EUR, PriceInnerRecordHandling.NONE
	);

	@Tag(SLOW)
	@DisplayName("generational proof test with random add/remove operations")
	@ParameterizedTest(name = "generational proof test with seed {0}")
	@ArgumentsSource(TimeArgumentProvider.class)
	void generationalProofTest(@Nonnull GenerationalTestInput input) {
		final AtomicInteger globalInternalPriceId = new AtomicInteger(0);

		// keys used for random operations
		final PriceIndexKey[] keys = {KEY_BASIC_CZK, KEY_VIP_CZK, KEY_BASIC_EUR};

		runFor(
			input, 1_000,
			new TestState(
				new StringBuilder(8192),
				new HashMap<>(8)
			),
			(random, testState) -> {
				final StringBuilder codeBuffer = testState.code();
				codeBuffer.setLength(0);

				// rebuild a fresh super index from the tracked state each iteration
				final PriceSuperIndex superIndex = new PriceSuperIndex();
				final Map<PriceIndexKey, Set<Integer>> currentState = testState.trackedPricesByKey();

				// populate the super index from state
				for (final Map.Entry<PriceIndexKey, Set<Integer>> entry : currentState.entrySet()) {
					for (final Integer ipId : entry.getValue()) {
						// use ipId as both entityPK and priceId for simplicity
						superIndex.addPrice(
							null, ipId, ipId,
							new PriceKey(ipId, entry.getKey().getPriceList(), entry.getKey().getCurrency()),
							entry.getKey().getRecordHandling(),
							null, null, 10000, 12100
						);
					}
				}

				// build a fresh PriceRefIndex and attach it
				final PriceRefIndex priceRefIndex = new PriceRefIndex(SCOPE);
				final GlobalEntityIndex mockGlobalIndex = Mockito.mock(GlobalEntityIndex.class);
				Mockito.when(mockGlobalIndex.getPriceIndex(ArgumentMatchers.any(PriceIndexKey.class)))
					.thenAnswer(inv -> superIndex.getPriceIndex(inv.getArgument(0)));
				final Catalog mockCatalog = Mockito.mock(Catalog.class);
				Mockito.when(mockCatalog.getEntityIndexIfExists(
					ArgumentMatchers.eq(ENTITY_TYPE),
					ArgumentMatchers.eq(new EntityIndexKey(EntityIndexType.GLOBAL, SCOPE)),
					ArgumentMatchers.eq(GlobalEntityIndex.class)
				)).thenReturn(Optional.of(mockGlobalIndex));
				priceRefIndex.attachToCatalog(ENTITY_TYPE, mockCatalog);

				// populate the ref index from state
				for (final Map.Entry<PriceIndexKey, Set<Integer>> entry : currentState.entrySet()) {
					for (final Integer ipId : entry.getValue()) {
						priceRefIndex.addPrice(
							null, ipId, ipId,
							new PriceKey(ipId, entry.getKey().getPriceList(), entry.getKey().getCurrency()),
							entry.getKey().getRecordHandling(),
							null, null, 10000, 12100
						);
					}
				}

				// plan random operations
				final Map<PriceIndexKey, Set<Integer>> nextState = new HashMap<>(8);
				for (final Map.Entry<PriceIndexKey, Set<Integer>> entry : currentState.entrySet()) {
					nextState.put(entry.getKey(), new HashSet<>(entry.getValue()));
				}

				// collect planned operations: {ipId, priceId, entityPK, keyIdx} for adds,
				// {ipId, keyIdx} for removes
				final List<int[]> addOps = new ArrayList<>(8);
				final List<int[]> removeOps = new ArrayList<>(8);

				final int opCount = 1 + random.nextInt(5);
				for (int i = 0; i < opCount; i++) {
					final int keyIdx = random.nextInt(keys.length);
					final PriceIndexKey selectedKey = keys[keyIdx];
					final Set<Integer> pricesForKey =
						nextState.computeIfAbsent(selectedKey, k -> new HashSet<>(4));

					if (pricesForKey.isEmpty() || random.nextBoolean()) {
						// plan an add -- use ipId as entityPK and priceId for consistency
						final int ipId = globalInternalPriceId.incrementAndGet();

						codeBuffer.append("ADD: ipId=").append(ipId)
							.append(" key=").append(selectedKey).append('\n');

						addOps.add(new int[]{ipId, keyIdx});
						pricesForKey.add(ipId);
					} else {
						// plan a remove
						final Integer ipIdToRemove = pricesForKey.iterator().next();

						codeBuffer.append("REMOVE: ipId=").append(ipIdToRemove)
							.append(" key=").append(selectedKey).append('\n');

						removeOps.add(new int[]{ipIdToRemove, keyIdx});
						pricesForKey.remove(ipIdToRemove);
						if (pricesForKey.isEmpty()) {
							nextState.remove(selectedKey);
						}
					}
				}

				// execute super index additions OUTSIDE the transaction
				for (final int[] op : addOps) {
					final PriceIndexKey key = keys[op[1]];
					superIndex.addPrice(
						null, op[0], op[0],
						new PriceKey(op[0], key.getPriceList(), key.getCurrency()),
						key.getRecordHandling(),
						null, null, 10000, 12100
					);
				}

				try {
					assertStateAfterCommit(
						priceRefIndex,
						original -> {
							// additions inside transaction
							for (final int[] op : addOps) {
								final PriceIndexKey key = keys[op[1]];
								original.addPrice(
									null, op[0], op[0],
									new PriceKey(op[0], key.getPriceList(), key.getCurrency()),
									key.getRecordHandling(),
									null, null, 10000, 12100
								);
							}
							// removals inside transaction
							for (final int[] op : removeOps) {
								final PriceIndexKey key = keys[op[1]];
								original.priceRemove(
									null, op[0], op[0],
									new PriceKey(op[0], key.getPriceList(), key.getCurrency()),
									key.getRecordHandling(),
									null, null, 10000, 12100
								);
							}
						},
						(original, committed) -> {
							for (final PriceIndexKey key : keys) {
								final Set<Integer> expectedPrices =
									nextState.getOrDefault(key, Set.of());
								final PriceListAndCurrencyPriceRefIndex childIndex =
									committed.getPriceIndex(key);

								if (expectedPrices.isEmpty()) {
									assertNull(
										childIndex,
										"Expected no child for " + key +
											" but found one.\n" + codeBuffer
									);
								} else {
									assertNotNull(
										childIndex,
										"Expected child for " + key +
											" but found none.\n" + codeBuffer
									);
									assertFalse(
										childIndex.isEmpty(),
										"Child for " + key +
											" is empty but should not be.\n" + codeBuffer
									);
								}
							}
						}
					);
				} catch (Exception ex) {
					fail(ex.getMessage() + "\n" + codeBuffer, ex);
				}

				return new TestState(
					new StringBuilder(8192),
					nextState
				);
			}
		);
	}

	/**
	 * State carried across generational test iterations.
	 *
	 * @param code              StringBuilder for debugging output on failure
	 * @param trackedPricesByKey mapping from `PriceIndexKey` to set of internal price ids
	 *                          currently in the ref index
	 */
	private record TestState(
		@Nonnull StringBuilder code,
		@Nonnull Map<PriceIndexKey, Set<Integer>> trackedPricesByKey
	) {
	}

}
