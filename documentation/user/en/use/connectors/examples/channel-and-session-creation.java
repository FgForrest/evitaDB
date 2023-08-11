AtomicReference<String> catalogNameRef = new AtomicReference<>("evita");
AtomicReference<String> sessionIdRef = new AtomicReference<>();

final ManagedChannel channel;
try {
	channel = NettyChannelBuilder.forAddress("demo.evitadb.io", 5556)
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