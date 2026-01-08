---
title: Cron expressions
perex: |
  Reference documentation for cron expression syntax used in evitaDB for scheduling
  recurring tasks such as automatic backups.
date: '30.12.2024'
author: 'Ing. Jan Novotný'
proofreading: 'done'
---

Cron expressions are a powerful and flexible way to define schedules for recurring tasks. evitaDB uses
a standard 6-field cron format to configure when automated operations (like backups) should run.

## Expression format

A cron expression consists of **6 space-separated fields**:

```plain
┌───────────── second (0-59)
│ ┌───────────── minute (0-59)
│ │ ┌───────────── hour (0-23)
│ │ │ ┌───────────── day of month (1-31)
│ │ │ │ ┌───────────── month (1-12 or JAN-DEC)
│ │ │ │ │ ┌───────────── day of week (0-7 or SUN-SAT)
│ │ │ │ │ │              (0 and 7 both represent Sunday)
│ │ │ │ │ │
* * * * * *
```

## Field values

Each field accepts specific values and special characters:

| Field        | Allowed values  | Allowed special characters |
|--------------|-----------------|----------------------------|
| Second       | 0-59            | `*` `,` `-` `/`            |
| Minute       | 0-59            | `*` `,` `-` `/`            |
| Hour         | 0-23            | `*` `,` `-` `/`            |
| Day of month | 1-31            | `*` `,` `-` `/`            |
| Month        | 1-12 or JAN-DEC | `*` `,` `-` `/`            |
| Day of week  | 0-7 or SUN-SAT  | `*` `,` `-` `/`            |

## Special characters

### Asterisk (`*`)

Matches **all values** in the field's range. For example, `*` in the hour field means "every hour".

```plain
0 0 * * * *    # Every hour at minute 0, second 0
```

### Comma (`,`)

Specifies a **list of values**. For example, `1,15` in the day-of-month field means "on the 1st and 15th".

```plain
0 0 0 1,15 * *    # At midnight on the 1st and 15th of each month
```

### Hyphen (`-`)

Defines an **inclusive range**. For example, `9-17` in the hour field means "from 9 AM to 5 PM".

```plain
0 0 9-17 * * *    # Every hour from 9 AM to 5 PM
```

### Slash (`/`)

Specifies **step values**. For example, `*/15` in the minute field means "every 15 minutes".

```plain
0 */15 * * * *    # Every 15 minutes
0 0-30/10 * * * * # At minutes 0, 10, 20, 30
```

## Named values

### Month names

You can use three-letter month abbreviations (case-insensitive):

`JAN`, `FEB`, `MAR`, `APR`, `MAY`, `JUN`, `JUL`, `AUG`, `SEP`, `OCT`, `NOV`, `DEC`

```plain
0 0 0 1 JAN-MAR *    # At midnight on the 1st, January through March
```

### Day of week names

You can use three-letter day abbreviations (case-insensitive):

`SUN`, `MON`, `TUE`, `WED`, `THU`, `FRI`, `SAT`

Note: Both `0` and `7` represent Sunday to maintain compatibility with different cron conventions.

```plain
0 0 9 * * MON-FRI    # At 9 AM on weekdays
```

## Common examples

### Every minute

```plain
0 * * * * *
```

### Every hour

```plain
0 0 * * * *
```

### Every day at midnight

```plain
0 0 0 * * *
```

### Every day at 3:30 AM

```plain
0 30 3 * * *
```

### Every weekday at 8:00 AM

```plain
0 0 8 * * MON-FRI
```

### Every 15 minutes

```plain
0 */15 * * * *
```

### Every 6 hours

```plain
0 0 */6 * * *
```

### First day of every month at midnight

```plain
0 0 0 1 * *
```

### Every Sunday at 2:00 AM

```plain
0 0 2 * * SUN
```

### At 10:15 AM on the 1st and 15th of each month

```plain
0 15 10 1,15 * *
```

### Every 30 minutes during business hours on weekdays

```plain
0 0,30 9-17 * * MON-FRI
```

### Complex example: Every 10 seconds between 8 AM and 6 PM

```plain
*/10 * 8-18 * * *
```

## Day of month and day of week interaction

When both day-of-month and day-of-week fields contain non-wildcard values, the schedule triggers
when **both** conditions are met. This is useful for scenarios like "the 13th that falls on a Friday":

```plain
0 0 0 13 * FRI    # Friday the 13th at midnight
```

<Note type="warning">

<NoteTitle toggles="false">

##### Timezone considerations

</NoteTitle>

Cron expressions are evaluated against the server's configured timezone. When scheduling tasks that
should run at specific local times, ensure your server timezone is correctly configured. Be aware
that daylight saving time transitions may affect scheduling around the transition hours.

</Note>