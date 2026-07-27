/*
 * (c) the authors Licensed under the Apache License, Version 2.0.
 */
package io.evitadb.roaringbitmap.longlong;

import java.math.BigInteger;
import java.util.Comparator;

import javax.annotation.Nonnull;

/**
 * Packs two 32-bit ints into a single 64-bit `long` and splits them back out: the high 32 bits act
 * as a lookup key, the low 32 bits index a 32-bit bitmap keyed under that high half.
 *
 * This is the coarse 32/32 split retained from upstream RoaringBitmap's `NavigableMap`-based 64-bit
 * bitmap. The ART-backed store used by {@link io.evitadb.roaringbitmap.PersistentLongRoaringBitmap}
 * in this package instead relies on the finer 48-bit high / 16-bit low split of {@link LongUtils}.
 *
 * @author Benoit Lacelle
 *
 */
public class RoaringIntPacking {

	/**
	 * Extracts the high 32 bits used as the lookup key. Uses an arithmetic shift, so a negative `id`
	 * yields a negative key (the sign bit is preserved rather than zero-filled).
	 *
	 * @param id any long, positive or negative
	 * @return an int holding the 32 highest order bits of information of the input long
	 */
	public static int high(long id) {
		return (int) (id >> 32);
	}

	/**
	 * Extracts the low 32 bits, i.e. the position within the bitmap keyed by {@link #high(long)}. The
	 * narrowing cast keeps the raw bit pattern regardless of sign.
	 *
	 * @param id any long, positive or negative
	 * @return an int holding the 32 lowest order bits of information of the input long
	 */
	public static int low(long id) {
		return (int) id;
	}

	/**
	 * Inverse of {@link #high(long)} / {@link #low(long)}: recombines the two halves into the original
	 * `long`. The low half is masked with `0xffffffffL` so its sign bit is not extended into the high
	 * 32 bits.
	 *
	 * @param high an integer representing the highest order bits of the output long
	 * @param low  an integer representing the lowest order bits of the output long
	 * @return a long packing together the integers as computed by
	 * {@link RoaringIntPacking#high(long)} and {@link RoaringIntPacking#low(long)}
	 */
	// https://stackoverflow.com/questions/12772939/java-storing-two-ints-in-a-long
	public static long pack(int high, int low) {
		return (((long) high) << 32) | (low & 0xffffffffL);
	}

	/**
	 *
	 * @param signedLongs true if the long should be considered as a signed long.
	 * @return the int representing the highest value which can be set as high value
	 */
	public static int highestHigh(boolean signedLongs) {
		if (signedLongs) {
			return Integer.MAX_VALUE;
		} else {
			return -1;
		}
	}

	/**
	 * @return A comparator for unsigned longs: a negative long is a long greater than Long.MAX_VALUE
	 */
	@Nonnull
	public static Comparator<Integer> unsignedComparator() {
		return new Comparator<Integer>() {

			@Override
			public int compare(Integer o1, Integer o2) {
				return compareUnsigned(o1, o2);
			}
		};
	}

	/**
	 * Compares two {@code int} values numerically treating the values as unsigned.
	 *
	 * @param x the first {@code int} to compare
	 * @param y the second {@code int} to compare
	 * @return the value {@code 0} if {@code x == y}; a value less than {@code 0} if {@code x < y} as
	 * unsigned values; and a value greater than {@code 0} if {@code x > y} as unsigned values
	 * @since 1.8
	 */
	// Duplicated from jdk8 Integer.compareUnsigned
	public static int compareUnsigned(int x, int y) {
		return Integer.compare(x + Integer.MIN_VALUE, y + Integer.MIN_VALUE);
	}

	/**
	 * the constant 2^64
	 */
	@Nonnull
	private static final BigInteger TWO_64 = BigInteger.ONE.shiftLeft(64);

	/**
	 * JDK8 Long.toUnsignedString was too complex to backport. Go for a slow version relying on
	 * BigInteger
	 */
	// https://stackoverflow.com/questions/7031198/java-signed-long-to-unsigned-long-string
	@Nonnull
	static String toUnsignedString(long l) {
		BigInteger b = BigInteger.valueOf(l);
		if (b.signum() < 0) {
			b = b.add(TWO_64);
		}
		return b.toString();
	}
}
