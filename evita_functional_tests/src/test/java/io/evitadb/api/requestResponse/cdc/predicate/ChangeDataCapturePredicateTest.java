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

package io.evitadb.api.requestResponse.cdc.predicate;

import com.github.javafaker.Faker;
import io.evitadb.api.AbstractHundredProductsFunctionalTest;
import io.evitadb.api.requestResponse.cdc.CaptureArea;
import io.evitadb.api.requestResponse.cdc.ChangeCaptureContent;
import io.evitadb.api.requestResponse.cdc.ChangeCatalogCapture;
import io.evitadb.api.requestResponse.cdc.ChangeCatalogCaptureCriteria;
import io.evitadb.api.requestResponse.cdc.ChangeCatalogCaptureRequest;
import io.evitadb.api.requestResponse.cdc.DataSite;
import io.evitadb.api.requestResponse.cdc.Operation;
import io.evitadb.api.requestResponse.cdc.SchemaSite;
import io.evitadb.api.requestResponse.data.EntityEditor.EntityBuilder;
import io.evitadb.api.requestResponse.data.SealedEntity;
import io.evitadb.api.requestResponse.data.mutation.EntityRemoveMutation;
import io.evitadb.api.requestResponse.data.mutation.EntityUpsertMutation;
import io.evitadb.api.requestResponse.data.mutation.attribute.ApplyDeltaAttributeMutation;
import io.evitadb.api.requestResponse.data.structure.ExistingEntityBuilder;
import io.evitadb.api.requestResponse.mutation.CatalogBoundMutation;
import io.evitadb.api.requestResponse.mutation.Mutation;
import io.evitadb.api.requestResponse.mutation.MutationPredicate;
import io.evitadb.api.requestResponse.transaction.TransactionMutation;
import io.evitadb.core.Evita;
import io.evitadb.core.cdc.predicate.MutationPredicateFactory;
import io.evitadb.test.Entities;
import io.evitadb.test.annotation.DataSet;
import io.evitadb.test.annotation.UseDataSet;
import io.evitadb.test.extension.DataCarrier;
import io.evitadb.test.extension.EvitaParameterResolver;
import io.evitadb.test.generator.DataGenerator;
import io.evitadb.test.generator.DataGenerator.ModificationFunction;
import io.evitadb.utils.UUIDUtil;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.function.BiFunction;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static io.evitadb.test.TestConstants.FUNCTIONAL_TEST;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * This test verifies contract of {@link ExistingEntityBuilder}.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
@DisplayName("Change data capture predicate test")
@Tag(FUNCTIONAL_TEST)
@ExtendWith(EvitaParameterResolver.class)
@Slf4j
class ChangeDataCapturePredicateTest extends AbstractHundredProductsFunctionalTest {
	private static final String HUNDRED_PRODUCTS = "HundredProductsForCapture";
	private List<CatalogBoundMutation> mutations;

	@DataSet(value = HUNDRED_PRODUCTS, destroyAfterClass = true)
	@Override
	protected DataCarrier setUp(Evita evita) {
		final DataCarrier dataCarrier = super.setUp(evita);
		final List<SealedEntity> products = (List<SealedEntity>) dataCarrier.getValueByName("originalProducts");
		final List<SealedEntity> brands = (List<SealedEntity>) dataCarrier.getValueByName("originalBrands");
		final List<SealedEntity> parameters = (List<SealedEntity>) dataCarrier.getValueByName("originalParameters");
		final List<SealedEntity> stores = (List<SealedEntity>) dataCarrier.getValueByName("originalStores");
		final Collection<SealedEntity> categories = ((Map<Integer, SealedEntity>) dataCarrier.getValueByName("originalCategories")).values();

		final Map<String, List<SealedEntity>> generatedEntities = Stream.of(
			products.stream(),
			brands.stream(),
			parameters.stream(),
			stores.stream(),
			categories.stream()
		)
			.flatMap(it -> it)
			.collect(Collectors.groupingBy(it -> it.getSchema().getName()));

		final Set<Integer> removedPks = new HashSet<>();
		final BiFunction<String, Faker, Integer> randomEntityPicker = (entityType, faker) -> {
			final int entityCount = generatedEntities.getOrDefault(entityType, Collections.emptyList()).size();
			int primaryKey;
			do {
				primaryKey = entityCount == 0 ? 0 : faker.random().nextInt(1, entityCount);
			} while (Entities.PRODUCT.equals(entityType) && removedPks.contains(primaryKey));
			return primaryKey == 0 ? null : primaryKey;
		};

		final Random rnd = new Random(1);
		final ModificationFunction modificationFunction = this.dataGenerator.createModificationFunction(
			randomEntityPicker, rnd
		);

		// generate random transactions
		final OffsetDateTime base = OffsetDateTime.now().minusYears(1);
		this.mutations = new ArrayList<>(2048);
		for (int i = 0; i < 5; i++) {
			final int updatesInTransaction = rnd.nextInt(5);
			this.mutations.add(new TransactionMutation(UUIDUtil.randomUUID(), i + 1, updatesInTransaction, rnd.nextLong(), base.plusSeconds(i)));
			final Set<Integer> updatedInThisTransaction = new HashSet<>();
			for (int j = 0; j < updatesInTransaction; j++) {
				if (rnd.nextInt(3) == 0) {
					int removedPk;
					do {
						removedPk = rnd.nextInt(products.size());
					} while (removedPks.contains(removedPk));
					removedPks.add(removedPk);
					this.mutations.add(new EntityRemoveMutation(Entities.PRODUCT, removedPk));
				} else {
					int updatedPk;
					do {
						updatedPk = rnd.nextInt(products.size());
					} while (removedPks.contains(updatedPk) || updatedInThisTransaction.contains(updatedPk));

					updatedInThisTransaction.add(updatedPk);

					final EntityBuilder baseMutation = modificationFunction.apply(products.get(updatedPk));

					if (rnd.nextInt(7) == 0) {
						((ExistingEntityBuilder)baseMutation).addMutation(
							new ApplyDeltaAttributeMutation<>(DataGenerator.ATTRIBUTE_QUANTITY, BigDecimal.ONE)
						);
					}

					this.mutations.add(baseMutation.toMutation().orElseThrow());
				}
			}
		}

		return dataCarrier;
	}

