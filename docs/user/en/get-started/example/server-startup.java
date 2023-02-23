final Evita evita = new Evita(
	EvitaConfiguration.builder()
		.server(
			// configure additional server options, or let the defaults apply
			ServerOptions.builder()
				.closeSessionsAfterSecondsOfInactivity(60)
				.build()
		)
		.storage(
			// configure additional storage options, or let the defaults apply
			StorageOptions.builder()
				.storageDirectory(Path.of("/data"))
				.build()
		)
		.cache(
			// configure additional cache options, or let the defaults apply
			CacheOptions.builder()
				.enabled(true)
				.build()
		)
		.build()
);