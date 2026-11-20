<p align="center">
  <img src="https://github.com/AndroidOctane/androidoctane.github.io/blob/main/assets/banner.png" alt="OctaneUI Banner" width="100%">
</p>

  [Русский](https://github.com/dtech-special/OctaneUi/blob/main/README_RU.md) | [English](https://github.com/dtech-special/OctaneUi/blob/main/README.md)  

<p align="center">
  An experimental open-source project for running alternative Android firmware environments (GSI) on top of stock OS — <b>without unlocking the bootloader</b>.
</p>

<p align="center">
  <img src="https://img.shields.io/badge/Project-Experimental-303030?style=flat-square" alt="Project status">
  <img src="https://img.shields.io/badge/Platform-ARM64%20(AArch64)-blue?style=flat-square" alt="Platform">
  <img src="https://img.shields.io/badge/License-Apache%202.0-green?style=flat-square" alt="License">
  <img src="https://img.shields.io/badge/Status-In%20Development-orange?style=flat-square" alt="Development status">
</p>


---

## About

Why reflash your device and break warranty every time you want to try another firmware?

**OctaneUI** explores how alternative Android environments can coexist alongside your primary stock installation via userspace `pivot_root` containers. Supporting both permanent root and **temp-root** setups, it investigates clean system hand-off concepts so you can experience a guest ROM and return safely to your main OS.

> **Disclaimer:** OctaneUI is an experimental research project. It is not a standalone operating system, a Linux distribution, a conventional custom ROM, or an Android launcher. Stability and device compatibility are not guaranteed.

## Architecture & Modules

The project is structured into modular components to separate container management, low-level system bridging, and storage handling:

- **`container-engine`** — Core userspace `pivot_root` container lifecycle and environment hand-off logic.
- **`root-bridge`** — Privilege execution layer supporting both standard root and temp-root setups.
- **`image-manager`** — Handling, mounting, and preparation of `arm64-ab` GSI system images.
- **`storage-manager`** — Partition space validation and isolated guest storage layout.
- **`system-detection`** — Hardware, kernel, and Samsung environment compatibility checks.
- **`app`** — Unified Material UI interface for managing and launching environments.

## Project Information

| Attribute | Details |
| :--- | :--- |
| **Name** | OctaneUI |
| **Type** | Userspace Android container & firmware environment manager |
| **Architecture** | ARM64 (`aarch64` / `arm64-ab` GSI) |
| **Root Requirement** | Root or Temp-Root (No bootloader unlock required) |
| **License** | [Apache License 2.0](LICENSE) |
| **Website** | [androidoctane.github.io](https://androidoctane.github.io) |
| **Status** | Experimental / Active Development |

## Credits & Acknowledgements

Special thanks to **WeWaffles** and the **[OneLineage](https://github.com)** project for the architectural inspiration, ideas, and foundational scripts (`assets/*.sh` and `assets/zygote_exec`) that helped shape OctaneUI.

See [`NOTICE`](NOTICE) and [`THIRD_PARTY_NOTICES.md`](THIRD_PARTY_NOTICES.md) for full attribution and license details.

## Follow Development

<p align="center">
  <a href="https://www.tiktok.com/@techdelta">
    <img src="https://img.shields.io/badge/TikTok-@techdelta-black?style=for-the-badge&logo=tiktok" alt="TikTok">
  </a>
</p>

---

<p align="center">
  <b>OctaneUI</b>
  <br>
  <sub>Copyright © 2026 dtech-special • Built for experimentation.</sub>
</p>