	@UseDataSet(HUNDRED_PRODUCTS)
	@Test
	void shouldMaintainBaseMutationPremises(Evita evita) {
		final MutationPredicate forwardCatchAllPredicate = MutationPredicateFactory.createChangeCatalogCapturePredicate(
			ChangeCatalogCaptureRequest.builder()
				.criteria(
					ChangeCatalogCaptureCriteria.builder()
						.area(CaptureArea.DATA)
						.site(DataSite.ALL)
						.build(),
					ChangeCatalogCaptureCriteria.builder()
						.area(CaptureArea.SCHEMA)
						.site(SchemaSite.ALL)
						.build(),
					ChangeCatalogCaptureCriteria.builder()
						.area(CaptureArea.INFRASTRUCTURE)
						.build()
				)
				.content(ChangeCaptureContent.BODY)
				.build()
		);
		final List<ChangeCatalogCapture> cdc = this.mutations.stream()
			.flatMap(it -> it.toChangeCatalogCapture(forwardCatchAllPredicate, ChangeCaptureContent.BODY))
			.toList();

		int expectedVersion = 0;
		int countdown = 0;
		int mutationsInTransaction = 0;
		for (ChangeCatalogCapture cdcItem : cdc) {
			assertInstanceOf(ChangeCatalogCapture.class, cdcItem, "Change catalog capture item should be of type ChangeCatalogCapture");
			assertTrue(countdown >= 0, "Countdown should not be negative");
			if (cdcItem.body() instanceof TransactionMutation transactionMutation) {
				mutationsInTransaction = transactionMutation.getMutationCount();
				countdown = mutationsInTransaction;
				expectedVersion++;
			} else {
				assertEquals(Entities.PRODUCT, cdcItem.entityType(), "Entity type should be 'product'");
				if (cdcItem.body() instanceof EntityUpsertMutation || cdcItem.body() instanceof EntityRemoveMutation) {
					countdown--;
				}
			}
			assertEquals(expectedVersion, cdcItem.version(), "Version should be monotonically increasing.");
			assertTrue(cdcItem.area() == CaptureArea.DATA || cdcItem.area() == CaptureArea.INFRASTRUCTURE, "Area should be DATA or INFRASTRUCTURE");
			assertNotNull(cdcItem.body());
			assertEquals(mutationsInTransaction - countdown, cdcItem.index(), "Index should be monotonically increasing.");
			assertNotNull(cdcItem.operation());
		}

		// now compute the same in reverse fashion, and it must be exactly the same as the original
		final MutationPredicate reverseCatchAllPredicate = MutationPredicateFactory.createReversedChangeCatalogCapturePredicate(
			ChangeCatalogCaptureRequest.builder()
				.criteria(
					ChangeCatalogCaptureCriteria.builder()
						.area(CaptureArea.DATA)
						.site(DataSite.ALL)
						.build(),
					ChangeCatalogCaptureCriteria.builder()
						.area(CaptureArea.SCHEMA)
						.site(SchemaSite.ALL)
						.build(),
					ChangeCatalogCaptureCriteria.builder()
						.area(CaptureArea.INFRASTRUCTURE)
						.build()
				)
				.content(ChangeCaptureContent.BODY)
				.build()
		);

		/* we need somehow to initialize mutation count eagerly?! */
		final CatalogBoundMutation[] reversedMutations = new CatalogBoundMutation[this.mutations.size()];
		int reverseIndex = this.mutations.size() - 1;
		for (int i = 0; i < this.mutations.size(); i++) {
			final Mutation mutation = this.mutations.get(i);
			assertInstanceOf(TransactionMutation.class, mutation, "Mutation should be of type TransactionMutation");
			final TransactionMutation transactionMutation = (TransactionMutation) mutation;
			for (int j = i + 1; j <= i + transactionMutation.getMutationCount(); j++) {
				reversedMutations[reverseIndex--] = this.mutations.get(j);
			}
			reversedMutations[reverseIndex--] = transactionMutation;
			i += transactionMutation.getMutationCount();
		}
		final List<ChangeCatalogCapture> reversedCdc = Arrays.stream(reversedMutations)
			.flatMap(it -> it.toChangeCatalogCapture(reverseCatchAllPredicate, ChangeCaptureContent.BODY))
			.toList();

		assertEquals(cdc.size(), reversedCdc.size());
		// compare mutations ignoring the transaction mutations
		int expectedIndex = cdc.size() - 1;
		for (int i = 0; i < cdc.size(); i++) {
			ChangeCatalogCapture expected = null;
			while (expectedIndex >= 0 && (expected = cdc.get(expectedIndex--)).operation() == Operation.TRANSACTION) {

			}
			ChangeCatalogCapture actual = null;
			while (i < reversedCdc.size() && (actual = reversedCdc.get(i)).operation() == Operation.TRANSACTION) {
				i++;
			}
			assertEquals(expected, actual);
		}
	}
}
