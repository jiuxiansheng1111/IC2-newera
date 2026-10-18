# IC2: New Era

IC2: New Era is an industrial addon connecting IC2 Classic energy and machines with Create processing and automation. It adds modern materials, electronics, data networks, battery-powered companion robots and a phone for remote control.

## Features

- Nickel, chromium, titanium and tungsten, with shared approved crushing, washing and refining routes. Existing IC2/Create machines perform the applicable processes.
- Silicon, PCB, integrated circuits, CPU, RAM, GPU and industrial storage components, with two specialized material processors.
- Data switches, optical fiber, machine adapters, control servers and a 144-slot network terminal. Data networks do not replace EU cables, pipes or Create shafts.
- Eight companion robot variants, replaceable batteries, four power-bank tiers, equipment, following, waiting, logging, mining and optional attacks against hostile mobs.
- A phone for owned robot status, commands and character conversations. Loaded robots can be contacted without a distance limit; unloaded chunks are not force-loaded.
- Specific-item collection with a requested amount. The new chat path interprets a collection request, then rechecks the item, quantity and owner on the server before assigning work.
- Optional player-configured LLM assistance, public music search/playlists and character voice playback. Voice synthesis uses a separately configured host runtime and compatible model assets; remote clients receive audio through their Minecraft connection.

## Requirements

Minecraft **1.19.2**, Forge **43.5.0**, Java **17**.

Required: **IC2 Classic 2.1.3.4** and **Create 0.5.1.i**, with their required dependencies. **ProjectE 1.1.0** is optional for the dark-matter terminal recipe. JEI is recommended for recipes. The companion test pack pins all seven dependency downloads.

Configure your own LLM/provider in game for singleplayer, or let the server owner configure the dedicated-server model. Model credentials are not included in downloads. AI and voice are optional; basic industrial and robot systems do not require an LLM connection. Voice model files and third-party runtime environments are not bundled.

The current in-game interface and natural-language collection phrases are primarily Chinese. This project page is provided in English.

## Alpha status

This is a development **Alpha**. Previous checks covered singleplayer voice playback, remote-phone control and GUI collection. The newest natural-language collection path is implemented and built, and is pending in-game player testing. Two simultaneous authenticated players, real public tunneling and other computers have not yet been verified.

Test on a separate world and report reproducible issues with screenshots and relevant log excerpts. Remove credentials before sharing logs.

Source and issues: https://github.com/jiuxiansheng1111/IC2-newera

