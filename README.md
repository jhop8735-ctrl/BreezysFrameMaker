# Breezy's Frame Maker

A BotWithUs script for crafting Construction frames at Fort Forinthry.
Supports all wood tiers with full auto-detection and two operating modes.

---

## Features

- **Two modes** -- Frames Only (refined planks preset) or Full Pipeline (logs preset)
- **Auto-detects** wood type and inventory stage on startup
- **All 10 wood tiers** -- Wooden, Oak, Willow, Teak, Maple, Acadia, Mahogany, Yew, Magic, Elder
- **XP/hr 
- **Random AFK breaks** toggle for a more human-like pattern
- **Auto-logout** when supplies run out

---

## Requirements

- JDK 20 or higher
- BotWithUs client
- A bank preset configured at the Fort Forinthry Bank Chest

---

## Setup

### Frames Only mode (default)
Fill your bank preset with **refined planks** of any wood type.
The script will load the preset and craft frames at the Woodworking Bench.

### Full Pipeline mode
Fill your bank preset with **raw logs** of any wood type.
The script will run: Bank Chest -> Sawmill (logs to planks) -> Sawmill (planks to refined) -> Workbench (refined to frames).

---

## Building

Clone the repo and run:

```
gradlew jar
```

This will compile the script and copy the `.jar` automatically to:
```
%USERPROFILE%\BotWithUs\scripts\local\
```

Requires JDK 20+. No need to install Gradle -- the wrapper handles it.


---

## Object / Interface IDs

| Object        | ID     | Coords       | Action         |
|---------------|--------|--------------|----------------|
| Bank Chest    | 125239 | (3283, 3555) | OBJECT4 = Load preset |
| Sawmill       | 125240 | (3281, 3550) | OBJECT1 = Open menu   |
| Workbench     | 125054 | (3282, 3550) | OBJECT1 = Open menu   |
| Sawmill IF    | 1370   |              |                |
| Workbench IF  | 1371   |              |                |
| Progress IF   | 1251   |              | Closes when crafting done |
| Construct btn | DIALOGUE 0 -1 89784350 | Same for all stations |

---

## Author

Breezy8735
