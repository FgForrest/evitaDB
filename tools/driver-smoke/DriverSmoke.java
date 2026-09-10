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

package io.evitadb.tools.smoke;

import io.evitadb.api.EvitaSessionContract;
import io.evitadb.api.requestResponse.data.SealedEntity;
import io.evitadb.driver.EvitaClient;
import io.evitadb.driver.config.EvitaClientConfiguration;

import java.util.List;
import java.util.Set;

import static io.evitadb.api.query.Query.query;
import static io.evitadb.api.query.QueryConstraints.attributeContent;
import static io.evitadb.api.query.QueryConstraints.attributeEquals;
import static io.evitadb.api.query.QueryConstraints.collection;
import static io.evitadb.api.query.QueryConstraints.entityFetchAll;
import static io.evitadb.api.query.QueryConstraints.filterBy;
import static io.evitadb.api.query.QueryConstraints.require;

/**
 * Smoke test proving that the Java driver runs on the oldest JDK it supports. It is not a Maven module and has
 * no dependency but the driver itself: {@code tools/verify-driver-on-jdk.sh} compiles it with the JDK under test
 * against the shaded {@code evita_java_driver_all_in_one} jar and runs it against a server started on the JDK the
 * server requires. The source therefore has to stay at the driver's language level (see {@code java.release} in
 * the root POM) and must not use anything beyond the driver's own API.
 *
 * The round trip covers the paths a client without any extra dependency has to be able to take: certificate
 * download through the system API, catalog creation, schema definition, upserts, going live, an entity fetch
 * by primary key, an attribute query and catalog removal. Every step prints a {@code SMOKE} line so a failing
 * run shows how far it got.
 *
 * Arguments: {@code <host> <port> [<expected Java feature version>]}. When the third argument is given the
 * run fails unless the JVM executing it has exactly that feature version - the guard against the smoke
 * silently running on the build JDK instead of the one under test.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
public final class DriverSmoke {
	private static final String CATALOG = "driverSmoke";
	private static final String ENTITY_TYPE = "Product";
	private static final String ATTRIBUTE_CODE = "code";
	private static final long CONNECT_TIMEOUT_MILLIS = 120_000L;

	private DriverSmoke() {
	}

	/**
	 * Runs the round trip described on the class and exits non-zero on the first failed expectation.
	 *
	 * @param args host, port and optionally the expected Java feature version of this JVM
	 * @throws Exception when any step of the round trip fails
	 */
	public static void main(String[] args) throws Exception {
		if (args.length < 2) {
			System.err.println("usage: DriverSmoke <host> <port> [<expected Java feature version>]");
			System.exit(2);
		}
		final String host = args[0];
		final int port = Integer.parseInt(args[1]);
		final int feature = Runtime.version().feature();
		System.out.println(
			"SMOKE runtime=" + Runtime.version() + " home=" + System.getProperty("java.home")
		);
		if (args.length > 2 && feature != Integer.parseInt(args[2])) {
			throw new IllegalStateException(
				"expected to run on Java " + args[2] + " but this JVM is Java " + feature
			);
		}

		try (EvitaClient evita = connect(host, port)) {
			evita.deleteCatalogIfExists(CATALOG);

			evita.defineCatalog(CATALOG)
				.withEntitySchema(
					ENTITY_TYPE,
					entitySchema -> entitySchema
						.withoutGeneratedPrimaryKey()
						.withAttribute(ATTRIBUTE_CODE, String.class, whichIs -> whichIs.filterable().sortable())
				)
				.updateViaNewSession(evita);
			System.out.println("SMOKE schema defined");

			evita.updateCatalog(CATALOG, session -> {
				session.createNewEntity(ENTITY_TYPE, 1).setAttribute(ATTRIBUTE_CODE, "P-1").upsertVia(session);
				session.createNewEntity(ENTITY_TYPE, 2).setAttribute(ATTRIBUTE_CODE, "P-2").upsertVia(session);
			});
			System.out.println("SMOKE entities upserted");

			evita.updateCatalog(CATALOG, EvitaSessionContract::goLiveAndClose);
			System.out.println("SMOKE catalog live");

			final String code = evita.queryCatalog(
				CATALOG,
				session -> {
					return session.getEntity(ENTITY_TYPE, 2, attributeContent(ATTRIBUTE_CODE))
						.orElseThrow()
						.getAttribute(ATTRIBUTE_CODE);
				}
			);
			final List<SealedEntity> hits = evita.queryCatalog(
				CATALOG,
				session -> {
					return session.queryList(
						query(
							collection(ENTITY_TYPE),
							filterBy(attributeEquals(ATTRIBUTE_CODE, "P-1")),
							require(entityFetchAll())
						),
						SealedEntity.class
					);
				}
			);
			System.out.println("SMOKE getEntity(2).code=" + code + " query(code=P-1).size=" + hits.size());
			if (!"P-2".equals(code) || hits.size() != 1 || hits.get(0).getPrimaryKey() != 1) {
				throw new IllegalStateException("read-back does not match what was written");
			}

			evita.deleteCatalogIfExists(CATALOG);
			System.out.println("SMOKE catalog dropped, remaining=" + evita.getCatalogNames());
		}
		System.out.println("SMOKE OK on Java " + feature);
	}

	/**
	 * Builds the client and proves the server is reachable, retrying for up to {@link #CONNECT_TIMEOUT_MILLIS}.
	 * The constructor itself already talks to the server (it downloads the generated certificate through the
	 * system API), so the whole handshake is retried, not just the first call.
	 *
	 * @param host server host
	 * @param port server port
	 * @return a client whose {@link EvitaClient#getCatalogNames()} has succeeded once
	 * @throws InterruptedException when interrupted while waiting for the server
	 */
	private static EvitaClient connect(String host, int port) throws InterruptedException {
		final long deadline = System.currentTimeMillis() + CONNECT_TIMEOUT_MILLIS;
		Exception last = null;
		while (System.currentTimeMillis() < deadline) {
			EvitaClient client = null;
			try {
				client = new EvitaClient(EvitaClientConfiguration.builder().host(host).port(port).build());
				final Set<String> catalogs = client.getCatalogNames();
				System.out.println("SMOKE connected, catalogs=" + catalogs);
				return client;
			} catch (Exception e) {
				last = e;
				if (client != null) {
					client.close();
				}
				Thread.sleep(1_000L);
			}
		}
		throw new IllegalStateException(
			"server " + host + ":" + port + " not reachable within " + CONNECT_TIMEOUT_MILLIS + " ms", last
		);
	}
}
