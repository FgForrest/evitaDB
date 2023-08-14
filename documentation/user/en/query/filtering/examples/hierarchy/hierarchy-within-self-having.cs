EvitaResponse<SealedEntity> entities = evita.QueryCatalog(
	"evita",
	session => session.QuerySealedEntity(
        Query(
        	Collection("Category"),
        	FilterBy(
        		HierarchyWithinSelf(
        			AttributeEquals("code", "accessories"),
        			Having(
        				Or(
        					AttributeIs("validity", Null),
        					AttributeInRange(
        						"validity", 
        						DateTimeOffset.Parse("2023-10-01T01:00:00-01:00", DateTimeFormatInfo.InvariantInfo)
        					)
        				)
        			)
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