```json
{
  "operation": "OVERALL",
  "start": 177425818030395,
  "steps": [
    {
      "operation": "PLANNING",
      "start": 177425818039060,
      "steps": [
        {
          "operation": "PLANNING_INDEX_USAGE",
          "start": 177425818040553,
          "steps": [ ],
          "arguments": [ ],
          "spentTime": "10129"
        },
        {
          "operation": "PLANNING_FILTER",
          "start": 177425818096047,
          "steps": [
            {
              "operation": "PLANNING_FILTER_ALTERNATIVE",
              "start": 177425818101938,
              "steps": [ ],
              "arguments": [
                "Index type: GLOBAL, estimated costs 585"
              ],
              "spentTime": "953217"
            }
          ],
          "arguments": [
            "Selected index: Index type: GLOBAL, estimated costs 585"
          ],
          "spentTime": "969337"
        },
        {
          "operation": "PLANNING_SORT",
          "start": 177425819073169,
          "steps": [ ],
          "arguments": [ ],
          "spentTime": "184534"
        },
        {
          "operation": "PLANNING_EXTRA_RESULT_FABRICATION",
          "start": 177425819259727,
          "steps": [ ],
          "arguments": [ ],
          "spentTime": "31859"
        }
      ],
      "arguments": [ ],
      "spentTime": "1256193"
    },
    {
      "operation": "EXECUTION",
      "start": 177425819296625,
      "steps": [
        {
          "operation": "EXECUTION_FILTER",
          "start": 177425819301374,
          "steps": [ ],
          "arguments": [ ],
          "spentTime": "45776"
        },
        {
          "operation": "EXECUTION_SORT_AND_SLICE",
          "start": 177425819347701,
          "steps": [ ],
          "arguments": [ ],
          "spentTime": "500843"
        }
      ],
      "arguments": [ ],
      "spentTime": "616930"
    }
  ],
  "arguments": [ ],
  "spentTime": "1883812"
}
```