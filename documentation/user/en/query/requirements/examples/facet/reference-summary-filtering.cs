EvitaResponse<ISealedEntity> entities = evita.QueryCatalog(
	"evita",
	session => session.QuerySealedEntity(
        Query(
        	Collection("Product"),
        	FilterBy(
        		AttributeEquals("status", "ACTIVE")
        	),
        	Require(
        		ReferenceSummary(
        			Counts,
        			FilterBy(
        				AttributeContains("code", "ar")
        			),
        			FilterGroupBy(
        				AttributeStartsWith("code", "o")
        			),
        			EntityFetch(
        				AttributeContent("code")
        			),
        			EntityGroupFetch(
        				AttributeContent("code")
        			)
        		)
        	)
        )
	)
);