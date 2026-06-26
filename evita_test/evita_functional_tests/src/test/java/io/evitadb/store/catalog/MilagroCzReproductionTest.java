/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2025-2026
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

import io.evitadb.api.query.Query;
import io.evitadb.api.query.parser.DefaultQueryParser;
import io.evitadb.api.requestResponse.EvitaResponse;
import io.evitadb.api.requestResponse.data.EntityClassifier;
import io.evitadb.driver.EvitaClient;
import io.evitadb.driver.config.ClientTlsOptions;
import io.evitadb.driver.config.EvitaClientConfiguration;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Connects to a manually-launched evitaDB server (run-server.sh) with the milagro_cz
 * production snapshot loaded and runs the queries from issue prj/p_mila.eshop#938
 * to attempt reproduction of the `Duplicate key` crash.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@Slf4j
@EnabledIfSystemProperty(named = "milagro.repro", matches = "true")
public class MilagroCzReproductionTest {
	private static final String CATALOG = "milagro_cz";

	private static EvitaClient openClient() {
		// Server runs gRPC with tlsMode=RELAXED — accepts plaintext h2c too.
		return new EvitaClient(
			EvitaClientConfiguration.builder()
				.host("localhost")
				.port(5555)
				.systemApiPort(5555)
				.tls(
					ClientTlsOptions.builder()
						.tlsEnabled(false)
						.useGeneratedCertificate(false)
						.trustCertificate(true)
						.build()
				)
				.build()
		);
	}

