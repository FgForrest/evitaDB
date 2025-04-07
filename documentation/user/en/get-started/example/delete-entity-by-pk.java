evita.updateCatalog(
	"evita", session -> {
		return session.deleteEntity(
			"Brand",
			2
		);
	}
);
