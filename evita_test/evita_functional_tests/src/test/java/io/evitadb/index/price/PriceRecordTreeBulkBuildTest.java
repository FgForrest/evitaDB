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

package io.evitadb.index.price;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.evitadb.api.requestResponse.data.PriceInnerRecordHandling;
import io.evitadb.core.transaction.Transaction;
import io.evitadb.index.bPlusTree.TransactionalElementBPlusTree;
import io.evitadb.index.price.model.PriceIndexKey;
import io.evitadb.index.price.model.priceRecord.PriceRecord;
import io.evitadb.index.price.model.priceRecord.PriceRecordContract;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.LoggerFactory;

import javax.annotation.Nonnull;
import java.util.Currency;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static io.evitadb.test.TestTags.INDEXING;
import static io.evitadb.test.TestTags.PRICE;
import static io.evitadb.test.TestTags.TRANSACTION;
import static io.evitadb.utils.AssertionUtils.assertStateAfterRollback;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Verifies the production price-tree factory's ordering fallback and bound-transaction insert path.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@DisplayName("Price record tree bulk construction and insert fallback")
@Tag(INDEXING)
@Tag(PRICE)
@Tag(TRANSACTION)
class PriceRecordTreeBulkBuildTest {
	private static final PriceIndexKey INDEX_KEY = new PriceIndexKey(
		"basic", Currency.getInstance("EUR"), PriceInnerRecordHandling.NONE
	);

	/**
	 * A 129-record input distinguishes three bulk-built leaves from the four leaves built by ascending inserts.
	 */
	@Test
	@DisplayName("ascending records outside a transaction use the bulk builder")
	void shouldBulkBuildAscendingInput() {
		final PriceRecordContract[] input = records(129);
		final LoggedBuild build = buildLogged(input);
		assertArrayEquals(input, build.tree().toArray());
		assertEquals(3, build.tree().leafPageHandles().size(), "Ascending input must use the full-leaf builder.");
		assertTrue(build.events().isEmpty(), () -> "Ascending input must log nothing: " + build.events());
	}

	/**
	 * Both a descending pair and a duplicate must preserve insert semantics and issue exactly one diagnostic.
	 */
	@ParameterizedTest
	@ValueSource(booleans = {false, true})
	@DisplayName("non-ascending input takes the correct insert fallback and logs one ERROR")
	void shouldFallBackToInsertsAndLogFirstOffendingPair(boolean duplicate) {
		final PriceRecordContract[] input = records(129);
		final int previousId = input[63].internalPriceId();
		final int currentId = duplicate ? previousId : previousId - 1;
		input[64] = new PriceRecord(currentId, 17, 29, 900, 800);
		input[100] = input[2];
		final TransactionalElementBPlusTree<PriceRecordContract> reference = insertBuilt(input);
		final LoggedBuild build = buildLogged(input);
		final TransactionalElementBPlusTree<PriceRecordContract> actual = build.tree();
		assertArrayEquals(reference.toArray(), actual.toArray(), "The fallback must preserve insert semantics.");
		assertEquals(reference.size(), actual.size());
		assertEquals(reference.toString(), actual.toString(), "The fallback must retain the insert-built shape.");
		assertEquals(input[64], actual.search(currentId), "A duplicate must retain its last record body.");
		assertEquals(1, build.events().size(), "Ordering violations must be logged once per tree build.");
		final ILoggingEvent event = build.events().getFirst();
		assertEquals(Level.ERROR, event.getLevel());
		final String message = event.getFormattedMessage();
		assertTrue(message.contains(INDEX_KEY.toString()), message);
		assertTrue(message.contains("position 64"), message);
		assertTrue(message.contains(previousId + " followed by " + currentId), message);
		assertTrue(message.contains("built by inserts and is correct"), message);
		assertFalse(message.contains("damaged"), message);
	}

	/**
	 * A factory invoked with a bound transaction must populate transactional root, size and leaf state via inserts.
	 */
	@Test
	@DisplayName("a bound transaction uses inserts and exposes an empty base after abort")
	void shouldUseInsertPathWithBoundTransaction() {
		final PriceRecordContract[] input = records(129);
		final AtomicReference<TransactionalElementBPlusTree<PriceRecordContract>> loaded = new AtomicReference<>();
		assertStateAfterRollback(
			new TransactionalElementBPlusTree<>(PriceRecordContract.class, PriceRecordContract::internalPriceId),
			owner -> {
				loaded.set(AbstractPriceListAndCurrencyPriceIndex.newPriceRecordTree(input, INDEX_KEY));
				assertArrayEquals(input, loaded.get().toArray());
				assertEquals(4, loaded.get().leafPageHandles().size(), "Bound input must retain insert-built leaves.");
				final Transaction transaction = Transaction.getTransaction().orElseThrow();
				transaction.unbindTransactionFromThread();
				try {
					assertEquals(0, loaded.get().size(), "Insert-built state must be isolated from the base.");
					assertEquals(0, loaded.get().toArray().length);
				} finally {
					transaction.bindTransactionToThread();
				}
			},
			(owner, discarded) -> assertEquals(0, loaded.get().size())
		);
	}

	/**
	 * Produces spaced ascending internal price ids with distinct record bodies.
	 */
	@Nonnull
	private static PriceRecordContract[] records(int count) {
		final PriceRecordContract[] result = new PriceRecordContract[count];
		for (int i = 0; i < count; i++) {
			final int id = i * 3 + 3;
			result[i] = new PriceRecord(id, id + 1, id / 3, id * 100 + 21, id * 100);
		}
		return result;
	}

	/**
	 * Replays the insert loop as a differential reference.
	 */
	@Nonnull
	private static TransactionalElementBPlusTree<PriceRecordContract> insertBuilt(
		@Nonnull PriceRecordContract[] input
	) {
		final TransactionalElementBPlusTree<PriceRecordContract> tree =
			new TransactionalElementBPlusTree<>(PriceRecordContract.class, PriceRecordContract::internalPriceId);
		for (final PriceRecordContract record : input) {
			tree.insert(record);
		}
		return tree;
	}

	/**
	 * Calls the production factory with an appender attached to its logger, detaching it even when the build fails.
	 */
	@Nonnull
	private static LoggedBuild buildLogged(@Nonnull PriceRecordContract[] input) {
		final Logger logger = (Logger) LoggerFactory.getLogger(AbstractPriceListAndCurrencyPriceIndex.class);
		final ListAppender<ILoggingEvent> appender = new ListAppender<>();
		appender.start();
		logger.addAppender(appender);
		try {
			return new LoggedBuild(
				AbstractPriceListAndCurrencyPriceIndex.newPriceRecordTree(input, INDEX_KEY), appender.list
			);
		} finally {
			logger.detachAppender(appender);
			appender.stop();
		}
	}

	/**
	 * A factory-built tree together with every event its build logged.
	 *
	 * @param tree   the tree the factory returned
	 * @param events the events logged by the factory during the build
	 */
	private record LoggedBuild(
		@Nonnull TransactionalElementBPlusTree<PriceRecordContract> tree,
		@Nonnull List<ILoggingEvent> events
	) {
	}
}
