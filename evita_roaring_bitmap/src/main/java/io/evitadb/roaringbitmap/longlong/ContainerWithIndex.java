package io.evitadb.roaringbitmap.longlong;

import io.evitadb.roaringbitmap.Container;

import javax.annotation.Nonnull;

/**
 * Result holder returned by {@link HighLowContainer#searchContainer(byte[])} that pairs a resolved
 * low-16 {@link Container} with its `containerIdx` in the backing {@code Containers} store. Carrying
 * the index alongside the container lets a caller mutate and then swap the container in place via
 * {@link HighLowContainer#replaceContainer(long, Container)} without a second ART lookup.
 */
public class ContainerWithIndex {

	/**
	 * The resolved low-16 container holding the values for one 48-bit high key.
	 */
	@Nonnull private final Container container;
	/**
	 * Slot index of {@link #container} in the backing store, used to replace or remove it by index.
	 */
	private final long containerIdx;

	public ContainerWithIndex(@Nonnull Container container, long containerIdx) {
		this.container = container;
		this.containerIdx = containerIdx;
	}

	@Nonnull
	public Container getContainer() {
		return this.container;
	}

	public long getContainerIdx() {
		return this.containerIdx;
	}
}
