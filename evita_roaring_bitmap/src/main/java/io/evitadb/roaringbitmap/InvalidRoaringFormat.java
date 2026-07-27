package io.evitadb.roaringbitmap;

import java.io.IOException;
import java.io.Serial;
import javax.annotation.Nonnull;

/**
 * Unchecked exception signalling a malformed serialized roaring bitmap - a missing format cookie or
 * a similar structural anomaly detected while deserializing.
 *
 * It is a {@link RuntimeException} because the memory-mapped (`ByteBuffer`) read path does not
 * declare `throws IOException` and treats a corrupt buffer as a data error. Stream-based
 * deserializers that do declare `IOException` can turn it into one via {@link #toIOException()}
 * when a corrupt input is more naturally reported as an I/O failure.
 */
public class InvalidRoaringFormat extends RuntimeException {

	/**
	 * @param string human-readable description of the format anomaly that was detected
	 */
	public InvalidRoaringFormat(@Nonnull final String string) {
		super(string);
	}

	/**
	 * Serialization version tag for this exception type.
	 */
	@Serial private static final long serialVersionUID = 1L;

	/**
	 * Wraps this format error in an {@link IOException} carrying the same message, for callers whose
	 * deserialization path reports failures as I/O errors.
	 *
	 * @return an `IOException` describing the same format anomaly
	 */
	@Nonnull
	public IOException toIOException() {
		return new IOException(toString());
	}
}
