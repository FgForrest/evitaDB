evita.updateCatalog(
	catalogName, session -> {
		return session.deleteEntities(
			query(
				collection("brand"),
				filterBy(entityPrimaryKeyInSet(1, 2))
			)
		);
	}
);