/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2024
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

package io.evitadb.utils;

import io.evitadb.exception.GenericEvitaInternalError;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.util.Random;
import java.util.concurrent.ThreadLocalRandom;

import static java.util.Optional.ofNullable;

/**
 * This class contains utility methods for working with random numbers.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2024
 */
public class RandomUtils {

	/**
	 * Method retrieves frozen internal state of the {@link Random} function that can be used as a stable SEED for
	 * randomized logic.
	 */
	public static byte[] getFrozenRandom() {
		final ByteArrayOutputStream bos = new ByteArrayOutputStream(200);
		try (var os = new ObjectOutputStream(bos)) {
			os.writeObject(new Random());
			return bos.toByteArray();
		} catch (IOException e) {
			throw new GenericEvitaInternalError("Unexpected error during debug mode evaluation!", e);
		}
	}

	/**
	 * Returns {@link Random} instance based on the provided frozen state. If the state is null, it returns
	 * {@link ThreadLocalRandom#current()}.
	 *
	 * @param frozenRandom frozen state of the random function
	 * @return {@link Random} instance
	 */
	@Nonnull
	public static Random getRandom(@Nullable byte[] frozenRandom) {
		return ofNullable(frozenRandom)
			.map(it -> {
				try (var is = new ObjectInputStream(new ByteArrayInputStream(it))) {
					return (Random) is.readObject();
				} catch (IOException | ClassNotFoundException e) {
					throw new GenericEvitaInternalError("Unexpected error during debug mode evaluation!", e);
				}
			})
			.orElseGet(ThreadLocalRandom::current);
	}
}
