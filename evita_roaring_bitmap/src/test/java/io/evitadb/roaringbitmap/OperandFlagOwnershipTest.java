package io.evitadb.roaringbitmap;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static io.evitadb.roaringbitmap.SharedContainerLockstepFuzzTest.assertLockstep;

/**
 * Non-owning operations may lend containers without writing an operand's flag array.
 * Each operation also preserves isolation when either side is subsequently mutated.
 */
class OperandFlagOwnershipTest {

	/** Borrowing operations, including both aggregation overloads and all receiver merge shapes. */
	enum Operation {
		OR, XOR, AND_NOT, RANGE_AND, RANGE_OR, RANGE_XOR, RANGE_AND_NOT, CLONE,
		IN_PLACE_OR, IN_PLACE_XOR, LAZY_OR, NAIVE_LAZY_OR,
		NAIVE_OR_ARRAY, NAIVE_OR_ITERATOR, NAIVE_XOR_ARRAY, NAIVE_XOR_ITERATOR,
		QUEUE_OR_ARRAY, QUEUE_OR_ITERATOR, QUEUE_XOR_ARRAY,
		FOLD_OR_ARRAY, FOLD_OR_ITERATOR, FOLD_XOR_ARRAY, FOLD_XOR_ITERATOR
	}

	/** Enumerates spare-capacity and exactly sized flag arrays with three receiver shapes. */
	static Stream<Arguments> operations() {
		return Arrays.stream(Operation.values()).flatMap(operation ->
			Stream.of(false, true).flatMap(exact ->
				Stream.of(0, 1, 2).map(shape -> Arguments.of(operation, exact, shape))));
	}

	/** Builds interleaved sparse chunks through a builder whose flags used to lag its backbone. */
	static PersistentRoaringBitmap operand(final int ordinal, final boolean exact) {
		final PersistentRoaringBitmap seed = new PersistentRoaringBitmap();
		for (int chunk = 0; chunk < 12; chunk++) {
			final int base = (chunk * 5 + ordinal + 1) << 16;
			seed.add(base + 7, base + 14, base + 21);
		}
		final PersistentRoaringBitmap bitmap = PersistentRoaringBitmap.addOffset(seed, 0);
		bitmap.shared = Arrays.copyOf(bitmap.shared, bitmap.highLowContainer.size() + (exact ? 0 : 4));
		return bitmap;
	}

	/** The operand matrix must cover both exactly sized flags and a false spare-capacity tail. */
	@ParameterizedTest
	@ValueSource(booleans = {false, true})
	void operandFixturesCoverExactAndSpareCapacity(final boolean exact) {
		final PersistentRoaringBitmap bitmap = operand(0, exact);
		final int size = bitmap.highLowContainer.size();
		if (exact) {
			assertEquals(size, bitmap.shared.length, "exact fixture must have exactly sized flags");
		} else {
			assertTrue(bitmap.shared.length > size, "spare fixture must have spare flag capacity");
		}
		assertLockstep(bitmap, "operand fixture");
	}

	/** Creates fresh operands so snapshots cannot inherit lending from an earlier test case. */
	private static List<PersistentRoaringBitmap> operands(final Operation operation, final boolean exact) {
		final int count = operation == Operation.CLONE ? 1 : 5;
		final List<PersistentRoaringBitmap> inputs = new ArrayList<>();
		for (int i = 0; i < count; i++) {
			final PersistentRoaringBitmap input = operand(operation == Operation.RANGE_AND ? 0 : i, exact);
			if (operation == Operation.RANGE_AND) {
				input.add((1 << 16) | (30 + i));
			}
			inputs.add(input);
		}
		return inputs;
	}

