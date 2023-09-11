EvitaResponse<ISealedEntity> entities = evita.QueryCatalog(
	"evita",
	session => session.QuerySealedEntity(
        Query(
        	Collection("Product"),
        	FilterBy(
        		AttributeInRange(
        			"validity", 
        			DateTimeOffset.Parse("2023-12-05T12:00:00+01:00", DateTimeFormatInfo.InvariantInfo)
        		)
        	),
        	Require(
        		EntityFetch(
        			AttributeContent("code")
        		)
        	)
        )
	)
);