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

AtomicReference<String> catalogNameRef = new AtomicReference<>("evita");
AtomicReference<String> sessionIdRef = new AtomicReference<>();

final ManagedChannel channel;
try {
	channel = NettyChannelBuilder.forAddress("demo.evitadb.io", 5555)
		.sslContext(SslContextBuilder.forClient()
		.applicationProtocolConfig(
			new ApplicationProtocolConfig(
			Protocol.ALPN,
			SelectorFailureBehavior.NO_ADVERTISE,
			SelectedListenerFailureBehavior.ACCEPT,
			ApplicationProtocolNames.HTTP_2)
		).build())
		.intercept(new ClientInterceptor() {
			@Override
			public <ReqT, RespT> ClientCall<ReqT, RespT> interceptCall(MethodDescriptor<ReqT, RespT> methodDescriptor, CallOptions callOptions, Channel channel) {
				return new ForwardingClientCall.SimpleForwardingClientCall<>(channel.newCall(methodDescriptor, callOptions)) {
					@Override
					public void start(Listener<RespT> listener, Metadata metadata) {
						if (sessionIdRef.get() != null) {
							metadata.put(Metadata.Key.of("sessionId", Metadata.ASCII_STRING_MARSHALLER), sessionIdRef.get());
							metadata.put(Metadata.Key.of("catalogName", Metadata.ASCII_STRING_MARSHALLER), catalogNameRef.get());
						}
						super.start(listener, metadata);
					}
				};
			}
		}
		).build();
} catch (Exception ex) {
	throw new RuntimeException(ex);
}

final EvitaServiceGrpc.EvitaServiceBlockingStub evitaBlockingStub = EvitaServiceGrpc.newBlockingStub(channel);
final GrpcEvitaSessionResponse sessionResponse = evitaBlockingStub.createReadOnlySession(GrpcEvitaSessionRequest.newBuilder()
	.setCatalogName(catalogNameRef.get())
	.build());
	sessionIdRef.set(sessionResponse.getSessionId());
