evita.updateCatalog(
	"testCatalog", session -> {
		return session.deleteEntities(
			query(
				collection("Brand"),
				filterBy(entityPrimaryKeyInSet(1, 2))
			)
		);
	}
);