# Frontier snapshot 8 chunk cleanup

The disposable test used Paper 26.4 snapshot 8, LumaGuilds SQLite ownership, a 30-day retention period, and the lab.6 plugin. Three existing ledger records in the copied world were aged to 31 days. The fresh wilderness control was in the same region file as the deletion candidate. Individual chunk deletion was enabled and whole-region reclamation was disabled.

Before the adapter change the plugin failed closed because the fork lacked MoonriseRegionFileIO. The new adapter uses the running world's terrain, entity and POI workers. The following observations were made from the Anvil files and SQLite ledger after a clean shutdown and again after restarting with cleanup active.

| Chunk X, Z | Fixture | After cleanup | After restart |
| --- | --- | --- | --- |
| -153, 100 | Aged wilderness | Terrain entry removed and ledger deletion recorded | Still absent |
| -126, 117 | Aged LabGuild claim | Skipped for land ownership | Terrain and ownership present |
| -138, 115 | Aged block-placement protection | Terrain and protection flag present | Same terrain payload and flag |
| -153, 101 | Fresh wilderness | Terrain payload unchanged | Same terrain payload |

The overworld location-table diff contained one removed terrain entry and zero other additions or removals. Entity and POI occupancy did not change. The deleted fixture originally had no entity or POI record, so removal of populated auxiliary records was outside this run. The ledger gained exactly one deletion. The guild claim retained a reservation and was rechecked against live ownership after restart.

The run used the disposable 26.4 lab distribution. The repository build targets the existing 26.2 API and selects the adapter for the fork's exact Bukkit API version. On that fork use `cleanup.physical-reclaim: false` for individual chunk deletion and `cleanup.require-lumaguilds: true` for Frontier claims. Bloom stayed in dry-run and SMP was outside the test.

## Villager and workstation cleanup

On November 21, 2026, the disposable server ran the 26.4 build from merge `47b8534`. Each of two adjacent chunks contained a tagged villager and a lectern. Before cleanup, inspection of the saved terrain, entity, and point-of-interest (POI) records confirmed the lectern block entity, the villager UUID, and a `minecraft:librarian` POI at the lectern coordinates. Both chunks were aged to 31 days in the copied ledger.

| Chunk X, Z | After cleanup | After restart with cleanup active |
| --- | --- | --- |
| Wilderness `(-150, 100)` | All three records absent. The ledger recorded deletion. | All three remained absent. |
| LabGuild `(-149, 100)` | Audit reported `land_claim`. All three records survived. | Same villager UUID, lectern, and librarian POI at block `(-2376, 220, 1608)`. LabGuild still owned the chunk. |

The first wilderness location was deferred with `environment_not_safe`. The test used the adjacent pair above, with a one-chunk guild claim added to the disposable database. The safety check stayed enabled.

## Interrupted cleanup and ownership on retry

A separate 31-day-old wilderness chunk at `(-152, 101)` also contained a villager and lectern. A test-only build halted the server process immediately after flushing the entity deletion to disk. Inspection confirmed the villager record was gone, the terrain and POI payloads still matched their pre-test SHA-256 hashes, and the SQLite ledger held a pending deletion intent with no completion timestamp.

Two copies of that interrupted state restarted with the unmodified deployment JAR:

| Ownership at restart | Retry and saved result |
| --- | --- |
| Wilderness | Cleanup resumed the existing intent, removed terrain and POI, recorded completion, and cleared the intent. All three records were absent after shutdown. |
| LabGuild | A claim was inserted into the stopped copy's LumaGuilds database before startup. Two consecutive scans reported `land_claim`. The terrain and POI hashes matched the interrupted state, and the ledger retained the original intent without marking the chunk deleted. |

Claiming between attempts protected the remaining records. It did not restore the villager already removed before the interruption. This test covered the interruption immediately after the entity flush. Both recovery runs used disposable copies, Bloom remained in dry-run, and SMP was untouched.
