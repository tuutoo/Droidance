<div align="center">

<img src="app/src/main/res/drawable-nodpi/droidance_launcher_icon.png" alt="Droidance" width="120" />

# Droidance

### Let your Droid dance.

**The Android Use Agent That Lives on Your Phone**

Vision only · Runs in the background · No root · No computer

[简体中文](README.md) | English

<br>

<a href="https://github.com/YbbLab/Droidance/releases/latest">
  <img src="https://img.shields.io/github/v/release/YbbLab/Droidance?include_prereleases&label=Download%20APK&style=for-the-badge&logo=android&logoColor=white&color=3DDC84" alt="Download APK" height="40" />
</a>

<br><br>

[![Android 11+](https://img.shields.io/badge/Android-11%2B-3DDC84?style=flat-square&logo=android&logoColor=white)](#-get-started)
[![CI](https://img.shields.io/github/actions/workflow/status/YbbLab/Droidance/ci.yml?branch=main&style=flat-square&label=CI)](https://github.com/YbbLab/Droidance/actions/workflows/ci.yml)
[![License](https://img.shields.io/badge/License-Apache_2.0-blue?style=flat-square)](LICENSE)
[![Stars](https://img.shields.io/github/stars/YbbLab/Droidance?style=flat-square&logo=github)](https://github.com/YbbLab/Droidance/stargazers)
[![Last Commit](https://img.shields.io/github/last-commit/YbbLab/Droidance?style=flat-square)](https://github.com/YbbLab/Droidance/commits)

</div>

---

## 📖 About

**Droidance is an open-source AI agent that runs on your Android phone.** Describe a task in one sentence and it works like a person: it looks at the screen, decides the next step and acts, completing work inside your apps — comparing prices, preparing orders, looking things up, scheduling, organizing information and running scheduled tasks.

Droidance **needs only a multimodal model API key**. It does not require root, a computer or an accessibility service. Tasks run in a **virtual space** on the phone, so you can keep chatting, watching videos or taking calls while it works on a separate, invisible display. It **understands the interface from screenshots** rather than the UI hierarchy or accessibility tree: if a person can read the screen, it can try to operate it.

<table>
<tr>
<td width="33%" valign="top">

### 🔑 One key to start

No root, computer, cable or extra hardware. Install the app, pair wireless debugging and enter a multimodal model API key. On a rooted device, you can also choose a Root connection. If Shizuku is already running, you can choose Shizuku and grant access.

</td>
<td width="33%" valign="top">

### 🌌 Works in the background

Tasks run in a separate virtual space instead of your main screen. Switch over at any time to watch or take control.

</td>
<td width="33%" valign="top">

### 👁️ Vision driven

No XML hierarchy dumps and no accessibility service. Custom-drawn controls and WebViews with incomplete accessibility trees are operated from the screen, as a person would.

</td>
</tr>
</table>

---

## 📲 Get started

**Requirements**

- An Android 11 or later phone. Low-memory and Android Go devices are not supported.
- Wireless debugging needs no root and requires Wi‑Fi for pairing; Root requires a rooted device and Root access granted to Droidance. The model provider still needs network access.
- Android 11–13 support is experimental: without per-display focus, key and text input briefly take focus from the main screen, and on Android 11 and 12 tasks cannot continue while the phone is locked.
- If the Agent reports that the system blocked input, enable "USB debugging (Security settings)" on Xiaomi-family phones, or disable permission monitoring on OPPO, OnePlus and realme phones, then reconnect the device.
- An API key for a multimodal model that supports image input and tool calling.

| Step | Action |
| :---: | :--- |
| **1** | Download and install the latest APK from [Releases](https://github.com/YbbLab/Droidance/releases/latest) |
| **2** | Under **Connect device**, choose wireless debugging, follow the guide to enable **Developer options → Wireless debugging**, and enter the pairing code. On a rooted device, you can instead choose Root and grant access. If Shizuku is running, select Shizuku and allow authorization. **No computer is needed.** |
| **3** | Add a provider endpoint, API key and model, then describe your task |

That's it. Tasks run in the background, and the runtime island at the top of the screen lets you check progress, pause or take control.

> [!NOTE]
> Root connection is available from v0.1.9. Droidance is a preview release (0.x); see the [CHANGELOG](CHANGELOG.md). Each release includes a `.sha256` file for the APK.

> [!CAUTION]
> This project is for learning and research. Automation may violate some apps' terms of service and put accounts at risk. Read the [disclaimer](#disclaimer) before use.

<details>
<summary><b>Build from source</b></summary>

Requires JDK 17 and Android SDK 35. Set `sdk.dir` in `local.properties` at the repository root (the file is ignored by Git), then run:

```bash
git clone https://github.com/YbbLab/Droidance.git
cd Droidance
./gradlew :app:assembleDebug
```

Choose a built-in provider, enter your API key and model ID, and save to enable it. The API address and protocol are prefilled; no model IDs are preset. You can also add a custom endpoint. Existing configurations are preserved. Builds do not embed local provider keys.

Public source checks (architecture, resources, native inputs, lint and debug APK; locally retained tests also run when present):

```bash
scripts/verify.sh
```

See [CONTRIBUTING](CONTRIBUTING.md) for development conventions and validation.

</details>

---

## ✨ What it can do

<table>
<tr>
<td width="33%" align="center" valign="top"><a href="assets/demos/compare.mp4"><img src="assets/demos/compare.webp" width="240" alt="Compare prices" /></a><br /><b>Compare prices</b><br /><sub>Compare iPhone prices on Pinduoduo, Tmall and JD.com</sub></td>
<td width="33%" align="center" valign="top"><a href="assets/demos/tea.mp4"><img src="assets/demos/tea.webp" width="240" alt="Order milk tea" /></a><br /><b>Order milk tea</b><br /><sub>Pick options and coupons, then stop for your confirmation</sub></td>
<td width="33%" align="center" valign="top"><a href="assets/demos/meeting.mp4"><img src="assets/demos/meeting.webp" width="240" alt="Schedule and share" /></a><br /><b>Schedule and share</b><br /><sub>Book a Tencent Meeting and send the details to WeChat</sub></td>
</tr>
<tr>
<td width="33%" align="center" valign="top"><a href="assets/demos/train.mp4"><img src="assets/demos/train.webp" width="240" alt="Find trains" /></a><br /><b>Find trains</b><br /><sub>Filter trains with second-class seats and list times and fares</sub></td>
<td width="33%" align="center" valign="top"><a href="assets/demos/resale.mp4"><img src="assets/demos/resale.webp" width="240" alt="Ask sellers" /></a><br /><b>Ask sellers</b><br /><sub>Pick three iPads on Xianyu and ask about battery and repairs</sub></td>
<td width="33%" align="center" valign="top"><a href="assets/demos/review.mp4"><img src="assets/demos/review.webp" width="240" alt="Review a purchase" /></a><br /><b>Review a purchase</b><br /><sub>Rate a pending JD.com item and submit the review</sub></td>
</tr>
</table>

<sub>Clips are edited, partly sped up, and have private details covered. Click a preview for the full video.</sub>

**More capabilities**

- **Human in the loop**: CAPTCHAs are always left to you. For steps that need your own decision, such as payment, passwords or sign-in authorization, the agent pauses and asks you to take over. You can also take control at any time and hand the task back afterwards.
- **Scheduled tasks**: one-time, daily, weekly and fixed-interval plans run automatically.
- **Reusable skills**: save workflows that worked as reusable skills and load them when needed.
- **System capabilities**: with your permission, it can read and write calendar, contacts, SMS and other system data directly instead of operating the interface for every step.
- **Model choice**: connect any OpenAI-compatible endpoint with image input and tool calling, using your own address, API key and models.

**Compared with common approaches**

| | Computer-side ADB control | Accessibility-service agents | **Droidance** |
| :--- | :---: | :---: | :---: |
| No computer / USB | ❌ | ✅ | ✅ |
| No root | ✅ | ✅ | ✅ |
| No UI hierarchy / accessibility service | ⚠️ Depends | ❌ | ✅ |
| Keeps the main screen free | ❌ | ❌ | ✅ |
| Pauses for sensitive steps and manual takeover | ⚠️ | ⚠️ | ✅ |

---

## 🏗️ Architecture

<picture>
  <source media="(prefers-color-scheme: dark)" srcset="assets/architecture/architecture-en-dark.png" />
  <img src="assets/architecture/architecture-en-light.png" alt="Droidance architecture" />
</picture>

### How it runs on an Android phone

1. **Built-in ADB: the phone connects to itself**: The app ships a complete wireless debugging client. It finds the local port with mDNS, pairs over SPAKE2 with `libadb.so` (BoringSSL), then connects to the system `adbd` with a Kotlin TLS ADB client and a local RSA key to start a resident shell-identity process, the Sidecar. No root or computer is needed. On a rooted device, you can instead grant Root access to start the same Sidecar without wireless debugging.
2. **Virtual space**: the sidecar creates a separate virtual display. Apps open on it with the apps and signed-in accounts already on your phone, not a copy of their data.
3. **Vision-only perception**: the agent reads only screenshots of the virtual display, zooming into regions when needed. It does not read the UI hierarchy, use an accessibility service or rely on a local OCR model.
4. **Single-agent decisions**: one agent decides from the screen and context whether to answer directly, use a system capability (calendar, SMS, contacts and so on), run an isolated script or operate the interface. There is no separate intent classifier or planner.
5. **Execution and reporting**: taps, text input and swipes go to the same virtual display. After each step the agent observes the screen again before deciding what to do next. The run is recorded locally, and results are reported in the app and through local notifications.

### Modules

| Module | Responsibility |
| --- | --- |
| `app` | Android host, UI, settings and runtime services |
| `core:agent` | Agent loop, task lifecycle, tool protocol and context management |
| `core:model` | Model abstraction, provider adapters and streaming protocols |
| `core:device` | Sidecar, screenshots, input, system capabilities and isolated script execution |
| `core:knowledge` | Skills, memory and document storage |
| `core:persistence` | Local persistence for events and knowledge |

Agent rules and policies live in Markdown configuration (`app/src/main/assets/rules/`, `skills/`); Kotlin handles generic orchestration, protocol validation, device execution and persistence.

---

## 🔒 Privacy and security

**Where data goes**

- **Local first**: conversations, skills and execution records stay on your device. There is no third-party analytics or telemetry.
- **Model requests**: instructions, context and screenshots are sent to **the model provider you configure**, directly to its endpoint and never through a Droidance server. Review that provider's privacy policy.
- **Your credentials**: API keys are stored in app-private storage and passed to the shell-identity runtime in memory. Runtime data and history also live in a shell-owned directory on the device, which is not automatically removed on uninstall; directories left by uninstalled older versions may still contain credential copies. See [SECURITY](SECURITY.md).
- **Local diagnostics**: the app automatically retains model requests, responses and screenshots for troubleshooting; these may still contain personal information. Cleanup targets seven days and 512 MiB, but runs on later requests as a best effort and excludes the current task, so neither is a strict retention or size limit. Use Settings → Data management → Clean up runtime logs to remove diagnostics; conversations, replay attachments and memory are managed separately. Diagnostic exports may contain sensitive content; review them before sharing.
- **App updates**: update checks contact the GitHub API. If a direct download fails, the app falls back to the `ghproxy.net` mirror; the package checksum always comes from the GitHub API.

**Permissions**

| Permission / capability | Purpose |
| --- | --- |
| Wireless debugging pairing | Start the shell-identity device control process without Root |
| Root access (optional) | Start the same shell-identity device control process without wireless debugging |
| Shizuku authorization (optional) | Start the same device control process through a running Shizuku service |
| Calendar, contacts, SMS, call log | System capabilities the agent can use for a task after you grant them |
| Notification access | Read notifications for tasks and automation |
| Droidance keyboard | Type text on the virtual display |
| Query installed apps | Find and launch target apps by name |
| Exact alarms, start at boot, battery optimization exemption | Run and restore scheduled tasks on time |
| Install unknown apps | Install updates downloaded in the app |

Runtime permissions can be revoked in system settings at any time. After wireless pairing, the sidecar does not depend on Wi‑Fi and restarts itself if killed. Reset pairing or restart the phone to stop it; it no longer restarts once developer options are off. After an app update, the sidecar must be replaced; if it cannot reconnect automatically, turn on wireless debugging or use Root on a rooted device. Root mode uses Root access to start and recover the channel; new starts or recovery fail after access is revoked. Report security issues privately according to [SECURITY](SECURITY.md).

**Limits**

- **Human confirmation**: the agent decides when to pause according to its rules; this is not a hard block. For financial, private or irreversible actions, have it stop at the confirmation page and review and submit it yourself.
- **Non-invasive**: no hooking, injection or modification of third-party apps.

---

## 📱 Compatibility and known limitations

- Wireless pairing and reconnecting after a phone restart require Wi‑Fi. Some systems turn wireless debugging off after network changes, restarts or long idle periods, and it must be enabled again when needed.
- Install Shizuku separately and start it through ADB or wireless debugging; start it again after a phone reboot. For root-started Shizuku, choose Droidance’s Root connection. A stopped Shizuku service does not interrupt tasks while the existing device channel remains available.
- Root connection has been tested on a physical device with Android 16 and SukiSU; behavior may differ with other Root managers and devices.
- Some vendor systems restrict background work. Allow Droidance to run in the background and turn off battery optimization for it.
- Some apps detect developer options or wireless debugging and may refuse to run or limit features.
- Success rate and speed depend on the model's vision and reasoning. An agent reporting "done" does not guarantee the business result is correct, so check important results yourself.

---

## 🗺️ Roadmap

- [ ] Pro and Ultra modes: more advanced operations and Geek Mode
- [ ] Sub-agents: run sub-agent tasks in parallel across virtual spaces

---

## 🤝 Contributing

Report problems and suggestions in [Issues](https://github.com/YbbLab/Droidance/issues), or open a pull request. Before you start, read:

- [Contributing guide](CONTRIBUTING.md): development setup, architecture conventions and validation
- [Code of Conduct](CODE_OF_CONDUCT.md)
- [Security policy](SECURITY.md): report vulnerabilities privately through GitHub, not in a public issue

Follow us: [X](https://x.com/ybblab2026) · [YouTube](https://www.youtube.com/@ybblab) · [Bilibili](https://space.bilibili.com/3706972849310516) · [Xiaohongshu](https://www.xiaohongshu.com/user/profile/69cb432b0000000032025c5e)

<a href="https://github.com/YbbLab/Droidance/graphs/contributors">
  <img src="https://contrib.rocks/image?repo=YbbLab/Droidance" alt="Contributors" />
</a>

---

## 📄 License

Droidance is licensed under the [Apache License 2.0](LICENSE). You may use, modify and distribute it, including commercially, provided that you:

- **Keep notices**: retain the copyright notices, [LICENSE](LICENSE) and [NOTICE](NOTICE) when distributing source or binaries.
- **Mark changes**: state your changes in any source files you modify.
- **Don't imply endorsement**: the license grants no trademark rights to the Droidance name or logo. Do not publish or promote derivative works as official.
- **Respect third-party licenses**: bundled third-party components keep their own licenses. See [NOTICE](NOTICE) and [bundled license texts](app/src/main/assets/licenses/).

### Disclaimer

> [!IMPORTANT]
> These usage cautions do not restrict the rights granted by the [Apache License 2.0](LICENSE).

1. **Follow applicable law**: this is an open-source mobile-agent project. Follow applicable laws and third-party service terms when using it.
2. **Account risk is yours**: automation may violate third-party apps' terms and **may lead to warnings, restrictions, bans or data loss**. You bear this risk. Avoid using it on important accounts.
3. **Avoid abuse**: please do not use this project for fake orders or engagement, bulk registration, malicious purchasing, bypassing risk controls or CAPTCHAs, unauthorized collection of other people's data, harassment, fraud or any other illegal or harmful activity.
4. **AI makes mistakes**: model decisions are uncertain and may result in wrong taps or input. Confirm any financial, private or irreversible action yourself. The authors and contributors are not liable for any direct or indirect loss arising from use of this project.
5. **Third-party services**: screenshots and other data are sent to the model provider you configure. Understand and follow its privacy policy and terms.
6. **No affiliation**: this project is not affiliated with any third-party app or provider mentioned here. Trademarks belong to their respective owners.

---

## 📈 Activity

<a href="https://star-history.com/#YbbLab/Droidance&Date">
  <picture>
    <source media="(prefers-color-scheme: dark)" srcset="https://api.star-history.com/svg?repos=YbbLab/Droidance&type=Date&theme=dark" />
    <img alt="Star History Chart" src="https://api.star-history.com/svg?repos=YbbLab/Droidance&type=Date" />
  </picture>
</a>

<div align="center">

**If Droidance gets your phone dancing, please give it a ⭐.**

</div>
