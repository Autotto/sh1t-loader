# Forbric

**One Minecraft instance that runs Fabric mods, Forge mods and NeoForge mods at the same time.**

Version 0.3.0 · Minecraft 26.2

## What it does

Minecraft mods come in three kinds, and normally you have to pick one. A mod is built for **Fabric**, or
for **Forge**, or for **NeoForge**, and it only works on the one it was built for. Put a Fabric mod into
a Forge game and nothing happens. So most people keep several separate setups, and whichever one they
start, most of their mods are sitting in the other ones.

Forbric is a fourth thing you install instead of those three. You put **every** mod into **one** folder —
Fabric, Forge and NeoForge mixed together, no sorting — and Forbric opens each file, works out what kind
it is, and loads it. All of them are running in the same world at the same time.

It also gives you one list of everything you have installed. The pause menu and the title screen get a
Forbric mods button, and from that list you can open any mod's own settings screen, whichever of the
three it belongs to.

**You may have heard of Kilt or Sinytra Connector.** Those are mods you add to a normal loader, and they
re-create one side's features inside the other — a translator in the room. Forbric is the loader itself:
the real Forge and the real NeoForge are running inside the game, next to Fabric, so nothing is being
translated. Connector is mature and Forbric is not, so if Connector already runs the mods you want, use
Connector. Forbric is for the cases it cannot reach.

## How to install

### Before you start

- A launcher that can start custom versions — **PCL2** or **HMCL**.
- **Java.** If you can already play Minecraft, you have it. The installer finds the copy your launcher
  downloaded, even if you never installed Java yourself.
- **An internet connection**, and about 730 MB of free disk while it works (about 190 MB is kept
  afterwards).

You do **not** need to install Minecraft 26.2 first. If you do not have it, the installer downloads it.

### Install