	@Test
	void runAllReproQueries() {
		final Map<String, String> queries = new LinkedHashMap<>();
		queries.put("Q1-group-145197-traverse-page200", """
			query(
				collection('Product'),
				filterBy(and(
					referenceHaving('groups', entityPrimaryKeyInSet(145197)),
					entityLocaleEquals('cs'),
					attributeEquals('status', 'ACTIVE')
				)),
				orderBy(
					referenceProperty('groups', attributeNatural('orderInGroup', ASC),
				traverseByEntityProperty(entityPrimaryKeyExact(145197)))
				),
				require(
					strip(0, 200),
					entityFetch( referenceContent('groups') )
				)
			)
			""");

		queries.put("Q2-segments-stocks-orderedQuantity", """
			query(
				collection('Product'),
				filterBy(
					and(
						referenceHaving('groups', and(or(and(attributeIs('assignmentValidity', NULL)),
				and(attributeInRangeNow('assignmentValidity'))), entityPrimaryKeyInSet(21798))),
						or(and(attributeIs('validity', NULL)), and(attributeInRangeNow('validity'))),
						entityLocaleEquals('cs'),
						attributeInSet('productType', 'BASIC', 'SET', 'MASTER'),
						attributeEquals('status', 'ACTIVE'),
						priceInPriceLists('jaro-leto-2026-cz', 'jaro-leto-cz-2025', 'podzim-zima-2024-cz', 'jaro-leto-2024-cz', 'basic',
				'reference', 'basic_milagro_cz', 'reference_milagro_cz', 'bf-sleva-20'),
						priceValidInNow(),
						priceInCurrency('CZK'),
						referenceHaving('stockVisibilities', entityHaving(attributeInSet('code', 'milagro_cz', '12', '1', '22', '24', '7',
				'13')))
					)
				),
				orderBy(
					segments(
						segment(
							entityHaving(referenceHaving('stocks', and(
								entityHaving(attributeInSet('code', 'milagro_cz', '12', '1', '22', '24', '7', '13')),
								attributeGreaterThan('quantityOnStock', 0)
							))),
							orderBy(attributeNatural('orderedQuantity', DESC))
						),
						segment(
							orderBy(attributeNatural('orderedQuantity', DESC))
						)
					)
				),
				require(
					strip(300, 20),
					entityFetch(
						referenceContentWithAttributes('relatedProducts', attributeContent('category')),
						referenceContent('variants', entityFetch(attributeContent('codeShort')), strip(0, 20)),
						referenceContent('master'),
						referenceContentWithAttributes('stocks', filterBy(entityHaving(attributeInSet('code', 'milagro_cz', '12', '1', '22',
				'24', '7', '13'))), attributeContent('quantityOnStock')),
						referenceContent('categories'),
						referenceContent('groups')
					)
				)
			)
			""");

		queries.put("Q3-refprop-no-traverse-page300", """
			query(
				collection('Product'),
				filterBy(
					and(
						referenceHaving('groups', and(or(and(attributeIs('assignmentValidity', NULL)),
				and(attributeInRangeNow('assignmentValidity'))), entityPrimaryKeyInSet(21798))),
						or(and(attributeIs('validity', NULL)), and(attributeInRangeNow('validity'))),
						entityLocaleEquals('cs'),
						attributeInSet('productType', 'BASIC', 'SET', 'MASTER'),
						attributeEquals('status', 'ACTIVE'),
						priceInPriceLists('jaro-leto-2026-cz', 'jaro-leto-cz-2025', 'podzim-zima-2024-cz', 'jaro-leto-2024-cz', 'basic',
				'reference', 'basic_milagro_cz', 'reference_milagro_cz', 'bf-sleva-20'),
						priceValidInNow(),
						priceInCurrency('CZK'),
						referenceHaving('stockVisibilities', entityHaving(attributeInSet('code', 'milagro_cz', '12', '1', '22', '24', '7',
				'13')))
					)
				),
				orderBy(
					referenceProperty('groups', attributeNatural('orderInGroup', ASC), attributeNatural('assignmentPriority', ASC))
				),
				require(
					strip(300, 20),
					entityFetch(
						referenceContentWithAttributes('relatedProducts', attributeContent('category')),
						referenceContent('variants', entityFetch(attributeContent('codeShort')), strip(0, 20)),
						referenceContent('master'),
						referenceContent('categories'),
						referenceContent('groups')
					)
				)
			)
			""");

		queries.put("Q4-refprop-traverse-attribute-content", """
			query(
				collection('Product'),
				filterBy(
					and(
						referenceHaving('groups', and(or(and(attributeIs('assignmentValidity', NULL)),
				and(attributeInRangeNow('assignmentValidity'))), entityPrimaryKeyInSet(21798))),
						or(and(attributeIs('validity', NULL)), and(attributeInRangeNow('validity'))),
						entityLocaleEquals('cs'),
						attributeInSet('productType', 'BASIC', 'SET', 'MASTER'),
						attributeEquals('status', 'ACTIVE'),
						priceInPriceLists('jaro-leto-2026-cz', 'jaro-leto-cz-2025', 'podzim-zima-2024-cz', 'jaro-leto-2024-cz', 'basic',
				'reference', 'basic_milagro_cz', 'reference_milagro_cz', 'bf-sleva-20'),
						priceValidInNow(),
						priceInCurrency('CZK'),
						referenceHaving('stockVisibilities', entityHaving(attributeInSet('code', 'milagro_cz', '12', '1', '22', '24', '7',
				'13')))
					)
				),
				orderBy(
					referenceProperty('groups', attributeNatural('orderInGroup', ASC), attributeNatural('assignmentPriority', ASC),
				traverseByEntityProperty(entityPrimaryKeyExact(21798)))
				),
				require(
					strip(300, 20),
					entityFetch( attributeContent('code') )
				)
			)
			""");

		queries.put("Q5-attribute-code-ordering", """
			query(
				collection('Product'),
				filterBy(
					and(
						referenceHaving('groups', and(or(and(attributeIs('assignmentValidity', NULL)),
				and(attributeInRangeNow('assignmentValidity'))), entityPrimaryKeyInSet(21798))),
						or(and(attributeIs('validity', NULL)), and(attributeInRangeNow('validity'))),
						entityLocaleEquals('cs'),
						attributeInSet('productType', 'BASIC', 'SET', 'MASTER'),
						attributeEquals('status', 'ACTIVE'),
						priceInPriceLists('jaro-leto-2026-cz', 'jaro-leto-cz-2025', 'podzim-zima-2024-cz', 'jaro-leto-2024-cz', 'basic',
				'reference', 'basic_milagro_cz', 'reference_milagro_cz', 'bf-sleva-20'),
						priceValidInNow(),
						priceInCurrency('CZK'),
						referenceHaving('stockVisibilities', entityHaving(attributeInSet('code', 'milagro_cz', '12', '1', '22', '24', '7',
				'13')))
					)
				),
				orderBy( attributeNatural('code', ASC) ),
				require(
					strip(300, 20),
					entityFetch(
						referenceContentWithAttributes('relatedProducts', attributeContent('category')),
						referenceContent('variants', entityFetch(attributeContent('codeShort')), strip(0, 20)),
						referenceContent('master'),
						referenceContent('categories'),
						referenceContent('groups')
					)
				)
			)
			""");

		queries.put("Q6-refprop-traverse-full-fetch", """
			query(
				collection('Product'),
				filterBy(
					and(
						referenceHaving('groups', and(or(and(attributeIs('assignmentValidity', NULL)),
				and(attributeInRangeNow('assignmentValidity'))), entityPrimaryKeyInSet(21798))),
						or(and(attributeIs('validity', NULL)), and(attributeInRangeNow('validity'))),
						entityLocaleEquals('cs'),
						attributeInSet('productType', 'BASIC', 'SET', 'MASTER'),
						attributeEquals('status', 'ACTIVE'),
						priceInPriceLists('jaro-leto-2026-cz', 'jaro-leto-cz-2025', 'podzim-zima-2024-cz', 'jaro-leto-2024-cz', 'basic',
				'reference', 'basic_milagro_cz', 'reference_milagro_cz', 'bf-sleva-20'),
						priceValidInNow(),
						priceInCurrency('CZK'),
						referenceHaving('stockVisibilities', entityHaving(attributeInSet('code', 'milagro_cz', '12', '1', '22', '24', '7',
				'13')))
					)
				),
				orderBy(
					referenceProperty('groups', attributeNatural('orderInGroup', ASC), attributeNatural('assignmentPriority', ASC),
				traverseByEntityProperty(entityPrimaryKeyExact(21798)))
				),
				require(
					strip(300, 20),
					entityFetch(
						referenceContentWithAttributes('relatedProducts', attributeContent('category')),
						referenceContent('variants', entityFetch(attributeContent('codeShort')), strip(0, 20)),
						referenceContent('master'),
						referenceContentWithAttributes('stocks', filterBy(entityHaving(attributeInSet('code', 'milagro_cz', '12', '1', '22',
				'24', '7', '13'))), attributeContent('quantityOnStock')),
						referenceContent('categories'),
						referenceContent('groups')
					)
				)
			)
			""");

		queries.put("Q7-segments-refprop-traverse", """
			query(
				collection('Product'),
				filterBy(
					and(
						referenceHaving('groups', and(or(and(attributeIs('assignmentValidity', NULL)),
				and(attributeInRangeNow('assignmentValidity'))), entityPrimaryKeyInSet(21798))),
						or(and(attributeIs('validity', NULL)), and(attributeInRangeNow('validity'))),
						entityLocaleEquals('cs'),
						attributeInSet('productType', 'BASIC', 'SET', 'MASTER'),
						attributeEquals('status', 'ACTIVE'),
						priceInPriceLists('jaro-leto-2026-cz', 'jaro-leto-cz-2025', 'podzim-zima-2024-cz', 'jaro-leto-2024-cz', 'basic',
				'reference', 'basic_milagro_cz', 'reference_milagro_cz', 'bf-sleva-20'),
						priceValidInNow(),
						priceInCurrency('CZK'),
						referenceHaving('stockVisibilities', entityHaving(attributeInSet('code', 'milagro_cz', '12', '1', '22', '24', '7',
				'13')))
					)
				),
				orderBy(
					segments(
						segment(
							entityHaving(
								referenceHaving('stocks', and(
									entityHaving(attributeInSet('code', 'milagro_cz', '12', '1', '22', '24', '7', '13')),
									attributeGreaterThan('quantityOnStock', 0)
								))
							),
							orderBy(referenceProperty('groups', attributeNatural('orderInGroup', ASC), attributeNatural('assignmentPriority',
				ASC), traverseByEntityProperty(entityPrimaryKeyExact(21798))))
						),
						segment(
							orderBy(referenceProperty('groups', attributeNatural('orderInGroup', ASC), attributeNatural('assignmentPriority',
				ASC), traverseByEntityProperty(entityPrimaryKeyExact(21798))))
						)
					)
				),
				require(
					strip(300, 20),
					entityFetch(
						referenceContentWithAttributes('relatedProducts', attributeContent('category')),
						referenceContent('variants', entityFetch(attributeContent('codeShort')), strip(0, 20)),
						referenceContent('master'),
						referenceContentWithAttributes('stocks', filterBy(entityHaving(attributeInSet('code', 'milagro_cz', '12', '1', '22',
				'24', '7', '13'))), attributeContent('quantityOnStock')),
						referenceContent('categories'),
						referenceContent('groups')
					)
				)
			)
			""");

		queries.put("Q8-segments-not-stock-fallback", """
			query(
				collection('Product'),
				filterBy(
					referenceHaving('groups', entityPrimaryKeyInSet(21798)),
					entityLocaleEquals('cs'),
					attributeInSet('productType', 'BASIC', 'SET', 'MASTER'),
					attributeEquals('status', 'ACTIVE')
				),
				orderBy(
					segments(
						segment(
							entityHaving(
								referenceHaving('stocks',
									and(
										entityHaving(attributeInSet('code', 'milagro_cz', '12', '1', '22', '24', '7', '13')),
										attributeGreaterThan('quantityOnStock', 0)
									)
								)
							),
							orderBy(
								referenceProperty('groups',
									attributeNatural('orderInGroup', ASC),
									traverseByEntityProperty(entityPrimaryKeyExact(21798))
								)
							)
						),
						segment(
							entityHaving(
								not(
									referenceHaving('stocks',
										and(
											entityHaving(attributeInSet('code', 'milagro_cz', '12', '1', '22', '24', '7', '13')),
											attributeGreaterThan('quantityOnStock', 0)
										)
									)
								)
							),
							orderBy(
								referenceProperty('groups',
									attributeNatural('orderInGroup', ASC),
									traverseByEntityProperty(entityPrimaryKeyExact(21798))
								)
							)
						)
					)
				),
				require(
					strip(0, 500),
					entityFetch(
						referenceContent('groups'),
						referenceContent('relatedProducts')
					)
				)
			)
			""");

		final Map<String, String> results = new LinkedHashMap<>();
		try (EvitaClient evita = openClient()) {
			for (Map.Entry<String, String> e : queries.entrySet()) {
				final String label = e.getKey();
				final String qStr = e.getValue();
				System.out.println("================================================================");
				System.out.println(">>> Running " + label);
				System.out.println("================================================================");
				try {
					final Query parsed = DefaultQueryParser.getInstance().parseQueryUnsafe(qStr);
					evita.queryCatalog(
						CATALOG,
						session -> {
							final EvitaResponse<EntityClassifier> resp = session.query(parsed, EntityClassifier.class);
							final int pageSize = resp.getRecordPage().getData().size();
							final int total = resp.getRecordPage().getTotalRecordCount();
							final Set<Integer> pks = new HashSet<>();
							int dupCount = 0;
							int firstDup = -1;
							for (EntityClassifier ec : resp.getRecordPage().getData()) {
								final Integer pk = ec.getPrimaryKey();
								if (!pks.add(pk)) {
									dupCount++;
									if (firstDup == -1) firstDup = pk;
								}
							}
							System.out.println("  OK: page=" + pageSize + " total=" + total + " dups=" + dupCount + (firstDup >= 0 ? (" firstDup=" + firstDup) : ""));
							results.put(label, "OK page=" + pageSize + " total=" + total + " dups=" + dupCount);
							return null;
						}
					);
				} catch (Throwable t) {
					Throwable root = t;
					while (root.getCause() != null && root.getCause() != root) root = root.getCause();
					System.out.println("  FAIL: " + t.getClass().getSimpleName() + ": " + t.getMessage());
					System.out.println("        root: " + root.getClass().getSimpleName() + ": " + root.getMessage());
					results.put(label, "FAIL: " + root.getClass().getSimpleName() + ": " + root.getMessage());
				}
			}
		}

		System.out.println();
		System.out.println("================================================================");
		System.out.println("SUMMARY");
		System.out.println("================================================================");
		for (Map.Entry<String, String> e : results.entrySet()) {
			System.out.println("  " + e.getKey() + ": " + e.getValue());
		}
	}

	@Test
	void inspectProduct759850() {
		// Per issue note: PK 759850 = product 764480C01 in milagro_cz, crashed even after clean re-push
		try (EvitaClient evita = openClient()) {
			evita.queryCatalog(
				CATALOG,
				session -> {
					session.getEntity("Product", 759850,
						io.evitadb.api.query.QueryConstraints.referenceContentAll(),
						io.evitadb.api.query.QueryConstraints.attributeContent("code"),
						io.evitadb.api.query.QueryConstraints.dataInLocales(new java.util.Locale("cs"))
					).ifPresentOrElse(
						sealedEntity -> {
							System.out.println("PK=759850 code=" + sealedEntity.getAttribute("code"));
							final Map<String, Integer> refCounts = new HashMap<>();
							sealedEntity.getReferences().forEach(r ->
								refCounts.merge(r.getReferenceName(), 1, Integer::sum)
							);
							refCounts.forEach((k, v) -> System.out.println("  ref " + k + ": " + v));
							System.out.println("  groups PKs: " + sealedEntity.getReferences("groups").stream()
								.map(r -> r.getReferencedPrimaryKey()).toList());
						},
						() -> System.out.println("PK=759850 NOT FOUND")
					);
					return null;
				}
			);
		}
	}
}
