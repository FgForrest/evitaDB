evita.updateCatalog(
	catalogName, session -> {
		return session.deleteEntityAndItsHierarchy(
			"category",
			1
		);
	}
);