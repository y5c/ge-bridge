# GE Bridge

Writes your Grand Exchange offers, fills and account state to files on your own computer, so your own
tools (spreadsheets, scripts, trade journals) can use them.

- **Local only.** It makes no network requests; nothing leaves your computer.
- **Read-only.** It never places, changes, collects or cancels an offer, and it sends no input to the game.
- **Exact times.** Every offer change is written the moment the client sees it. Fills that happen while
  you are logged out (or trading on mobile) are written at your next login and marked as offline, with
  the window they happened in.

## Where the files go

```
~/.runelite/plugin-data/ge-bridge/<account id>/
  events.jsonl                 one line per change, appended
  events-<UTC time>.jsonl      older event logs (the newest six are kept)
  state.json                   the current snapshot
  account.json                 quest states and achievement diary completion
```

`<account id>` is RuneLite's numeric account hash, so several accounts on one computer each get their
own folder.

## events.jsonl

One JSON object per line. When the file reaches the size limit (settings), it is renamed to
`events-<yyyyMMddTHHmmss>.jsonl` and a new one is started. A rename keeps the file's identity, so a reader
that was part-way through can finish it under its new name.

Every line has:

| field | meaning |
|---|---|
| `ts` | ISO-8601 UTC time the client saw the change |
| `t` | the same, in epoch milliseconds |
| `type` | see below |

Offer lines also have `slot` (0–7), `itemId`, `item`, `side` (`buy`/`sell`), `state` (the client's offer
state, e.g. `BUYING`), `price` (the listed price), `total`, `done` (quantity filled so far) and `spent` (gp
moved so far).

| type | when | extra fields |
|---|---|---|
| `placed` | a new offer appears in a slot | |
| `fill` | the filled quantity goes up | `qty` (units in this fill), `gp` (gp moved in this fill) |
| `completed` | the offer is fully bought or sold | |
| `cancelled` | the offer is aborted | |
| `cleared` | the offer leaves its slot (collected) | `unobserved: true` if it left without the plugin seeing it finish, so later fills may be missing |
| `collected` | items or coins are taken out of the slot's collection box while the offer is still there (e.g. collecting part of a filling bid) | `qty` (the offer's own item: units bought, or unsold units returned), `coins`, `items`, and running totals `collectedQty`, `collectedCoins` |
| `overflow` | changes queued before the account loaded exceeded the safety limit | `dropped` |
| `decant` | potions of one kind swap for other dose sizes in one inventory change with the doses unchanged (Bob Barter's decanting, or combining by hand) | `potion`, `doses`, `from` and `to` (each `id`, `name`, `quantity`) |
| `history` | the Grand Exchange trade history screen is open and its contents changed | `rows`: the screen's list as shown (`i`, and `itemId`/`itemName`/`quantity` for item icons, `text` for text), recorded unparsed |
| `baseline` | first ever sight of a slot (no earlier memory): records what is there, implies no fill | |
| `anomaly` | the filled quantity went down (should not happen) | `note` |
| `sink` | Jagex's GE item sink bought the item (see below) | `itemSink: true`; once `state` is `SOLD`, also `qty` (always 1), `gp` and `tax` |
| `login`, `logout`, `exit`, `start`, `stop` | session changes (`exit` = the client was closed while logged in) | `world`, `version` |

**Item-sink sales.** Jagex buys some high-value items (raid uniques, godswords, zenyte jewellery and others) from
sell offers with GE tax money. The client shows such a sale in a slot whose offer is empty, from separate
`ge_itemsink_*` variables, so it never appears as an offer change. GE Bridge reads those variables each tick and
writes a `sink` line (one unit, `side: sell`, `state` `SELLING` or `SOLD` as the game labels it). A later `cleared`
line with `itemSink: true` means the sale was collected. The sink buys as the sell offer is posted, so there is
normally no `placed` line for that offer.

Any offer line seen first at login, for a change made while you were away, also carries `offline: true`
and `since`: the last time the slot was seen before that login. The change happened somewhere between
`since` and `ts`.

## state.json

Rewritten atomically (write, then rename): when something changes (at most once a second), and at the
save interval while logged in. The field names follow the long-standing Local Data Exporter format, so
tools written for it read this file too.

| field | meaning |
|---|---|
| `schema`, `version` | file format (1) and plugin version |
| `timestamp`, `timestampIso` | when this file was written |
| `rsn`, `accountHash`, `world`, `gameState`, `loggedIn`, `lastLogin`, `lastLogout` | the session |
| `heartbeatSeconds`, `sections` | the save interval, and which optional sections are switched on |
| `health` | since the plugin started: `eventsWritten`, `writeFailures`, `readFailures` (potion storage or quest/diary data the client would not give), `anomalies`, `reconcileFixes` (slots the login check corrected), `overflowed`, `pending` |
| `grandExchange.offers."0"…"7"` | each slot: `state`, `itemId`, `itemName`, `listedPrice`, `totalQuantity`, `completedQuantity`, `remainingQuantity`, `spent`, `placedAt`, `placedOffline`, `lastFillAt`, `observedAt` (times in epoch ms), `collectedQuantity`, `collectedCoins`; `itemSink`: `itemId`, `itemName`, `price`, `tax`, `sold` for an item-sink sale in the slot, or null |
| `skills` | per skill: `level`, `boostedLevel`, `xp`; plus `combatLevel`, `totalLevel`, `totalXp` (optional) |
| `inventory`, `equipment` | `items` by slot: `id`, `name`, `quantity`, `price`, `value` (optional) |
| `bank`, `bankLastSeenTimestamp`, `bankFromCache` | the bank as of the last time it was opened (optional) |
| `potionStore`, `potionStoreLastSeenTimestamp` | the bank's potion storage, which is not part of the bank container: per potion `id`, `name`, `doses`, `withdrawDoses`, `pricePerDose`, `value` (with the bank setting) |

## account.json

Written once your login has settled and refreshed about every ten minutes while logged in, but only rewritten
when something changed. `quests`: counts by state and `entries` per quest (`name`, `state`: `NOT_STARTED`,
`IN_PROGRESS`, `FINISHED`). `achievementDiaries`: per region and tier (`easy` … `elite`) whether it is
complete, from the game's own completion flags, plus totals. Same field names as the Local Data Exporter.

`state.json` is also the plugin's memory between sessions. If you delete it, the next login starts again
from `baseline` lines.

## Settings

| setting | default | |
|---|---|---|
| Write bank | on | include the bank and its potion storage in state.json |
| Write skills | on | include skills and levels |
| Write inventory and equipment | on | |
| Write quests and diaries | on | account.json |
| Save interval | 30 s | 10–300 s |
| Event log size (MB) | 5 | 1–50; the size at which events.jsonl is archived |

The Grand Exchange slots and the event log are always written; they are what the plugin is for.

## Known limits

- **Trade history is raw.** The `history` rows are recorded exactly as the screen lists them; reading trades
  out of them is up to your tool, so a layout change in the game cannot make the plugin write wrong trades.
- **Collections elsewhere.** Collection boxes are only seen while a collection interface is open in this
  client; collecting on mobile is not seen.
- **Login timing.** Fills made while you were logged out are only known to have happened between
  `since` and the login.

## Building

Needs Java 11.

```
./gradlew test
./gradlew run      # RuneLite in developer mode with the plugin loaded
```

## License

BSD 2-Clause; see LICENSE.
