package io.evitadb.roaringbitmap.longlong;

import javax.annotation.Nonnull;

/**
 * Big-endian byte helpers for the 4-byte partial keys that the ART inner nodes ({@code Node4},
 * {@code Node16}) store and reshuffle. Byte position `0` is the most significant byte, so the
 * lexicographic byte ordering the nodes rely on matches the unsigned ordering of the packed int.
 */
public class IntegerUtil {

	/**
	 * convert integer to its byte array format
	 *
	 * @param v an input integer value
	 * @return the big endian byte array representation
	 */
	@Nonnull
	public static byte[] toBDBytes(int v) {
		final byte[] bytes = new byte[4];
		bytes[0] = (byte) (v >> 24);
		bytes[1] = (byte) (v >> 16);
		bytes[2] = (byte) (v >> 8);
		bytes[3] = (byte) v;
		return bytes;
	}

	/**
	 * convert into its integer representation
	 *
	 * @param bytes the big endian integer's byte array
	 * @return a integer corresponding to input bytes
	 */
	public static int fromBDBytes(@Nonnull byte[] bytes) {
		return (bytes[0] & 0xFF) << 24
			| (bytes[1] & 0xFF) << 16
			| (bytes[2] & 0xFF) << 8
			| bytes[3] & 0xFF;
	}

	/**
	 * Returns a copy of `v` with the byte at big-endian position `pos` (0 = most significant)
	 * replaced by `bv`, leaving the other three bytes untouched.
	 *
	 * @param v   the input integer value
	 * @param bv  the byte value to insert
	 * @param pos the position of an 4 byte array to replace
	 * @return a fresh integer after a specified position byte been replaced
	 */
	public static int setByte(int v, byte bv, int pos) {
		final int i = ((3 - pos) << 3);
		v &= ~(0xFF << i);
		v |= (bv & 0xFF) << i;
		return v;
	}

	/**
	 * Shifts a `count`-byte window of `v`, starting at big-endian position `pos`, one byte toward the
	 * most-significant end, opening a gap for an inserted key byte in an ART node. A `count` of `0`
	 * leaves `v` unchanged.
	 *
	 * @param v     a integer value
	 * @param pos   the position from which to shift byte values left
	 * @param count the shifting numbers
	 * @return a fresh integer value
	 */
	public static int shiftLeftFromSpecifiedPosition(int v, int pos, int count) {
		if (count != 0) {
			final int shiftToLeft = (4 - count) << 3;
			final int shiftToRight = shiftToLeft - (pos << 3);
			final int maskShifted = 0xFFFFFFFF >>> shiftToLeft << shiftToRight;
			v = (v & ~maskShifted) | (v << 8 & maskShifted);
		}
		return v;
	}

	/**
	 * fetch the first byte
	 *
	 * @param v an input integer
	 * @return the first byte of the big endian representation
	 */
	public static byte firstByte(int v) {
		return (byte) (v >> 24);
	}
}
