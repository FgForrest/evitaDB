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

package io.evitadb.spike;

import io.evitadb.externalApi.http.Http2ConnectionMonitor;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelOutboundHandlerAdapter;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.ChannelPromise;
import io.netty.channel.embedded.EmbeddedChannel;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;
import org.openjdk.jmh.annotations.Warmup;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

/**
 * Measures what {@link Http2ConnectionMonitor}'s per-connection handler costs a connection it is watching.
 *
 * The handler sits in **every** child channel pipeline, between the TLS handler and the HTTP/2 codec, and every
 * inbound and outbound byte of every connection passes through it. That is a place where a cheap-looking design
 * mistake is expensive, so the two claims the implementation rests on are measured here rather than argued:
 *
 * 1. **The inbound walk is O(frames), not O(bytes).** It reads the nine-byte frame header one byte at a time - so it
 *    survives frames split across arbitrary socket reads - and then skips the whole payload in a single index jump.
 *    If that holds, `dataFrames` costs the same at a 16 KiB payload as at a 256 B one, and the sweep over
 *    {@link #payloadSize} shows a flat line. If it ever regresses to a per-byte walk, this benchmark is where it
 *    shows up first.
 * 2. **Neither direction allocates.** Run with `-prof gc` and compare `gc.alloc.rate.norm` between the
 *    {@link #monitored} arms. Expect a residual of a thousandth of a byte per operation rather than a flat zero -
 *    that is fixed background allocation amortized over fewer operations in the slower arm, and it scales with the
 *    arm's slowdown rather than with anything the handler does. A per-frame allocation would show up as whole bytes.
 *
 * **How to read it.** Every operation is an A/B: `monitored = true` puts the monitor in the pipeline,
 * `monitored = false` runs the identical pipeline without it. Only the **difference** between the two arms is the
 * handler's cost - the absolute numbers are dominated by `EmbeddedChannel` plumbing that a real connection does not
 * have.
 *
 * **The two directions have different denominators.** The inbound ops ({@link #dataFrames}, {@link #resetFrames})
 * push {@link #FRAMES_PER_INVOCATION} frames through the walker, which parses every one of their headers - divide by
 * that for a per-frame figure. {@link #outboundFrames} does **not**: it hands the whole buffer to a single
 * `writeOutbound`, so the recognition runs **once per write** and rejects on the first frame's type byte, whatever
 * else the buffer holds behind it. Its figure is therefore per-write and must not be divided.
 *
 * Measured results are transcribed in
 * `documentation/performance/individual/Http2ConnectionMonitorBenchmark/README.md`.
 *
 * **What is deliberately not measured.** The reporting path (log line plus JFR event) is throttled to once per minute
 * per peer and would otherwise dominate a benchmark that provokes it thousands of times a second - so the monitor
 * used here raises its reporting threshold out of reach. What remains is exactly the code that runs on every
 * connection all the time, which is the part whose cost anybody has to care about.
 *
 * Run through JMH's own runner (the benchmarks jar uses a custom main):
 * `java -cp evita_test/evita_performance_tests/target/benchmarks.jar org.openjdk.jmh.Main
 * io\.evitadb\.spike\.Http2ConnectionMonitorBenchmark -prof gc`.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
// the walker needs a long warm-up before it settles - with three warm-up iterations the first measured iteration
// still came in ~55% above the other four, which is enough to swamp the whole measurement in its own error bar
@Warmup(iterations = 8, time = 2)
@Measurement(iterations = 6, time = 2)
@Fork(2)
public class Http2ConnectionMonitorBenchmark {
	/**
	 * Number of HTTP/2 frames pushed through the pipeline per invocation. Divide any reported figure by this for a
	 * per-frame number.
	 */
	private static final int FRAMES_PER_INVOCATION = 16;
	/**
	 * The HTTP/2 connection preface (RFC 9113 §3.4). Written once per iteration so the walker enters framing mode;
	 * it must NOT be part of the repeated buffer, or the second invocation would try to parse it as a frame header.
	 */
	private static final byte[] CONNECTION_PREFACE =
		"PRI * HTTP/2.0\r\n\r\nSM\r\n\r\n".getBytes(StandardCharsets.US_ASCII);
	/**
	 * Size of the synthetic HEADERS frame preceding each DATA frame - roughly what a compressed gRPC request header
	 * block comes to.
	 */
	private static final int HEADERS_FRAME_SIZE = 64;

	/**
	 * Whether the monitor is in the pipeline. The difference between the two arms IS the measurement.
	 */
	@Param({"true", "false"})
	private boolean monitored;

	/**
	 * Payload of each DATA frame. Sweeping it is what proves the walk is per-frame rather than per-byte.
	 */
	@Param({"256", "16384"})
	private int payloadSize;

	private EmbeddedChannel channel;
	private ByteBuf dataFrameStream;
	private ByteBuf resetFrameStream;
	private ByteBuf outboundFrameStream;

	/**
	 * Builds a raw HTTP/2 frame - 3B payload length, 1B type, 1B flags, 4B stream identifier, payload.
	 */
	private static byte[] frame(int type, int streamId, int payloadLength) {
		final byte[] result = new byte[9 + payloadLength];
		result[0] = (byte) (payloadLength >>> 16);
		result[1] = (byte) (payloadLength >>> 8);
		result[2] = (byte) payloadLength;
		result[3] = (byte) type;
		result[5] = (byte) (streamId >>> 24);
		result[6] = (byte) (streamId >>> 16);
		result[7] = (byte) (streamId >>> 8);
		result[8] = (byte) streamId;
		return result;
	}

	/**
	 * Concatenates the passed frames into one buffer, the way a socket read delivers several frames at once.
	 */
	private static ByteBuf concat(byte[]... frames) {
		int length = 0;
		for (byte[] frame : frames) {
			length += frame.length;
		}
		final byte[] result = new byte[length];
		int offset = 0;
		for (byte[] frame : frames) {
			System.arraycopy(frame, 0, result, offset, frame.length);
			offset += frame.length;
		}
		return Unpooled.wrappedBuffer(result);
	}

	@Setup(Level.Trial)
	public void setUpBuffers() {
		// a request-shaped stream: HEADERS followed by DATA, repeated - whole frames only, so the same buffer can be
		// replayed indefinitely without ever desynchronising the walker
		final byte[][] dataFrames = new byte[FRAMES_PER_INVOCATION][];
		for (int i = 0; i < FRAMES_PER_INVOCATION; i += 2) {
			dataFrames[i] = frame(0x01, 2 * i + 1, HEADERS_FRAME_SIZE);
			dataFrames[i + 1] = frame(0x00, 2 * i + 1, this.payloadSize);
		}
		this.dataFrameStream = concat(dataFrames);

		// the cancellation-storm shape: nothing but RST_STREAM frames, which is the walker's worst case per byte
		// because every frame is header-only and the payload skip never amortizes anything
		final byte[][] resetFrames = new byte[FRAMES_PER_INVOCATION][];
		for (int i = 0; i < FRAMES_PER_INVOCATION; i++) {
			resetFrames[i] = frame(0x03, 2 * i + 1, 4);
		}
		this.resetFrameStream = concat(resetFrames);

		// what the server writes back - the buffers the outbound GOAWAY recognition has to reject one by one
		final byte[][] outboundFrames = new byte[FRAMES_PER_INVOCATION][];
		for (int i = 0; i < FRAMES_PER_INVOCATION; i++) {
			outboundFrames[i] = frame(0x00, 2 * i + 1, this.payloadSize);
		}
		this.outboundFrameStream = concat(outboundFrames);
	}

	@Setup(Level.Iteration)
	public void setUpChannel() {
		this.channel = new EmbeddedChannel();
		final ChannelPipeline pipeline = this.channel.pipeline();
		// terminal handlers swallow the buffers, so nothing queues up inside EmbeddedChannel across invocations and
		// the very same buffer can be replayed every invocation without any per-invocation allocation
		pipeline.addLast(new SwallowingOutboundHandler());
		// stands in for Armeria's protocol handler purely so the real install() has something to insert in front of -
		// the placement logic is exercised here rather than bypassed
		pipeline.addLast(new Http2CodecStub());
		pipeline.addLast(new SwallowingInboundHandler());
		if (this.monitored) {
			new NonReportingMonitor().install(pipeline);
		}
		// prime the walker past the connection preface exactly once - it must not be part of the replayed buffer, or
		// the second invocation would try to read it as a frame header
		this.channel.writeInbound(Unpooled.wrappedBuffer(CONNECTION_PREFACE));
	}

	@TearDown(Level.Iteration)
	public void tearDownChannel() {
		// the replayed buffers are owned by the trial, not the channel, so only the channel itself is closed here
		this.channel.close();
	}

	/**
	 * The ordinary request shape - HEADERS plus DATA. Compare across {@link #payloadSize} to see whether the walk is
	 * per-frame or per-byte.
	 */
	@Benchmark
	public void dataFrames() {
		this.channel.writeInbound(this.dataFrameStream);
	}

	/**
	 * A stream of nothing but `RST_STREAM` frames - the highest frame-per-byte density the walker can be handed, and
	 * the only op in which `recordResetFrame` (and its `System.nanoTime()` call) runs on every frame.
	 */
	@Benchmark
	public void resetFrames() {
		this.channel.writeInbound(this.resetFrameStream);
	}

	/**
	 * The response path - every outbound buffer is offered to the `GOAWAY` recognition, which must reject it on the
	 * frame-type byte.
	 */
	@Benchmark
	public void outboundFrames() {
		this.channel.writeOutbound(this.outboundFrameStream);
	}

	/**
	 * Monitor whose reporting threshold is out of reach, so the throttled reporting path never runs and the
	 * measurement is of the always-on walk alone.
	 */
	private static class NonReportingMonitor extends Http2ConnectionMonitor {

		NonReportingMonitor() {
			super(0, 60);
		}

		@Override
		protected int reportingThreshold() {
			return Integer.MAX_VALUE;
		}

	}

	/**
	 * Placeholder for Armeria's HTTP/2 protocol handler. Only its class name matters - `install()` inserts the
	 * monitor in front of the first handler whose simple name starts with `Http2`.
	 */
	private static class Http2CodecStub extends ChannelDuplexHandler {
	}

	/**
	 * Consumes and releases inbound buffers so {@link EmbeddedChannel}'s inbound queue never grows.
	 */
	private static class SwallowingInboundHandler extends ChannelInboundHandlerAdapter {

		@Override
		public void channelRead(ChannelHandlerContext ctx, Object msg) {
			// deliberately neither forwarded nor released - the buffers are long-lived unpooled heap buffers owned
			// by the benchmark and replayed on every invocation
		}
	}

	/**
	 * Consumes and releases outbound buffers so {@link EmbeddedChannel}'s outbound queue never grows.
	 */
	private static class SwallowingOutboundHandler extends ChannelOutboundHandlerAdapter {

		@Override
		public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) {
			// see SwallowingInboundHandler - the buffer is owned by the benchmark and outlives the write
			promise.setSuccess();
		}
	}

}
