/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2023-2024
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

import javax.annotation.Nonnull;
import java.security.SecureRandom;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * UUID generator that uses {@link java.util.concurrent.ThreadLocalRandom} number generator for constructing UUIDs
 * according to standard method number 4.
 *
 * This generator replaces {@link UUID#randomUUID()} because the Java implementation uses {@link SecureRandom}
 * under the hood and that performs poorly on high throughput systems due to thread contention and slowly
 * accumulating entropy. We don't need cryptographically secure UUID - just random ones that are unique just enough.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2022
 */
public class UUIDUtil {

	private static final int STANDARD_METHOD_NUMBER = 4;

	/**
	 * Generates new randomized {@link UUID}. We dont use Java {@link UUID#randomUUID()} because it uses
	 * {@link SecureRandom} internally and in case of high throughput the system entropy becomes
	 * bottleneck and slows down entire system.
	 */
	@Nonnull
	public static UUID randomUUID() {
		final ThreadLocalRandom random = ThreadLocalRandom.current();
		final long r1 = random.nextLong();
		final long r2 = random.nextLong();
		return constructUUID(r1, r2);
	}

	/**
	 * Factory method for creating UUIDs from the canonical string
	 * representation.
	 *
	 * Copied from `com.fasterxml.uuid.impl.UUIDUtil`.
	 * <a href="https://github.com/cowtowncoder/java-uuid-generator">UUID Utils from CowTownCoder</a>. Thanks.
	 *
	 * @param id String that contains the canonical representation of
	 *   the UUID to build; 36-char string (see UUID specs for details).
	 *   Hex-chars may be in upper-case too; UUID class will always output
	 *   them in lowercase.
	 */
	@Nonnull
	public static UUID uuid(@Nonnull String id)
	{
		if (id.length() != 36) {
			throw new NumberFormatException("UUID has to be represented by the standard 36-char representation");
		}

		long lo, hi;
		lo = hi = 0;

		for (int i = 0, j = 0; i < 36; ++j) {

			// Need to bypass hyphens:
			switch (i) {
				case 8, 13, 18, 23 -> {
					if (id.charAt(i) != '-') {
						throw new NumberFormatException("UUID has to be represented by the standard 36-char representation");
					}
					++i;
				}
			}
			int curr;
			char c = id.charAt(i);

			if (c >= '0' && c <= '9') {
				curr = (c - '0');
			} else if (c >= 'a' && c <= 'f') {
				curr = (c - 'a' + 10);
			} else if (c >= 'A' && c <= 'F') {
				curr = (c - 'A' + 10);
			} else {
				throw new NumberFormatException("Non-hex character at #"+i+": '"+c
					+"' (value 0x"+Integer.toHexString(c)+")");
			}
			curr = (curr << 4);

			c = id.charAt(++i);

			if (c >= '0' && c <= '9') {
				curr |= (c - '0');
			} else if (c >= 'a' && c <= 'f') {
				curr |= (c - 'a' + 10);
			} else if (c >= 'A' && c <= 'F') {
				curr |= (c - 'A' + 10);
			} else {
				throw new NumberFormatException("Non-hex character at #"+i+": '"+c
					+"' (value 0x"+Integer.toHexString(c)+")");
			}
			if (j < 8) {
				hi = (hi << 8) | curr;
			} else {
				lo = (lo << 8) | curr;
			}
			++i;
		}
		return new UUID(hi, lo);
	}

	/**
	 * Creates UUID by standard method 4.
	 * Copied from `com.fasterxml.uuid.impl.UUIDUtil`.
	 * <a href="https://github.com/cowtowncoder/java-uuid-generator">UUID Generator from CowTownCoder</a>. Thanks.
	 */
	@Nonnull
	public static UUID constructUUID(long l1, long l2) {
		// first, ensure type is ok
		l1 &= ~0xF000L; // remove high nibble of 6th byte
		l1 |= STANDARD_METHOD_NUMBER << 12;
		// second, ensure variant is properly set too (8th byte; most-sig byte of second long)
		l2 = ((l2 << 2) >>> 2); // remove 2 MSB
		l2 |= (2L << 62); // set 2 MSB to '10'
		return new UUID(l1, l2);
	}

}