	/** Runs the selected operation; every in-place receiver is private to this invocation. */
	private static PersistentRoaringBitmap apply(
		final Operation operation, final List<PersistentRoaringBitmap> inputs, final int shape
	) {
		final PersistentRoaringBitmap left = inputs.get(0);
		final PersistentRoaringBitmap right = inputs.get(inputs.size() - 1);
		final PersistentRoaringBitmap[] array = inputs.toArray(PersistentRoaringBitmap[]::new);
		final PersistentRoaringBitmap receiver = new PersistentRoaringBitmap();
		if (shape > 0) {
			receiver.add(63 << 16 | 3);
		}
		if (shape == 2) {
			receiver.add(2 << 16 | 3, 9 << 16 | 3, 20 << 16 | 3);
		}
		return switch (operation) {
			case OR -> PersistentRoaringBitmap.or(left, right);
			case XOR -> PersistentRoaringBitmap.xor(left, right);
			case AND_NOT -> PersistentRoaringBitmap.andNot(left, right);
			case RANGE_AND -> {
				final PersistentRoaringBitmap result =
					PersistentRoaringBitmap.and(inputs.iterator(), 0L, 64L << 16);
				assertArrayEquals(operand(0, true).toArray(), result.toArray(), "range intersection contents");
				yield result;
			}
			case RANGE_OR -> PersistentRoaringBitmap.or(inputs.iterator(), 0L, 64L << 16);
			case RANGE_XOR -> PersistentRoaringBitmap.xor(inputs.iterator(), 0L, 64L << 16);
			case RANGE_AND_NOT -> PersistentRoaringBitmap.andNot(left, right, 0L, 64L << 16);
			case CLONE -> left.clone();
			case IN_PLACE_OR -> { receiver.or(left); yield receiver; }
			case IN_PLACE_XOR -> { receiver.xor(left); yield receiver; }
			case LAZY_OR -> { receiver.lazyor(left); receiver.repairAfterLazy(); yield receiver; }
			case NAIVE_LAZY_OR -> { receiver.naivelazyor(left); receiver.repairAfterLazy(); yield receiver; }
			case NAIVE_OR_ARRAY -> FastAggregation.naive_or(array);
			case NAIVE_OR_ITERATOR -> FastAggregation.naive_or(inputs.iterator());
			case NAIVE_XOR_ARRAY -> FastAggregation.naive_xor(array);
			case NAIVE_XOR_ITERATOR -> FastAggregation.naive_xor(inputs.iterator());
			case QUEUE_OR_ARRAY -> FastAggregation.priorityqueue_or(array);
			case QUEUE_OR_ITERATOR -> FastAggregation.priorityqueue_or(inputs.iterator());
			case QUEUE_XOR_ARRAY -> FastAggregation.priorityqueue_xor(array);
			case FOLD_OR_ARRAY -> FastAggregation.or(array);
			case FOLD_OR_ITERATOR -> FastAggregation.or(inputs.iterator());
			case FOLD_XOR_ARRAY -> FastAggregation.xor(array);
			case FOLD_XOR_ITERATOR -> FastAggregation.xor(inputs.iterator());
		};
	}

	/** Merges lend only source-only chunks; recomputed overlaps leave the operand privately owned. */
	@ParameterizedTest
	@EnumSource(value = Operation.class, names = {"IN_PLACE_OR", "IN_PLACE_XOR", "LAZY_OR", "NAIVE_LAZY_OR"})
	void mergesLendOnlyBorrowedContainers(final Operation operation) {
		for (int shape = 0; shape < 3; shape++) {
			final PersistentRoaringBitmap receiver = PersistentRoaringBitmap.bitmapOf(shape == 2 ? 1 << 16 : 1);
			final PersistentRoaringBitmap source = PersistentRoaringBitmap.bitmapOf(shape == 1 ? 1 << 16 : 2);
			final Container original = source.getContainerAtIndex(0);
			switch (operation) {
				case IN_PLACE_OR -> receiver.or(source);
				case IN_PLACE_XOR -> receiver.xor(source);
				case LAZY_OR -> receiver.lazyor(source);
				case NAIVE_LAZY_OR -> receiver.naivelazyor(source);
				default -> throw new IllegalArgumentException("Unsupported merge: " + operation);
			}
			assertEquals(shape != 0, source.lent, operation + ": lend only when a container is borrowed");
			if (shape == 0) {
				source.add(3);
				assertSame(original, source.getContainerAtIndex(0), "an unborrowed container needs no copy");
			} else {
				final int slot = receiver.highLowContainer.getIndex(source.getKeyAtIndex(0));
				assertSame(original, receiver.getContainerAtIndex(slot), "the source-only container is borrowed");
				assertTrue(receiver.isShared(slot), "the borrowed result slot must be flagged");
			}
		}
	}

