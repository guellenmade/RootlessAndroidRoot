# RootlessVM (RootlessAndroidRoot)

**A FOSS virtualized Android environment with root and a working Xposed
framework inside the VM — on an unrooted host.**

- GPL-3.0-or-later, no trackers, no proprietary images, built from pinned
  FOSS sources only (proot, Vector @ `ddeed8c`).
- proot baseline for nearly all devices (Android 8+); AVF/pKVM fast path is
  detected and documented as roadmap.

Documentation:

- [DOCUMENTATION.md](DOCUMENTATION.md) — full docs: features, architecture,
  compatibility, install/build guides, FAQ.
- [AGENT.md](AGENT.md) — repo guide for AI agents and contributors
  (architecture decisions, conventions, pinned sources).

Quick start (build): see [DOCUMENTATION.md §8](DOCUMENTATION.md#8-build-guide).
