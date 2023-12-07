evita.UpdateCatalog(
	"testCatalog", session => {
		return session.DeleteEntities(
			Query(
				Collection("Brand"),
				FilterBy(EntityPrimaryKeyInSet(1, 2))
			)
		);
	}
);