```json
{
  "data": [
    {
      "primaryKey": 108528,
      "type": "Product",
      "version": 1,
      "allLocales": [
        "en"
      ],
      "attributes": {
        "global": {
          "code": "samsung-galaxy-watch-4"
        }
      },
      "groups": [
        {
          "referencedPrimaryKey": 112769,
          "referencedEntity": {
            "primaryKey": 112769,
            "type": "Group",
            "version": 1,
            "allLocales": [
              "en"
            ],
            "attributes": {
              "global": {
                "code": "special-offer-group"
              }
            },
            "tags": [
              {
                "referencedPrimaryKey": 11,
                "referencedEntity": {
                  "primaryKey": 11,
                  "type": "Tag",
                  "version": 1,
                  "allLocales": [
                    "cs",
                    "de",
                    "en"
                  ],
                  "attributes": {
                    "global": {
                      "code": "special-offer"
                    }
                  },
                  "categories": [
                    {
                      "referencedPrimaryKey": 8
                    }
                  ]
                },
                "groupEntity": {
                  "primaryKey": 0,
                  "type": "tagCategory"
                }
              }
            ]
          }
        }
      ]
    }
  ],
  "type": "PAGE",
  "totalRecordCount": 1,
  "first": true,
  "last": true,
  "hasPrevious": false,
  "hasNext": false,
  "singlePage": true,
  "empty": false,
  "pageSize": 20,
  "pageNumber": 1,
  "lastPageNumber": 1,
  "firstPageItemNumber": 0,
  "lastPageItemNumber": 1
}
```