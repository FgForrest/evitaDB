EvitaResponse<ISealedEntity> entities = evita.QueryCatalog(
	"evita",
	session => session.QuerySealedEntity(
        Query(
        	Collection("Product"),
        	FilterBy(
        		And(
        			EntityPrimaryKeyInSet(110066, 106742, 110513),
        			EntityPrimaryKeyInSet(110066, 106742),
        			EntityPrimaryKeyInSet(107546, 106742, 107546)
        		)
        	)
        )
	)
);