evita.updateCatalog(
	catalogName, session -> {
		return session.deleteEntity(
			"brand",
			1
		);
	}
);