	/** Pins both the array identity and every flag, including spare entries, for every input. */
	@ParameterizedTest(name = "{0} exact={1} shape={2}")
	@MethodSource("operations")
	void nonOwnersLeaveOperandArraysUntouched(final Operation operation, final boolean exact, final int shape) {
		final List<PersistentRoaringBitmap> inputs = operands(operation, exact);
		final boolean[][] references = inputs.stream().map(bitmap -> bitmap.shared).toArray(boolean[][]::new);
		final boolean[][] contents = inputs.stream().map(bitmap -> bitmap.shared.clone()).toArray(boolean[][]::new);
		final PersistentRoaringBitmap result = apply(operation, inputs, shape);
		assertTrue(inputs.stream().anyMatch(bitmap -> bitmap.lent), operation + ": no operand lent");
		assertLockstep(result, operation + ": result");
		for (int i = 0; i < inputs.size(); i++) {
			assertLockstep(inputs.get(i), operation + ": operand");
			for (int slot = 0; slot < result.highLowContainer.size(); slot++) {
				final int source = inputs.get(i).highLowContainer.getIndex(result.getKeyAtIndex(slot));
				if (source >= 0 && inputs.get(i).getContainerAtIndex(source) == result.getContainerAtIndex(slot)) {
					assertTrue(inputs.get(i).lent, operation + ": aliased operand must be lent");
					assertTrue(result.isShared(slot), operation + ": result alias must be flagged");
				}
			}
			assertSame(references[i], inputs.get(i).shared, operation + ": operand flag array replaced");
			assertArrayEquals(contents[i], inputs.get(i).shared, operation + ": operand flag contents changed");
		}
	}

	/** Adds an absent value in every existing chunk to exercise container copy-on-write. */
	private static void writeEveryChunk(final PersistentRoaringBitmap bitmap) {
		for (int i = 0; i < bitmap.highLowContainer.size(); i++) {
			bitmap.add((bitmap.getKeyAtIndex(i) << 16) | 0xFFFF);
		}
	}

	/** A subsequent owner mutation must leave every result container's values unchanged. */
	@ParameterizedTest(name = "{0} exact={1} shape={2}")
	@MethodSource("operations")
	void operandMutationPreservesResult(final Operation operation, final boolean exact, final int shape) {
		final List<PersistentRoaringBitmap> inputs = operands(operation, exact);
		final PersistentRoaringBitmap result = apply(operation, inputs, shape);
		final int[] expected = result.toArray();
		for (final PersistentRoaringBitmap input : inputs) {
			final boolean wasLent = input.lent;
			final int oldSize = input.highLowContainer.size();
			input.add(64 << 16);
			assertFalse(input.lent, "the next mutation must materialize lending");
			if (wasLent) {
				for (int i = 0; i < oldSize; i++) {
					assertTrue(input.shared[i], "unlend must protect every old slot");
				}
			}
			assertFalse(input.shared[oldSize], "a newly inserted container is owned");
			writeEveryChunk(input);
			assertArrayEquals(expected, result.toArray(), operation + ": operand mutation reached result");
		}
	}

	/** Mutating the private result must leave every input's values unchanged. */
	@ParameterizedTest(name = "{0} exact={1} shape={2}")
	@MethodSource("operations")
	void resultMutationPreservesOperands(final Operation operation, final boolean exact, final int shape) {
		final List<PersistentRoaringBitmap> inputs = operands(operation, exact);
		final int[][] expected = inputs.stream().map(PersistentRoaringBitmap::toArray).toArray(int[][]::new);
		final PersistentRoaringBitmap result = apply(operation, inputs, shape);
		writeEveryChunk(result);
		for (int i = 0; i < inputs.size(); i++) {
			assertArrayEquals(expected[i], inputs.get(i).toArray(), operation + ": result mutation reached operand");
		}
	}
	/** Mutation entry points, including delegating overloads and whole-state replacement. */
	enum Mutation {
		ADD, ADD_MANY, ADD_N, ADD_RANGE, ADD_INT_RANGE, CHECKED_ADD, CHECKED_REMOVE,
		REMOVE, REMOVE_RANGE, REMOVE_INT_RANGE, FLIP, FLIP_RANGE, FLIP_INT_RANGE,
		AND, AND_NOT, OR_NOT, OR, XOR, TRIM, CLEAR, REMOVE_RUNS, OPTIMIZE, APPEND,
		LAZY_OR, NAIVE_LAZY_OR, NAIVE_LAZY_OR_POLICY, REPAIR,
		DESERIALIZE, DESERIALIZE_BUFFER, DESERIALIZE_BYTES, READ_EXTERNAL
	}

