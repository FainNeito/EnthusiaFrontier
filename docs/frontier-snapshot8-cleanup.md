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
