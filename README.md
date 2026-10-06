This is the source code for arrow anticheat's loader.

The loader downloads and starts the Bukkit Arrow artifact only. It verifies the
artifact contains Bukkit's `plugin.yml` and `me.arrow.backend.bukkit.ArrowBukkitPlugin`,
then uses that entrypoint's loader bridge with ArrowLoader as the Bukkit host. Fabric
artifacts are deliberately rejected.

check out the anticheat on: https://arrow-anticheat.xyz


Anticheat source:

https://github.com/StelGR/ArrowAntiCheat/tree/master

Same AGPL 3.0 license applies.
