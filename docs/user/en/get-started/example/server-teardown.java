Runtime.getRuntime()
    .addShutdownHook(
		new Thread(() -> { 
			externalApiServer.close();
			evita.close();
		})
    );