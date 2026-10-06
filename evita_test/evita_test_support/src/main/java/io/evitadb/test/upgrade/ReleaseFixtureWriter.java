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

package io.evitadb.test.upgrade;

import io.evitadb.api.CatalogState;
import io.evitadb.api.EvitaContract;
import io.evitadb.api.EvitaSessionContract;
import io.evitadb.api.configuration.EvitaConfiguration;
import io.evitadb.api.configuration.ServerOptions;
import io.evitadb.api.configuration.StorageOptions;
import io.evitadb.api.requestResponse.data.EntityReferenceContract;
import io.evitadb.core.Evita;

import javax.annotation.Nonnull;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.stream.Collectors;

/**
 * Executes {@link ReleaseFixtureRecipe}s on an engine. The same code runs inside a released engine, where it writes the
 * fixtures (driven by {@link Release2026_1FixtureGenerator} and {@link Release2026_2FixtureGenerator}), and inside the
 * current engine, where {@link #replay(EvitaContract, ReleaseFixtureRecipe)} builds what a fresh current engine makes
 * of the same recipe. It therefore uses only the API the releases and the current engine have in common.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
public final class ReleaseFixtureWriter {

	/**
	 * How long a catalog may take to settle after the engine started, in milliseconds.
	 */
	private static final long SETTLE_TIMEOUT_MILLIS = 120_000L;

	private ReleaseFixtureWriter() {
		// utility class
	}

	/**
	 * Starts an engine over the given storage directory, with sessions that never time out.
	 *
	 * @param storageDirectory the directory holding the engine files and one folder per catalog
	 * @param workDirectory    the directory for the engine's temporary files
	 * @return the started engine
	 */
	@Nonnull
	public static Evita start(@Nonnull Path storageDirectory, @Nonnull Path workDirectory) {
		createDirectories(storageDirectory);
		createDirectories(workDirectory);
		return new Evita(
			EvitaConfiguration.builder()
				.server(ServerOptions.builder().closeSessionsAfterSecondsOfInactivity(-1).build())
				.storage(
					StorageOptions.builder()
						.storageDirectory(storageDirectory)
						.workDirectory(workDirectory)
						.build()
				)
				.build()
		);
	}

	/**
	 * Writes the catalog of the recipe as its writing release does: defines the catalog, fills it in a warming-up
	 * session, takes it live and commits the recipe's {@link ReleaseFixtureRecipe#aliveTransactions()} one after
	 * another.
	 *
	 * @param evita  the engine
	 * @param recipe the recipe
	 */
	public static void write(@Nonnull EvitaContract evita, @Nonnull ReleaseFixtureRecipe recipe) {
		evita.defineCatalog(recipe.name());
		try (EvitaSessionContract session = evita.createReadWriteSession(recipe.name())) {
			recipe.warmUp().accept(session);
			session.goLiveAndClose();
		}
		commit(evita, recipe.name(), recipe.aliveTransactions());
	}

	/**
	 * Commits the recipe's {@link ReleaseFixtureRecipe#release2026_2Transactions()} on a catalog the 2026.1 engine
	 * wrote, after waiting until the engine has opened (and thereby upgraded) it.
	 *
	 * @param evita  the engine
	 * @param recipe the recipe
	 * @throws IllegalStateException when the catalog does not come alive
	 */
	public static void openAndCommitRelease2026_2Transactions(
		@Nonnull EvitaContract evita,
		@Nonnull ReleaseFixtureRecipe recipe
	) {
		final CatalogState state = awaitSettled(evita, recipe.name());
		if (state != CatalogState.ALIVE) {
			throw new IllegalStateException("Catalog `" + recipe.name() + "` did not come alive, it is " + state + ".");
		}
		commit(evita, recipe.name(), recipe.release2026_2Transactions());
	}

	/**
	 * Builds the catalog of the recipe in the given engine from scratch, as the chain of releases that wrote the
	 * fixture did: {@link #write(EvitaContract, ReleaseFixtureRecipe)} followed by the transactions the 2026.2 engine
	 * committed. Run on the current engine, it yields what a fresh current engine builds from the same input.
	 *
	 * @param evita  the engine
	 * @param recipe the recipe
	 */
	public static void replay(@Nonnull EvitaContract evita, @Nonnull ReleaseFixtureRecipe recipe) {
		write(evita, recipe);
		commit(evita, recipe.name(), recipe.release2026_2Transactions());
	}

	/**
	 * Runs every probe of the recipe and describes each answer on one line: `label = [primary keys]`, or
	 * `label FAILED <exception>` when the query throws.
	 *
	 * @param evita  the engine
	 * @param recipe the recipe
	 * @return one line per probe, in the recipe's order
	 */
	@Nonnull
	public static List<String> probe(@Nonnull EvitaContract evita, @Nonnull ReleaseFixtureRecipe recipe) {
		final List<String> lines = new ArrayList<>(recipe.probes().size());
		for (ReleaseFixtureProbe probe : recipe.probes()) {
			try {
				final List<Integer> primaryKeys = evita.queryCatalog(
					recipe.name(),
					session -> {
						return session.queryListOfEntityReferences(probe.query())
							.stream()
							.map(EntityReferenceContract::getPrimaryKey)
							.collect(Collectors.toList());
					}
				);
				lines.add(probe.label() + " = " + primaryKeys);
			} catch (RuntimeException ex) {
				lines.add(probe.label() + " FAILED " + ex);
			}
		}
		return lines;
	}

	/**
	 * Waits until the catalog leaves its transitional states and returns the state it settled in: `ALIVE`,
	 * `WARMING_UP`, `CORRUPTED` or `INACTIVE`.
	 *
	 * @param evita       the engine
	 * @param catalogName the catalog
	 * @return the settled state
	 * @throws IllegalStateException when the catalog does not settle within two minutes
	 */
	@Nonnull
	public static CatalogState awaitSettled(@Nonnull EvitaContract evita, @Nonnull String catalogName) {
		final long deadline = System.currentTimeMillis() + SETTLE_TIMEOUT_MILLIS;
		CatalogState state = null;
		while (System.currentTimeMillis() < deadline) {
			state = evita.getCatalogState(catalogName).orElse(null);
			if (
				state == CatalogState.ALIVE || state == CatalogState.WARMING_UP ||
					state == CatalogState.CORRUPTED || state == CatalogState.INACTIVE
			) {
				return state;
			}
			try {
				Thread.sleep(50L);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				throw new IllegalStateException("Interrupted while waiting for catalog `" + catalogName + "`.", e);
			}
		}
		throw new IllegalStateException("Catalog `" + catalogName + "` did not settle, last state " + state + ".");
	}

	/**
	 * Writes each recipe with the running engine into a storage directory of its own, `outputDirectory/<name>`, which
	 * must not exist yet. One engine is started and closed per fixture, so each directory holds exactly one catalog
	 * and the engine files that know about it.
	 *
	 * @param recipes         the recipes to write
	 * @param outputDirectory the directory receiving one storage directory per fixture
	 * @param workDirectory   the directory for the engines' temporary files
	 * @throws IllegalStateException when a fixture directory already exists
	 */
	public static void writeFixtures(
		@Nonnull List<ReleaseFixtureRecipe> recipes,
		@Nonnull Path outputDirectory,
		@Nonnull Path workDirectory
	) {
		for (ReleaseFixtureRecipe recipe : recipes) {
			final Path storageDirectory = outputDirectory.resolve(recipe.name());
			if (Files.exists(storageDirectory)) {
				throw new IllegalStateException("Fixture directory `" + storageDirectory + "` already exists.");
			}
			try (Evita evita = start(storageDirectory, workDirectory.resolve(recipe.name()))) {
				write(evita, recipe);
			}
			System.out.println("FIXTURE WRITTEN " + recipe.name() + " (" + recipe.origin() + ")");
		}
	}

	/**
	 * Opens each fixture the 2026.1 engine wrote into `outputDirectory/<name>` with the running engine and commits the
	 * recipe's {@link ReleaseFixtureRecipe#release2026_2Transactions()}. Recipes of another origin are skipped.
	 *
	 * @param recipes         the recipes
	 * @param outputDirectory the directory holding one storage directory per fixture
	 * @param workDirectory   the directory for the engines' temporary files
	 * @throws IllegalStateException when the 2026.1 engine has not written a fixture yet
	 */
	public static void openFixturesWrittenBy2026_1(
		@Nonnull List<ReleaseFixtureRecipe> recipes,
		@Nonnull Path outputDirectory,
		@Nonnull Path workDirectory
	) {
		for (ReleaseFixtureRecipe recipe : recipes) {
			if (recipe.origin() != ReleaseFixtureOrigin.RELEASE_2026_1_OPENED_BY_2026_2) {
				continue;
			}
			final Path storageDirectory = outputDirectory.resolve(recipe.name());
			if (!Files.isDirectory(storageDirectory)) {
				throw new IllegalStateException(
					"Fixture `" + recipe.name() + "` must be written by the 2026.1 generator first, `" +
						storageDirectory + "` does not exist."
				);
			}
			try (Evita evita = start(storageDirectory, workDirectory.resolve(recipe.name() + "-opened"))) {
				openAndCommitRelease2026_2Transactions(evita, recipe);
			}
			System.out.println("FIXTURE OPENED " + recipe.name() + " (" + recipe.origin() + ")");
		}
	}

	/**
	 * Starts the running engine over each existing fixture directory `storageRoot/<name>` and prints the answers of the
	 * recipe's probes, one line per probe prefixed by `PROBE <name>`. Opening a catalog may upgrade it, so this must
	 * run on a copy of the fixtures.
	 *
	 * @param recipes       the recipes
	 * @param storageRoot   the directory holding one storage directory per fixture
	 * @param workDirectory the directory for the engines' temporary files
	 */
	public static void probeFixtures(
		@Nonnull List<ReleaseFixtureRecipe> recipes,
		@Nonnull Path storageRoot,
		@Nonnull Path workDirectory
	) {
		for (ReleaseFixtureRecipe recipe : recipes) {
			final Path storageDirectory = storageRoot.resolve(recipe.name());
			if (!Files.isDirectory(storageDirectory)) {
				continue;
			}
			try (Evita evita = start(storageDirectory, workDirectory.resolve(recipe.name() + "-probe"))) {
				final CatalogState state = awaitSettled(evita, recipe.name());
				System.out.println("PROBE " + recipe.name() + " state = " + state);
				if (state == CatalogState.ALIVE || state == CatalogState.WARMING_UP) {
					for (String line : probe(evita, recipe)) {
						System.out.println("PROBE " + recipe.name() + " " + line);
					}
				}
			}
		}
	}

	/**
	 * Selects the recipes named in `names`, or all of them when `names` is empty. Names of other recipes are ignored:
	 * the generator script passes the same names to the run of each release, and each release writes only its own
	 * recipes; {@link Release2026_2FixtureGenerator} rejects a name that matches no recipe at all.
	 *
	 * @param recipes the recipes to choose from
	 * @param names   the fixture names to keep
	 * @return the selected recipes, in the order of `recipes`
	 */
	@Nonnull
	public static List<ReleaseFixtureRecipe> select(
		@Nonnull List<ReleaseFixtureRecipe> recipes,
		@Nonnull List<String> names
	) {
		if (names.isEmpty()) {
			return recipes;
		}
		final List<ReleaseFixtureRecipe> selected = new ArrayList<>(names.size());
		for (ReleaseFixtureRecipe recipe : recipes) {
			if (names.contains(recipe.name())) {
				selected.add(recipe);
			}
		}
		return selected;
	}

	/**
	 * Commits each of the transactions in its own read-write session, in order.
	 *
	 * @param evita        the engine
	 * @param catalogName  the catalog
	 * @param transactions the transactions
	 */
	private static void commit(
		@Nonnull EvitaContract evita,
		@Nonnull String catalogName,
		@Nonnull List<Consumer<EvitaSessionContract>> transactions
	) {
		for (Consumer<EvitaSessionContract> transaction : transactions) {
			evita.updateCatalog(catalogName, transaction);
		}
	}

	/**
	 * Creates the directory and its parents when they do not exist yet.
	 *
	 * @param directory the directory
	 */
	private static void createDirectories(@Nonnull Path directory) {
		try {
			Files.createDirectories(directory);
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}

}