	/** Every owner entry expands lending before a write, preserving a previously published peer. */
	@ParameterizedTest
	@EnumSource(Mutation.class)
	@SuppressWarnings("deprecation")
	void mutationEntriesUnlendAndPreservePeers(final Mutation mutation) throws Exception {
		final PersistentRoaringBitmap bitmap = operand(0, false);
		final PersistentRoaringBitmap peer = bitmap.clone();
		final int[] expected = peer.toArray();
		final PersistentRoaringBitmap incoming = operand(1, false);
		final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
		incoming.serialize(new DataOutputStream(bytes));
		switch (mutation) {
			case ADD -> bitmap.add((1 << 16) | 30);
			case ADD_MANY -> bitmap.add(new int[]{(1 << 16) | 30, (6 << 16) | 30});
			case ADD_N -> bitmap.addN(new int[]{(1 << 16) | 30, (6 << 16) | 30}, 0, 2);
			case ADD_RANGE -> bitmap.add((1L << 16) | 30, (7L << 16) | 40);
			case ADD_INT_RANGE -> bitmap.add((1 << 16) | 30, (7 << 16) | 40);
			case CHECKED_ADD -> bitmap.checkedAdd((1 << 16) | 30);
			case CHECKED_REMOVE -> bitmap.checkedRemove((1 << 16) | 7);
			case REMOVE -> bitmap.remove((1 << 16) | 7);
			case REMOVE_RANGE -> bitmap.remove((1L << 16) | 7, (7L << 16) | 21);
			case REMOVE_INT_RANGE -> bitmap.remove((1 << 16) | 7, (7 << 16) | 21);
			case FLIP -> bitmap.flip((1 << 16) | 7);
			case FLIP_RANGE -> bitmap.flip((1L << 16) | 7, (7L << 16) | 21);
			case FLIP_INT_RANGE -> bitmap.flip((1 << 16) | 7, (7 << 16) | 21);
			case AND -> bitmap.and(PersistentRoaringBitmap.bitmapOfRange(0, 7L << 16));
			case AND_NOT -> bitmap.andNot(PersistentRoaringBitmap.bitmapOfRange(0, 7L << 16));
			case OR_NOT -> bitmap.orNot(incoming, 7L << 16);
			case OR -> bitmap.or(incoming);
			case XOR -> bitmap.xor(incoming);
			case TRIM -> bitmap.trim();
			case CLEAR -> bitmap.clear();
			case REMOVE_RUNS -> bitmap.removeRunCompression();
			case OPTIMIZE -> bitmap.runOptimize();
			case APPEND -> bitmap.append((char) 64, new ArrayContainer(new char[]{7}));
			case LAZY_OR -> bitmap.lazyor(incoming);
			case NAIVE_LAZY_OR -> bitmap.naivelazyor(incoming);
			case NAIVE_LAZY_OR_POLICY -> bitmap.naivelazyor(incoming, false);
			case REPAIR -> bitmap.repairAfterLazy();
			case DESERIALIZE -> bitmap.deserialize(new DataInputStream(new ByteArrayInputStream(bytes.toByteArray())));
			case DESERIALIZE_BUFFER -> bitmap.deserialize(ByteBuffer.wrap(bytes.toByteArray()));
			case DESERIALIZE_BYTES -> bitmap.deserialize(
				new DataInputStream(new ByteArrayInputStream(bytes.toByteArray())), new byte[8192]
			);
			case READ_EXTERNAL -> {
				final ByteArrayOutputStream external = new ByteArrayOutputStream();
				final ObjectOutputStream output = new ObjectOutputStream(external);
				incoming.writeExternal(output);
				output.flush();
				bitmap.readExternal(new ObjectInputStream(new ByteArrayInputStream(external.toByteArray())));
			}
		}
		assertFalse(bitmap.lent, mutation + ": pending lending survived mutation");
		assertLockstep(bitmap, mutation.toString());
		assertArrayEquals(expected, peer.toArray(), mutation + ": mutation reached peer");
		if (mutation == Mutation.CLEAR || mutation.name().startsWith("DESERIALIZE") ||
			mutation == Mutation.READ_EXTERNAL) {
			assertArrayEquals(new boolean[bitmap.shared.length], bitmap.shared, "replacement must be all-owned");
		}
		bitmap.repairAfterLazy();
		writeEveryChunk(bitmap);
		assertArrayEquals(expected, peer.toArray(), mutation + ": later write reached peer");
	}

