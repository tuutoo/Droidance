<div align="center">

<img src="app/src/main/res/drawable-nodpi/droidance_launcher_icon.png" alt="Droidance" width="120" />

# Droidance

### Let your Droid dance.

**装在手机上的安卓操作智能体，让手机自己干活。**

纯视觉 · 后台运行 · 无需 Root · 无需电脑

简体中文 | [English](README.en.md)

<br>

<a href="https://github.com/YbbLab/Droidance/releases/latest">
  <img src="https://img.shields.io/github/v/release/YbbLab/Droidance?include_prereleases&label=%E4%B8%8B%E8%BD%BD%20APK&style=for-the-badge&logo=android&logoColor=white&color=3DDC84" alt="下载 APK" height="40" />
</a>

<br><br>

[![Android 11+](https://img.shields.io/badge/Android-11%2B-3DDC84?style=flat-square&logo=android&logoColor=white)](#-下载与上手)
[![CI](https://img.shields.io/github/actions/workflow/status/YbbLab/Droidance/ci.yml?branch=main&style=flat-square&label=CI)](https://github.com/YbbLab/Droidance/actions/workflows/ci.yml)
[![License](https://img.shields.io/badge/License-Apache_2.0-blue?style=flat-square)](LICENSE)
[![Stars](https://img.shields.io/github/stars/YbbLab/Droidance?style=flat-square&logo=github)](https://github.com/YbbLab/Droidance/stargazers)
[![Last Commit](https://img.shields.io/github/last-commit/YbbLab/Droidance?style=flat-square)](https://github.com/YbbLab/Droidance/commits)

</div>

---

## 📖 项目介绍

**Droidance 是一个运行在安卓手机上的开源 AI 智能体。** 你只需要说一句话，它就会像人一样「看屏幕、想下一步、动手点」，在 App 里替你把事情做完：比价、点单、查询、预约、整理信息、定时执行……

Droidance **只靠一个多模态大模型 API Key 就能跑起来**：不用 Root，不用连电脑，也不用开无障碍服务。任务在手机的**虚拟空间**里后台执行，你照常聊天、刷视频、接电话，它在另一块"看不见的屏幕"上安静干活。它**依靠截图理解界面**，不读取 UI 层级、不依赖无障碍树，人能看懂的界面，它就能尝试操作。

<table>
<tr>
<td width="33%" valign="top">

### 🔑 一个 Key 就能用

不需要 Root、电脑、数据线或额外硬件。装好 App，完成无线调试配对，填一个多模态大模型的 API Key，就能开始下达任务。已 Root 的设备也可以选择 Root 连接。已启动 Shizuku 的用户也可以选择 Shizuku 并授权。

</td>
<td width="33%" valign="top">

### 🌌 后台执行，不打扰你

任务运行在独立的虚拟空间里，不占用主屏幕。你用你的手机，它干它的活；需要时也能随时切过去围观或接手。

</td>
<td width="33%" valign="top">

### 👁️ 纯视觉驱动

不 Dump XML 层级，不使用无障碍服务。自绘控件、WebView 等无障碍树不完整的界面，也能像人一样看着屏幕操作。

</td>
</tr>
</table>

---

## 📲 下载与上手

**准备：**

- 一部 Android 11 及以上的手机。低内存设备和 Android Go 不支持。
- 无线调试连接无需 Root，配对时需要 Wi‑Fi；Root 连接需要设备已 Root 并授予 Droidance Root 权限。模型服务仍需要可用的网络连接。
- Android 11–13 为实验性支持：系统没有虚拟屏独立焦点，按键和文字输入时会短暂占用主屏焦点；Android 11、12 锁屏后任务无法继续。
- 如果 Agent 提示系统拦截输入，小米系请在开发者选项开启“USB 调试（安全设置）”，OPPO、一加、realme 请关闭“权限监控”，然后重新连接设备。
- 一个支持图像输入和工具调用的多模态大模型 API Key。

| 步骤 | 操作 |
| :---: | :--- |
| **1** | 前往 [Releases](https://github.com/YbbLab/Droidance/releases/latest) 下载最新 APK 并安装 |
| **2** | 在「连接设备」选择无线调试，按引导打开「开发者选项 → 无线调试」并输入配对码；已 Root 的设备也可以选择 Root 并授权；已启动 Shizuku 的用户可选择 Shizuku 并允许授权。**全程不需要电脑** |
| **3** | 添加模型服务商的服务地址、API Key 和模型，用一句话说出你的任务 |

完成。任务会在后台执行，你可以通过顶部的运行岛随时查看进度、暂停或接手操作。

> [!NOTE]
> 从 v0.1.9 起支持 Root 连接。项目仍处于预览阶段，变更记录见 [CHANGELOG](CHANGELOG.md)。每个 Release 都附带 APK 的 `.sha256` 校验文件，安装前可以核对。

> [!CAUTION]
> 本项目仅供学习与研究使用。自动化操作可能违反部分 App 的服务条款，存在账号受限的风险。使用前请阅读[免责声明](#免责声明)。

<details>
<summary><b>从源码构建</b></summary>

需要 JDK 17 与 Android SDK 35。在仓库根目录的 `local.properties` 中配置 `sdk.dir`（该文件已被 Git 忽略），然后：

```bash
git clone https://github.com/YbbLab/Droidance.git
cd Droidance
./gradlew :app:assembleDebug
```

选择内置服务商，填写 API 密钥和模型 ID 后保存即可启用。API 地址和协议已预填，不预置模型 ID；也可以添加自定义地址。已有配置会保留。构建过程不会将本机 Key 注入 APK。

公开源码检查（架构、资源、原生依赖、Lint 与 Debug APK；本地保留测试时也会运行测试）：

```bash
scripts/verify.sh
```

开发约定与验证方式见 [CONTRIBUTING](CONTRIBUTING.md)。

</details>

---

## ✨ 能做什么

<table>
<tr>
<td width="33%" align="center" valign="top"><a href="assets/demos/compare.mp4"><img src="assets/demos/compare.webp" width="240" alt="跨平台比价" /></a><br /><b>跨平台比价</b><br /><sub>比较拼多多、天猫旗舰店和京东自营的 iPhone 到手价</sub></td>
<td width="33%" align="center" valign="top"><a href="assets/demos/tea.mp4"><img src="assets/demos/tea.webp" width="240" alt="点奶茶" /></a><br /><b>点奶茶</b><br /><sub>选好规格和红包，停在提交订单页等你确认</sub></td>
<td width="33%" align="center" valign="top"><a href="assets/demos/meeting.mp4"><img src="assets/demos/meeting.webp" width="240" alt="预约并分享会议" /></a><br /><b>预约并分享会议</b><br /><sub>预约腾讯会议，把会议信息发到微信文件传输助手</sub></td>
</tr>
<tr>
<td width="33%" align="center" valign="top"><a href="assets/demos/train.mp4"><img src="assets/demos/train.webp" width="240" alt="查高铁" /></a><br /><b>查高铁</b><br /><sub>筛选后天上午有二等座的车次，整理时间和票价</sub></td>
<td width="33%" align="center" valign="top"><a href="assets/demos/resale.mp4"><img src="assets/demos/resale.webp" width="240" alt="二手询价" /></a><br /><b>二手询价</b><br /><sub>在闲鱼挑 3 台 iPad，礼貌询问电池和维修情况</sub></td>
<td width="33%" align="center" valign="top"><a href="assets/demos/review.mp4"><img src="assets/demos/review.webp" width="240" alt="商品评价" /></a><br /><b>商品评价</b><br /><sub>在京东完成待评价商品的评分和提交</sub></td>
</tr>
</table>

<sub>示例经过剪辑，部分操作经过倍速处理，隐私信息已遮挡。点击动图查看完整视频。</sub>

**更多能力**

- **人在回路**：人机验证（CAPTCHA）始终由你完成；遇到支付、密码、登录授权等需要你本人决定的步骤，智能体会暂停并请你接手。你也可以随时接管，操作完再交还给它。
- **定时任务**：支持一次性、每日/每周与固定间隔的计划，到点自动执行。
- **技能沉淀**：把摸索出的有效流程沉淀为可复用的技能（Skill），之后按需加载，越用越顺手。
- **系统能力**：在你授权后，可以直接读写日历、联系人、短信等系统数据，不必每一步都操作界面。
- **模型自由**：可接入支持图像输入与工具调用的 OpenAI 兼容端点，服务地址、API Key 和模型都由你自己配置。

**和常见方案对比**

| | 电脑端 ADB 控制方案 | 基于无障碍服务的 Agent | **Droidance** |
| :--- | :---: | :---: | :---: |
| 不需要电脑 / USB | ❌ | ✅ | ✅ |
| 不需要 Root | ✅ | ✅ | ✅ |
| 不依赖 UI 层级 / 无障碍服务 | ⚠️ 取决于实现 | ❌ | ✅ |
| 执行时不占用主屏 | ❌ | ❌ | ✅ |
| 敏感步骤暂停、人工接管 | ⚠️ | ⚠️ | ✅ |

---

## 🏗️ 技术架构与运行机制

<picture>
  <source media="(prefers-color-scheme: dark)" srcset="assets/architecture/architecture-zh-dark.png" />
  <img src="assets/architecture/architecture-zh-light.png" alt="Droidance 技术架构" />
</picture>

### 它是怎么在安卓手机上跑起来的

1. **内置 ADB，手机自己连自己**：App 内置完整的无线调试客户端：用 mDNS 发现本机端口，用 `libadb.so`（BoringSSL）完成 SPAKE2 配对，再由 Kotlin 实现的 TLS ADB 客户端以本地 RSA 密钥连接系统 `adbd`，启动常驻的 Shell 身份执行进程（Sidecar）。不需要 Root，也不需要电脑。已 Root 的设备也可以授权 App 通过 Root 启动同一个 Sidecar，无需无线调试。
2. **创建虚拟空间**：Sidecar 在手机上创建一块独立的虚拟显示屏。App 在这块屏幕上打开，使用的是你手机上已安装的应用和已登录的账号，不是另一份数据拷贝。
3. **纯视觉感知**：智能体只读取虚拟屏的截图，必要时局部放大查看细节。不读取 UI 层级、不使用无障碍服务，也不依赖本地 OCR 模型。
4. **单一智能体决策**：同一个智能体根据画面和上下文，自主选择直接回答、调用系统能力（日历、短信、通讯录等）、执行隔离脚本，还是进行界面操作。没有额外的意图分类器或规划器，一个循环走到底。
5. **执行与回报**：点击、输入、滑动都发往同一块虚拟屏。每一步执行后重新观察画面，确认效果再决定下一步；执行过程完整记录在本地，任务结束后把结果回报给你（App 内与本地通知）。

### 模块组成

| 模块 | 职责 |
| --- | --- |
| `app` | 安卓宿主、界面、设置与运行时服务 |
| `core:agent` | 智能体决策循环、任务生命周期、工具协议与上下文管理 |
| `core:model` | 模型抽象层、各服务商适配与流式协议 |
| `core:device` | Sidecar、截屏、输入、系统能力与隔离脚本执行 |
| `core:knowledge` | 技能、记忆与文档存储 |
| `core:persistence` | 事件与知识的本地持久化 |

智能体的规则与策略放在 Markdown 配置中（`app/src/main/assets/rules/`、`skills/`），Kotlin 只负责通用编排、协议校验、设备执行与持久化。

---

## 🔒 隐私与安全

**数据去向**

- **本地优先**：会话、技能与执行记录只保存在你的设备上，没有任何第三方统计或遥测。
- **模型请求**：任务指令、上下文与屏幕截图会发送给**你自己配置的模型服务商**，请求直连该端点，不经过 Droidance 的服务器。请了解对应服务商的隐私政策。
- **凭证自持**：API Key 保存在应用私有存储中，并在运行时传入 Shell 身份进程的内存。运行数据和历史记录存放在设备上由 Shell 持有的目录中，卸载应用不会自动删除该目录；旧版卸载后遗留的目录可能仍含凭证副本。详情见 [SECURITY](SECURITY.md)。
- **本地诊断**：应用为排障自动保存模型请求、响应和截图；其中仍可能包含个人信息。诊断记录以 7 天及 512 MiB 为清理目标，但清理只在后续请求时尽力执行，当前任务不参与清理，因此这不是严格的保存期限或容量上限。可在「设置 → 数据管理 → 清理运行日志」删除诊断记录；对话、回放附件和记忆需分别管理。导出诊断可能包含这些内容，分享前请检查。
- **应用更新**：检查更新时访问 GitHub API；直连下载失败时会回退到 `ghproxy.net` 镜像，安装包校验值始终来自 GitHub API。

**权限用途**

| 权限 / 能力 | 用途 |
| --- | --- |
| 无线调试配对 | 无需 Root，启动 Shell 身份的设备控制进程 |
| Root 授权（可选） | 无需无线调试，以 Shell 身份启动同一设备控制进程 |
| Shizuku 授权（可选） | 通过已运行的 Shizuku 启动同一设备控制进程 |
| 日历、联系人、短信、通话记录 | 你授权后，作为系统能力供智能体按任务读写 |
| 通知使用权 | 读取通知内容，供任务与自动化使用 |
| Droidance 输入法 | 在虚拟屏中输入文字 |
| 查询应用列表 | 按名称找到并启动目标 App |
| 精确闹钟、开机启动、电池优化豁免 | 定时任务按时执行与恢复 |
| 安装未知应用 | 安装应用内下载的更新 |

运行时权限都可以在系统设置中随时撤回。无线调试配对成功后，Sidecar 不依赖 Wi‑Fi，被杀后会自动拉起；重置配对或重启手机可以停止它，关闭开发者选项后它不再自动拉起。应用更新后需要启动匹配新版的 Sidecar；如果无法自动恢复，请开启无线调试重新连接，或在已 Root 的设备上使用 Root。Root 模式使用 Root 授权启动和恢复通道，撤销授权后新的启动或恢复会失败。安全问题请按 [SECURITY](SECURITY.md) 私下报告。

**边界**

- **人工确认**：是否暂停交给你，由智能体按规则判断，并非硬性拦截。涉及资金、隐私或不可逆的操作，请让它停在确认页，由你亲自核对提交。
- **无侵入**：不 Hook、不注入、不修改任何第三方 App。

---

## 📱 兼容性与已知限制

- 无线调试配对和重启手机后恢复连接需要 Wi‑Fi；部分系统会在断网、重启或长时间闲置后关闭无线调试，需要时重新开启。
- Shizuku 需要单独安装并通过 ADB 或无线调试启动；手机重启后需重新启动。Root 启动的 Shizuku 请改用 Droidance 的 Root 连接。Shizuku 停止时，只要已有设备通道仍可用，任务就可以继续。
- Root 连接已在 Android 16 和 SukiSU 上完成真机验证；其他 Root 管理器或设备的行为可能不同。
- 部分厂商系统会限制后台运行，建议在系统设置中允许 Droidance 后台运行并关闭电池优化。
- 部分 App 会检测开发者选项或无线调试是否开启，可能拒绝运行或限制功能。
- 任务完成率与耗时取决于所用模型的视觉与推理能力；智能体报告"完成"不等于业务结果一定正确，重要结果请自行核对。

---

## 🗺️ 开发计划

- [ ] Pro 与 Ultra 模式：支持更多高级操作，并解锁极客模式
- [ ] 子智能体：支持子智能体在多个虚拟空间中并行执行任务

---

## 🤝 参与贡献

欢迎通过 [Issue](https://github.com/YbbLab/Droidance/issues) 反馈问题、提出建议，或直接提交 Pull Request。开始之前请阅读：

- [贡献指南](CONTRIBUTING.md)：开发环境、架构约定与验证方式
- [行为准则](CODE_OF_CONDUCT.md)
- [安全策略](SECURITY.md)：安全漏洞请通过 GitHub 私下报告，不要公开提交 Issue

关注我们：[X](https://x.com/ybblab2026) · [YouTube](https://www.youtube.com/@ybblab) · [哔哩哔哩](https://space.bilibili.com/3706972849310516) · [小红书](https://www.xiaohongshu.com/user/profile/69cb432b0000000032025c5e)

<a href="https://github.com/YbbLab/Droidance/graphs/contributors">
  <img src="https://contrib.rocks/image?repo=YbbLab/Droidance" alt="Contributors" />
</a>

---

## 📄 开源协议

本项目基于 [Apache License 2.0](LICENSE) 开源。你可以自由地使用、修改和分发（包括商业用途），但需要遵守以下要求：

- **保留声明**：分发源码或二进制时，请保留原有的版权声明、[LICENSE](LICENSE) 与 [NOTICE](NOTICE) 文件。
- **标注修改**：如果修改了源文件，请在修改过的文件中注明。
- **不得冒用名义**：协议不授予 Droidance 名称与 Logo 的商标使用权，基于本项目的衍生作品请勿以官方名义发布或宣传。
- **第三方组件**：项目包含的第三方组件遵循各自的许可证，详见 [NOTICE](NOTICE) 及 [随源码保留的许可证](app/src/main/assets/licenses/)。

### 免责声明

> [!IMPORTANT]
> 以下是使用风险提示，不限制 [Apache License 2.0](LICENSE) 授予的权利。

1. **遵守适用法律**：本项目是面向移动端智能体的开源项目；使用时请遵守适用法律和第三方服务条款。
2. **账号风险自担**：自动化操作可能违反第三方 App 的用户协议，**可能导致账号被警告、限制、封禁或数据丢失**，风险由使用者自行承担。建议不要在重要账号上使用。
3. **避免滥用**：请勿将本项目用于刷单刷量、批量注册、恶意抢购、绕过风控或验证码、未经授权采集他人信息、骚扰诈骗等违法或侵害他人权益的行为。
4. **AI 可能出错**：大模型的决策存在不确定性，可能误点、误输入。涉及资金、隐私或不可逆的操作请务必人工确认。因使用本项目造成的任何直接或间接损失，作者与贡献者不承担责任。
5. **第三方服务**：屏幕截图等数据会发送给你自行配置的模型服务商，请了解并遵守其隐私政策与使用条款。
6. **无关联声明**：本项目与文中提及的任何第三方 App 或服务商无关联，相关商标归各自所有者所有。

---

## 📈 项目动态

<a href="https://star-history.com/#YbbLab/Droidance&Date">
  <picture>
    <source media="(prefers-color-scheme: dark)" srcset="https://api.star-history.com/svg?repos=YbbLab/Droidance&type=Date&theme=dark" />
    <img alt="Star History Chart" src="https://api.star-history.com/svg?repos=YbbLab/Droidance&type=Date" />
  </picture>
</a>

<div align="center">

**如果 Droidance 让你的手机"跳"起来了，请点一个 ⭐ 支持我们。**

</div>