1. Open the [latest release](https://github.com/Ray-T-r/Minecraft-Forbric-mod-loader/releases/latest).

2. Download **two** files into the **same folder**:

   | You are on | Download |
   | --- | --- |
   | Windows | `forbric-kernel-installer-0.3.0.jar` **and** `Forbric-Installer.bat` |
   | macOS | `forbric-kernel-installer-0.3.0.jar` **and** `Forbric-Installer.command` |
   | Linux | `forbric-kernel-installer-0.3.0.jar` (run it with `java -jar`) |

3. **Double-click the `.bat` or `.command` — not the jar.** On Windows, double-clicking the jar often
   just flashes a black window and does nothing, because Windows tends to remember a broken setting for
   `.jar` files. The script starts Java itself and does not depend on that setting. (Installing Java
   does not fix it; the bad setting keeps winning.)

   On macOS the first time, you may need to right-click the file and choose **Open**, then confirm. That
   is macOS being careful about downloads, not an error.

4. **A window opens.** The only field that matters is **Game directory** — your `.minecraft` folder. It
   is usually filled in correctly. If not:

   - Windows — `C:\Users\<your name>\AppData\Roaming\.minecraft`
   - macOS — `~/Library/Application Support/minecraft`
   - Linux — `~/.minecraft`

   Leave everything else alone.

5. **Press install and wait.** The first install takes several minutes. It is downloading Minecraft's,
   Forge's and NeoForge's own files and putting them together on your computer, because those files
   cannot legally be handed out ready-made. Stay connected while it runs. Installing again later reuses
   what is already on disk and is quick.

6. **Open PCL2 or HMCL.** A new version called **`26.2-forbric`** is in the list. Start it like any
   other version.

> Want to check your computer first? Run this — it looks only, and writes nothing:
>
> ```bash
> java -jar forbric-kernel-installer-0.3.0.jar --doctor
> ```

### Where to put mods

The installer prints the folder when it finishes.

- If your launcher keeps each version separate (PCL2 and HMCL both can):
  `.minecraft/versions/26.2-forbric/mods/`
- Otherwise the shared `.minecraft/mods/`.

**Fabric, Forge and NeoForge mods all go in the same folder.** You never have to tell Forbric which is
which.

One thing to watch: a popular mod is usually published as a Fabric build, a Forge build *and* a NeoForge
build. Download **one** of them, not several. Forbric will notice and pick one, but it is better that
you choose.

If you download mods through your launcher's own mod browser, it will offer you Fabric builds by
default. A launcher can only be told about one kind, so Forbric tells it Fabric. Any of the three still
work — this only changes what the browser suggests first.

### Did it work?

Open the pause menu. There is a button with **three overlapping squares**, and the tooltip says
*Mods (Forbric)*. It opens one list of every mod you installed, each row labelled with the kind it is.
Select a mod and press **Config**, or double-click the row, to open that mod's own settings.

If you have Mod Menu installed, there will be **two** mods buttons. The one that says *Mods (Forbric)* is
the one that lists all three kinds.

### If something goes wrong

| What you see | What to do |
| --- | --- |
| **A window says a mod is missing something it needs** | It names the mod and what to install. Install it, or press **Launch anyway**. |
| **A window says required mod features are unavailable** | Some part of a mod could not start. You can continue playing, or quit and remove that mod. |
| **The game crashes** | Look in `crash-reports/`. Next to the crash report there is a `crash-analysis.txt` that names the mods most likely to blame. Remove those and try again. |
| **A mod is installed but does nothing** | Open the Forbric mods list — a mod that did not finish loading is marked there. The same list is in `.forbric-kernel/load-report.txt` in your game folder. Often the mod was built for a different Minecraft version, or you have two builds of it. |
| **A dedicated server will not start** and the log says the compatibility policy stopped it | A server has no screen to ask you on, so it stops instead. Remove the mod it names, or add `-Dforbric.compatibilityPolicy=continue` to the server's start command to run anyway. |
| **Continuity loads, but glass still has borders between blocks** | In **Options → Resource Packs**, enable **Default Connected Textures** (included with Continuity). Its built-in packs are optional and are not enabled just by installing the mod. Use the Fabric build with Fabric API, or a native NeoForge build matching your Minecraft version. |
| **The install seems stuck** | Usually a proxy or VPN sitting between you and Mojang's servers. Run `--doctor`, then try with it off. |

### Updating and uninstalling

**To update**, run the new installer with the same settings. Your mods folder and worlds are left alone.
The first install after updating from 0.2.0 builds Forbric's game files again, so it takes several minutes
once more.

**To uninstall**, delete `.minecraft/versions/26.2-forbric/`. To get the disk space back as well, also
delete `.minecraft/.forbric-build/` and `.minecraft/libraries/net/forbric/`.

## What's new in 0.3.0

**More mods work.** We picked three batches of about 100 random mods from Modrinth (popular ones and
random ones, all three kinds) and started the game with each mod on its own. **80.5% loaded cleanly on
0.2.0, 89.0% on 0.3.0.**

New:

- **Mods from different loaders can pass items, fluids and energy to each other** — for example a Fabric
  pipe or hopper can feed a NeoForge or Forge machine.
- **A window before you play when part of a mod cannot work**, so you can choose to continue or quit
  instead of finding out later.
- **The Forbric mods list marks mods that did not finish loading**, and says why.
- **After a crash, a `crash-analysis.txt`** names the mods most likely responsible.
- **The warning windows speak 10 languages**, including Chinese, and suggest what to install.
- Forbric now uses the full NeoForge release instead of a beta, so NeoForge mods that need a newer NeoForge
  can load, and the title screen no longer says "beta".

Fixed:

- Crashes when smelting in a furnace, crafting or brewing with Fabric API installed, fighting the Ender
  Dragon, using a flower pot, or placing a fluid from a Fabric or Forge mod.
- Some mod sets left the game on a black screen at startup.
- Dungeons did not generate, and a seed did not give the same terrain as vanilla Minecraft.
- Item tooltips were missing enchantments, lore, attributes and durability.
- Many Forge mods loaded but did nothing — their commands, key bindings, on-screen displays, settings files,
  mobs and world changes now work.
- Xaero's Minimap and World Map (Forge builds) crashed at startup.
- Mods that crashed or failed on 0.2.0 and work now include Farmer's Delight Refabricated, Better End,
  Better Nether, Entity Culling, More Culling, Friends & Foes, Repurposed Structures, Traveler's Backpack,
  Shoulder Surfing and YetAnotherConfigLib.

Worse than 0.2.0: in the same test, **Alex's Mobs Continued**, **Drippy Loading Screen**, **FancyMenu** and
**Easy Magic** (NeoForge builds) worked on 0.2.0 and do not on 0.3.0.

Changed for server owners: a dedicated server now stops at startup if a mod is missing a part it needs,
because there is no screen to ask you on (see *If something goes wrong* above).

## What we promise

**Your existing Minecraft is not touched.** Forbric installs alongside everything else. Your Fabric,
Forge and NeoForge setups, your worlds, and your other mod folders are exactly as they were.

**Uninstalling is deleting a folder.** Nothing is scattered around your system, and nothing is left
running when you are not playing.

**Nothing is hidden.** All the source code is here and the licence is Apache-2.0. This repository
contains no Minecraft, Forge or NeoForge code — all of that is fetched from their own servers and
assembled on your machine when you install.

And what we do **not** promise:

**We cannot promise any particular mod works.** In our own test about one mod in ten still fails on its
own, and mods that each work alone can still clash when put together. Expect some trial and error, and do
not plan a big modpack around Forbric yet. Popular performance and shader mods — Sodium, Iris, Sodium Extra
(NeoForge builds) — still crash.

**This is a research project at version 0.3.0.** There is no support, no roadmap, and things will change.

Forbric is not affiliated with Mojang, FabricMC, MinecraftForge or NeoForged.

---

### For mod developers

**Your mod does not need to change.** Forbric loads it in its own ecosystem's real runtime — nothing is
re-implemented, so there is no compatibility layer to code against.

- [introduction.md](introduction.md) — how Forbric works inside, for developers: boot order, how the
  three kinds of mods are loaded together, what the installer builds, and how it is tested.
- [forbric-kernel/README.md](forbric-kernel/README.md) — a shorter summary of the kernel, which is what
  the installer installs.

To build from source you need `git` and a JDK 21 or newer:

```bash
git clone https://github.com/Ray-T-r/Minecraft-Forbric-mod-loader.git
cd Minecraft-Forbric-mod-loader
./bootstrap.sh
cd forbric-kernel && ./gradlew build
```

### Licence

Apache-2.0 — see [LICENSE](LICENSE) and [NOTICE](NOTICE). The clean-room boundary is documented in
[forbric-loader/CREDITS.md](forbric-loader/CREDITS.md) and
[forbric-loader/MAPPINGS.md](forbric-loader/MAPPINGS.md).