	/** All-owned builders cover their live containers before exposing a result or invoking repair. */
	@Test
	void ownedBuildersKeepFlagsSized() {
		final PersistentRoaringBitmap source = operand(0, false);
		final long end = 64L << 16;
		final long[] words = new long[12 * 1024];
		for (int i = 0; i < 12; i++) {
			words[i * 1024] = 7;
		}
		final ByteBuffer bytes = ByteBuffer.allocate(words.length * Long.BYTES).order(java.nio.ByteOrder.LITTLE_ENDIAN);
		bytes.asLongBuffer().put(words);
		final List<PersistentRoaringBitmap> results = List.of(
			PersistentRoaringBitmap.addOffset(source, 0), PersistentRoaringBitmap.addOffset(source, 1),
			PersistentRoaringBitmap.add(source, 0L, 1L), PersistentRoaringBitmap.add(source, 0L, end),
			PersistentRoaringBitmap.flip(source, 0L, end),
			PersistentRoaringBitmap.remove(source, 0L, 1L), PersistentRoaringBitmap.remove(source, 0L, 7L << 16),
			PersistentRoaringBitmap.orNot(source, new PersistentRoaringBitmap(), end),
			PersistentRoaringBitmap.lazyor(source, new PersistentRoaringBitmap()), source.limit(30),
			source.selectRange(0, end), FastAggregation.horizontal_or(List.of(source)),
			FastAggregation.horizontal_or(source), FastAggregation.horizontal_xor(source),
			BitSetUtil.bitmapOf(words), BitSetUtil.bitmapOf(bytes, new long[1024])
		);
		for (final PersistentRoaringBitmap result : results) {
			assertLockstep(result, "all-owned builder");
			assertFalse(result.lent);
			assertArrayEquals(new boolean[result.shared.length], result.shared);
		}
	}

	/** Consuming lent temporaries transfers effective carry flags and protects either accumulator. */
	@ParameterizedTest(name = "bitmapOnRight={0}")
	@ValueSource(booleans = {false, true})
	void consumedInputsTransferEffectiveFlags(final boolean bitmapOnRight) {
		final PersistentRoaringBitmap left = new PersistentRoaringBitmap();
		final PersistentRoaringBitmap right = new PersistentRoaringBitmap();
		left.add(7, (1 << 16) | 7, (3 << 16) | 7);
		right.add((1 << 16) | 14, (2 << 16) | 14, (4 << 16) | 14);
		final PersistentRoaringBitmap dense = bitmapOnRight ? right : left;
		for (int i = 0; i < 5000; i++) {
			dense.add((1 << 16) | (i * 3));
		}
		final PersistentRoaringBitmap leftPeer = left.clone();
		final PersistentRoaringBitmap rightPeer = right.clone();
		final int[] leftBefore = leftPeer.toArray();
		final int[] rightBefore = rightPeer.toArray();
		assertTrue(left.lent && right.lent);
		assertArrayEquals(new boolean[left.shared.length], left.shared);
		assertArrayEquals(new boolean[right.shared.length], right.shared);

		final PersistentRoaringBitmap result = PersistentRoaringBitmap.lazyorfromlazyinputs(left, right);

		assertFalse(left.lent || right.lent);
		assertLockstep(result, "consumed result");
		assertTrue(result.isShared(0));
		assertFalse(result.isShared(1), "combined accumulator is owned");
		assertTrue(result.isShared(2));
		assertTrue(result.isShared(3));
		assertTrue(result.isShared(4));
		assertSame(leftPeer.getContainerAtIndex(0), result.getContainerAtIndex(0));
		assertSame(rightPeer.getContainerAtIndex(1), result.getContainerAtIndex(2));
		assertSame(leftPeer.getContainerAtIndex(2), result.getContainerAtIndex(3));
		assertSame(rightPeer.getContainerAtIndex(2), result.getContainerAtIndex(4));
		assertArrayEquals(leftBefore, leftPeer.toArray(), "consumption mutated the left peer");
		assertArrayEquals(rightBefore, rightPeer.toArray(), "consumption mutated the right peer");
		result.repairAfterLazy();
		writeEveryChunk(result);
		assertArrayEquals(leftBefore, leftPeer.toArray());
		assertArrayEquals(rightBefore, rightPeer.toArray());
	}

}
